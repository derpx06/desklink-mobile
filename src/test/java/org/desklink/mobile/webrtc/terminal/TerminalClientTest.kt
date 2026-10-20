/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc.terminal

import org.desklink.mobile.transport.TransportCallback
import org.desklink.mobile.transport.TransportError
import org.desklink.mobile.transport.TransportErrorCode
import org.desklink.mobile.webrtc.SessionPassword
import org.desklink.mobile.webrtc.SessionPasswordDenial
import org.desklink.mobile.webrtc.WebRtcWireBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

class TerminalClientTest {
    private val wire = WebRtcWireBinding("phone-1", "desktop-1", 7, 1)
    private val host = WebRtcWireBinding("desktop-1", "phone-1", 7, 1)

    private class Sent(val type: String, val payload: ByteArray)

    private class FakeSink(val reply: (ByteArray) -> Unit) : TerminalSink {
        val events = mutableListOf<String>()
        var text = StringBuilder()
        var replayingNow = false
        var resets = 0
        var size: Pair<Int, Int>? = null
        override fun beginSnapshot() { resets++; text = StringBuilder(); events += "reset" }
        override fun write(data: ByteArray) { text.append(String(data)); events += "write:${String(data)}" }
        override fun setReplaying(replaying: Boolean) { replayingNow = replaying; events += "replaying:$replaying" }
        override fun resize(rows: Int, cols: Int) { size = rows to cols; events += "resize:$rows,$cols" }
        override fun onEnded(exitStatus: Long?) { events += "ended:$exitStatus" }
    }

    /** Runs tasks inline unless [deferred], where they wait for [drain]. */
    private class TestExecutor(var deferred: Boolean = false) : Executor {
        private val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { if (deferred) queue.addLast(command) else command.run() }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private inner class Harness(deferred: Boolean = false) {
        val executor = TestExecutor(deferred)
        val sent = mutableListOf<Sent>()
        val sinks = mutableMapOf<String, FakeSink>()
        val discarded = mutableListOf<String>()
        var nextFailure: TransportError? = null
        val password = AtomicReference<String?>()
        val client = TerminalClient(
            sender = { type, payload, callback ->
                sent += Sent(type, payload)
                nextFailure?.let { callback.onFailure(it) } ?: callback.onSuccess()
            },
            sinkFactory = { id, _, _, reply -> FakeSink(reply).also { sinks[id] = it } },
            executor = executor,
            discard = { message, _ -> discarded += message },
            password = password,
        )

        fun controls(action: TerminalAction? = null): List<TerminalControl> = sent
            .filter { it.type == TerminalProtocol.CONTROL_MESSAGE_TYPE }
            .map { TerminalControl.fromJson(it.payload) }
            .filter { action == null || it.action == action }

        fun inputs(): List<TerminalChunk> = sent
            .filter { it.type == TerminalProtocol.CHUNK_MESSAGE_TYPE }
            .map { TerminalChunk.decode(it.payload) }

        fun ready(link: WebRtcWireBinding = wire) = client.onLinkReady(link)

        fun fromHost(control: TerminalControl, link: WebRtcWireBinding = wire) =
            client.onControl(link, control.toJson())

        fun hostControl(action: TerminalAction) = TerminalControl.create(action, host)

        fun listed(vararg infos: TerminalSessionInfo) =
            fromHost(hostControl(TerminalAction.SESSIONS).copy(sessions = infos.toList()))

        fun attached(id: String, offset: Long = 0, rows: Int = 24, cols: Int = 80) = fromHost(
            hostControl(TerminalAction.ATTACHED).copy(terminalId = id, offset = offset, rows = rows, cols = cols),
        )

        fun output(id: String, offset: Long, text: String, kind: ChunkKind = ChunkKind.OUTPUT) =
            client.onChunk(wire, TerminalChunk(kind, id, offset, text.toByteArray()).encode())

        fun error(message: String, id: String? = null) =
            fromHost(hostControl(TerminalAction.ERROR).copy(error = message, terminalId = id))

        /** Opens [id] on screen and completes the attach handshake. */
        fun open(id: String = "t1", rows: Int = 24, cols: Int = 80) {
            client.sink(id, rows, cols)
            client.attachOrResize(id, rows, cols)
            attached(id)
        }

        fun state() = client.state.value
        fun session(id: String = "t1") = state().sessions.first { it.id == id }
    }

    private val bash = TerminalSessionInfo("t1", "bash", 24, 80)

    // ---- list / create ---------------------------------------------------------------------

    @Test
    fun aReadyLinkAsksForTheSessionList() {
        val h = Harness()
        assertEquals(TerminalLinkState.DISCONNECTED, h.state().link)
        h.ready()
        assertEquals(TerminalLinkState.CONNECTED, h.state().link)
        assertEquals(listOf(TerminalAction.LIST), h.controls().map { it.action })
        assertFalse(h.state().listLoaded)

        h.listed(bash, TerminalSessionInfo("t2", "vim", 40, 100, exitStatus = 1))
        assertTrue(h.state().listLoaded)
        assertEquals(listOf("t1", "t2"), h.state().sessions.map { it.id })
        assertNull(h.session("t1").exitStatus)
        assertEquals(1L, h.session("t2").exitStatus)
    }

    @Test
    fun messagesCarryTheWireBinding() {
        val h = Harness()
        h.ready()
        val list = h.controls().single()
        assertEquals("phone-1", list.deviceId)
        assertEquals(7L, list.sessionId)
        assertEquals(1L, list.connectionGeneration)
    }

    @Test
    fun createThenTheHostReplyOpensTheNewSession() {
        val h = Harness()
        h.ready()
        h.client.create(30, 90)
        val create = h.controls(TerminalAction.CREATE).single()
        assertEquals(30, create.rows)
        assertEquals(90, create.cols)
        assertNull(create.command)

        h.fromHost(h.hostControl(TerminalAction.CREATED).copy(terminalId = "t9", rows = 30, cols = 90))
        assertEquals("t9", h.state().createdId)
        h.client.consumeCreated()
        assertNull(h.state().createdId)
        assertEquals("t9", h.state().sessions.single().id)
    }

    @Test
    fun createPassesAnArgumentVectorNeverAShellString() {
        val h = Harness()
        h.ready()
        h.client.create(24, 80, listOf("tmux", "attach", "-t", "a b; rm -rf ~"))
        assertEquals(listOf("tmux", "attach", "-t", "a b; rm -rf ~"), h.controls(TerminalAction.CREATE).single().command)
    }

    @Test
    fun everySessionListUpdatesTheLaunchers() {
        val h = Harness()
        h.ready()
        assertEquals(emptyList<String>(), h.state().launchers)
        h.fromHost(h.hostControl(TerminalAction.SESSIONS).copy(sessions = listOf(bash), launchers = listOf("claude")))
        assertEquals(listOf("claude"), h.state().launchers)
        // An agent uninstalled at the desk, or an older desktop that does not say.
        h.listed(bash)
        assertEquals(emptyList<String>(), h.state().launchers)
    }

    @Test
    fun nothingIsSentWhileTheLinkIsDown() {
        val h = Harness()
        h.client.refresh()
        h.client.create(24, 80)
        assertTrue(h.sent.isEmpty())
    }

    // ---- attach and output offsets ---------------------------------------------------------------

    @Test
    fun attachingAFreshSessionAsksForTheWholeStreamThenTakesThePhonesGeometry() {
        val h = Harness()
        h.ready()
        h.listed(bash) // the desktop's session is 24x80
        h.client.sink("t1", 24, 80)
        h.client.attachOrResize("t1", 50, 100)

        val attach = h.controls(TerminalAction.ATTACH).single()
        assertEquals("t1", attach.terminalId)
        assertEquals(0L, attach.offset)
        // The replay is laid out for the desktop's size, so the screen keeps it until the attach is done.
        assertEquals(24 to 80, h.sinks["t1"]!!.size)
        assertTrue(h.controls(TerminalAction.RESIZE).isEmpty())
        assertTrue(h.sinks["t1"]!!.replayingNow)
        assertFalse(h.session().attached)
        assertTrue(h.session().wanted)

        h.attached("t1")
        assertTrue(h.session().attached)
        assertFalse(h.sinks["t1"]!!.replayingNow)
        // Sent after the attach, which is what claims the write lease.
        val resize = h.controls().last()
        assertEquals(TerminalAction.RESIZE, resize.action)
        assertEquals(50, resize.rows)
        assertEquals(100, resize.cols)
        assertEquals(50 to 100, h.sinks["t1"]!!.size)
    }

    @Test
    fun aLayoutChangeDuringAnAttachDoesNotUndoTheDesktopsGeometry() {
        val h = Harness()
        h.ready(); h.listed(bash)
        h.client.sink("t1", 24, 80)
        h.client.attachOrResize("t1", 50, 100)
        h.client.attachOrResize("t1", 30, 100) // the keyboard opened mid-attach
        assertEquals(24 to 80, h.sinks["t1"]!!.size)
        h.attached("t1")
        assertEquals(30 to 100, h.sinks["t1"]!!.size)
        assertEquals(30, h.controls(TerminalAction.RESIZE).last().rows)
    }

    @Test
    fun outputAdvancesTheOffsetAndDuplicatesAreTrimmed() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        val sink = h.sinks["t1"]!!

        h.output("t1", 0, "hello ")
        h.output("t1", 6, "world")
        assertEquals("hello world", sink.text.toString())

        h.output("t1", 6, "world") // exact duplicate
        h.output("t1", 0, "hello ") // older
        h.output("t1", 8, "rldXYZ") // overlaps the tail: only "XYZ" is new
        assertEquals("hello worldXYZ", sink.text.toString())
        assertTrue(h.controls(TerminalAction.ATTACH).size == 1) // never re-attached
    }

    @Test
    fun aZeroLengthReplyChunkIsHarmless() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        h.output("t1", 0, "")
        assertEquals("", h.sinks["t1"]!!.text.toString())
    }

    @Test
    fun aGapResyncsFromTheLastOffsetOnce() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        h.output("t1", 0, "abc")
        h.output("t1", 10, "later") // bytes 3..10 never arrived
        h.output("t1", 15, "even later") // still waiting for the reply: no second attach

        val attaches = h.controls(TerminalAction.ATTACH)
        assertEquals(2, attaches.size)
        assertEquals(3L, attaches.last().offset)
        assertEquals("abc", h.sinks["t1"]!!.text.toString())
        assertTrue(h.sinks["t1"]!!.replayingNow)

        // The host answers with exactly the missing bytes, then Attached.
        h.output("t1", 3, "defghij")
        h.attached("t1", offset = 3)
        h.output("t1", 10, "later")
        assertEquals("abcdefghijlater", h.sinks["t1"]!!.text.toString())
        assertFalse(h.sinks["t1"]!!.replayingNow)
    }

    @Test
    fun aSnapshotResetsTheScreenAndRestartsTheOffsetAtItsHead() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        val sink = h.sinks["t1"]!!
        h.output("t1", 0, "old screen")

        // A snapshot in three pieces: the desktop numbers the pieces consecutively but output resumes at the first.
        h.output("t1", 500, "\u001bcAAAA", ChunkKind.SNAPSHOT)
        h.output("t1", 506, "BBBB", ChunkKind.SNAPSHOT)
        h.output("t1", 510, "CC", ChunkKind.SNAPSHOT)
        assertEquals(1, sink.resets)
        assertEquals("\u001bcAAAABBBBCC", sink.text.toString())

        h.output("t1", 500, "next")
        h.output("t1", 504, "!")
        assertEquals("\u001bcAAAABBBBCCnext!", sink.text.toString())
        assertEquals(1, sink.resets)
        assertEquals(1, h.controls(TerminalAction.ATTACH).size)
    }

    @Test
    fun aSecondSnapshotIsANewScreen() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        h.output("t1", 100, "one", ChunkKind.SNAPSHOT)
        h.output("t1", 100, "next")
        h.output("t1", 900, "two", ChunkKind.SNAPSHOT)
        assertEquals(2, h.sinks["t1"]!!.resets)
        h.output("t1", 900, "go")
        assertEquals("twogo", h.sinks["t1"]!!.text.toString())
    }

    @Test
    fun outputDoesNotLeakBetweenTerminals() {
        val h = Harness()
        h.ready(); h.listed(bash, TerminalSessionInfo("t2", "sh", 24, 80)); h.open("t1"); h.open("t2")
        h.output("t2", 0, "for two")
        assertEquals("", h.sinks["t1"]!!.text.toString())
        assertEquals("for two", h.sinks["t2"]!!.text.toString())
    }

    // ---- reconnect ----------------------------------------------------------------------------

    @Test
    fun reconnectingReattachesFromTheLastOffset() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        h.output("t1", 0, "0123456789")

        h.client.onLinkLost(wire)
        assertEquals(TerminalLinkState.DISCONNECTED, h.state().link)
        assertFalse(h.session().attached)
        assertTrue("stays wanted so it re-attaches", h.session().wanted)
        h.sent.clear()

        val next = WebRtcWireBinding("phone-1", "desktop-1", 8, 1)
        h.ready(next)
        assertEquals(TerminalAction.LIST, h.controls().first().action)
        val attach = h.controls(TerminalAction.ATTACH).single()
        assertEquals(10L, attach.offset)
        assertEquals("t1", attach.terminalId)
        assertEquals(8L, attach.sessionId)
        assertTrue(h.sinks["t1"]!!.replayingNow)

        // The host resumes with only the missing bytes.
        h.client.onChunk(next, TerminalChunk(ChunkKind.OUTPUT, "t1", 10, "abc".toByteArray()).encode())
        assertEquals("0123456789abc", h.sinks["t1"]!!.text.toString())
        assertEquals(0, h.sinks["t1"]!!.resets)

        // Once attached, the size is re-sent so the desktop matches the phone again.
        val nextHost = WebRtcWireBinding("desktop-1", "phone-1", 8, 1)
        h.fromHost(
            TerminalControl.create(TerminalAction.ATTACHED, nextHost).copy(terminalId = "t1", offset = 10, rows = 24, cols = 80),
            next,
        )
        assertEquals(TerminalAction.RESIZE, h.controls().last().action)
        assertEquals(8L, h.controls().last().sessionId)
    }

    @Test
    fun reconnectingAfterTheRingMovedOnResetsToASnapshot() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        h.output("t1", 0, "0123456789")
        h.client.onLinkLost(wire)
        h.ready()
        h.output("t1", 90_000, "\u001bcfresh", ChunkKind.SNAPSHOT)
        h.attached("t1", offset = 90_000)
        assertEquals("\u001bcfresh", h.sinks["t1"]!!.text.toString())
        assertEquals(1, h.sinks["t1"]!!.resets)
        h.output("t1", 90_000, "!")
        assertEquals("\u001bcfresh!", h.sinks["t1"]!!.text.toString())
    }

    @Test
    fun aLostLinkFromASupersededPeerIsIgnored() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        val next = WebRtcWireBinding("phone-1", "desktop-1", 8, 1)
        h.ready(next)
        h.client.onLinkLost(wire) // arrives late, from the old peer
        assertEquals(TerminalLinkState.CONNECTED, h.state().link)
    }

    @Test
    fun aRepeatedReadyForTheSamePeerDoesNotReattach() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        val before = h.sent.size
        h.ready()
        assertEquals(before, h.sent.size)
    }

    @Test
    fun aReconnectLaysItsReplayOutForTheGeometryThePhoneLastSet() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open(rows = 24, cols = 80)
        h.client.attachOrResize("t1", 50, 100) // the phone resized the desktop's terminal
        h.client.onLinkLost(wire)
        h.ready()
        assertEquals("not the 24x80 the list once said", 50 to 100, h.sinks["t1"]!!.size)
    }

    @Test
    fun aRecoveredPeerWithoutANewHandshakeReattachesAfterASendFailure() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        h.nextFailure = TransportError(TransportErrorCode.CLOSED, "closed")
        h.client.refresh()
        h.nextFailure = null
        assertEquals(TerminalLinkState.DISCONNECTED, h.state().link)
        h.sent.clear()
        h.ready() // same wire: the coordinator saw the peer come back
        assertEquals(TerminalLinkState.CONNECTED, h.state().link)
        assertNotNull(h.controls(TerminalAction.ATTACH).singleOrNull())
    }

    @Test
    fun otherSendFailuresSurfaceWithoutDroppingTheLink() {
        val h = Harness()
        h.ready()
        h.nextFailure = TransportError(TransportErrorCode.SEND_FAILED, "channel is full")
        h.client.refresh()
        assertEquals("channel is full", h.state().error)
        assertEquals(TerminalLinkState.CONNECTED, h.state().link)
        h.client.clearError()
        assertNull(h.state().error)
    }

    // ---- detach / close ------------------------------------------------------------------------

    @Test
    fun detachStopsOutputButKeepsTheOffsetForLater() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        h.output("t1", 0, "abc")
        h.client.detach("t1")
        assertEquals(TerminalAction.DETACH, h.controls().last().action)
        assertFalse(h.session().wanted)

        h.sent.clear()
        h.client.attachOrResize("t1", 24, 80)
        assertEquals(3L, h.controls(TerminalAction.ATTACH).single().offset)
    }

    @Test
    fun aDetachedSessionIsNotReattachedAfterAReconnect() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        h.client.detach("t1")
        h.client.onLinkLost(wire)
        h.sent.clear()
        h.ready()
        assertTrue(h.controls(TerminalAction.ATTACH).isEmpty())
    }

    @Test
    fun closeAsksTheHostAndTheReplyRemovesTheSession() {
        val h = Harness()
        h.ready(); h.listed(bash)
        h.client.close("t1")
        assertEquals("t1", h.controls(TerminalAction.CLOSE).single().terminalId)
        h.fromHost(h.hostControl(TerminalAction.CLOSED).copy(terminalId = "t1"))
        assertTrue(h.state().sessions.isEmpty())
    }

    @Test
    fun aSessionThatExitedOnItsOwnStaysListedWithItsStatus() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        h.fromHost(h.hostControl(TerminalAction.CLOSED).copy(terminalId = "t1", exitStatus = 130))
        assertEquals(130L, h.session().exitStatus)
        assertFalse(h.session().attached)
        assertEquals("ended:130", h.sinks["t1"]!!.events.last())
    }

    @Test
    fun aSessionMissingFromTheListIsGoneButItsScreenSurvivesWhileShown() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        h.listed() // the desktop restarted its registry
        assertTrue(h.session().gone)
        assertNotNull("the screen must still be readable", h.sinks["t1"])
        h.client.leave("t1")
        assertTrue(h.state().sessions.isEmpty())
    }

    // ---- errors --------------------------------------------------------------------------------

    @Test
    fun aHostErrorSurfaces() {
        val h = Harness()
        h.ready()
        h.error("No such DeskLink terminal")
        assertEquals("No such DeskLink terminal", h.state().error)
        assertFalse(h.state().notAllowed)
    }

    @Test
    fun terminalNotAllowedIsReportedDistinctlyAndClearsOnSuccess() {
        val h = Harness()
        h.ready()
        h.error(TerminalProtocol.ERROR_NOT_ALLOWED)
        assertTrue(h.state().notAllowed)
        assertNull("not-allowed is a state, not a transient error", h.state().error)

        // The user enabled it on the desktop and retried.
        h.client.refresh()
        h.listed()
        assertFalse(h.state().notAllowed)
        assertTrue(h.state().listLoaded)
    }

    @Test
    fun revokingAccessMidSessionKeepsTheSessionAndReattachesOnRetry() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        h.output("t1", 0, "abc")

        // The desktop revokes: one `closed` per attached terminal carrying the error, then a bare `error`.
        h.fromHost(h.hostControl(TerminalAction.CLOSED).copy(terminalId = "t1", error = TerminalProtocol.ERROR_NOT_ALLOWED))
        h.error(TerminalProtocol.ERROR_NOT_ALLOWED)
        assertTrue(h.state().notAllowed)
        assertNull("a revoke is a state, not a failure", h.state().error)
        assertTrue("the session still runs on the desktop", h.state().sessions.any { it.id == "t1" && !it.gone })
        assertFalse(h.session().attached)
        assertTrue(h.session().wanted)
        assertNull(h.session().exitStatus)
        assertFalse(h.sinks["t1"]!!.events.any { it.startsWith("ended") })

        // Typing into a session the desktop no longer lets us drive goes nowhere.
        h.sent.clear()
        h.client.sendInput("t1", "x".toByteArray())
        assertTrue(h.inputs().isEmpty())

        // The person allows it on the desktop and taps Retry: one list, then one attach from the last offset.
        h.client.refresh()
        assertEquals(1, h.controls(TerminalAction.LIST).size)
        assertTrue("nothing is retried until asked", h.controls(TerminalAction.ATTACH).isEmpty())
        h.listed(bash)
        assertFalse(h.state().notAllowed)
        assertEquals(3L, h.controls(TerminalAction.ATTACH).single().offset)
    }

    // ---- session password -------------------------------------------------------------------------

    @Test
    fun aKnownPasswordGoesOnEveryControlButDetach() {
        val h = Harness()
        h.password.set("secret")
        h.ready(); h.listed(bash); h.open()
        h.client.attachOrResize("t1", 30, 90)
        h.client.create(24, 80)
        h.client.close("t1")
        h.client.detach("t1")
        val byAction = h.controls().groupBy { it.action }
        listOf(TerminalAction.LIST, TerminalAction.ATTACH, TerminalAction.RESIZE, TerminalAction.CREATE, TerminalAction.CLOSE)
            .forEach { action -> assertTrue(action.wire, byAction.getValue(action).all { it.password == "secret" }) }
        assertNull(byAction.getValue(TerminalAction.DETACH).single().password)
    }

    @Test
    fun thePasswordIsKeptAcrossALinkDropAndOfferedToTheNextConnection() {
        val h = Harness()
        h.password.set("secret")
        h.ready(); h.listed(bash)
        h.client.onLinkLost(wire)
        assertEquals("secret", h.password.get())
        h.sent.clear()
        h.ready(WebRtcWireBinding("phone-1", "desktop-1", 7, 2))
        assertEquals("a Wi-Fi switch does not ask again", "secret", h.controls(TerminalAction.LIST).single().password)
    }

    @Test
    fun passwordRequiredAsksForThePasswordAndIsNotARefusalOfAccess() {
        val h = Harness()
        h.ready()
        h.error(SessionPassword.REQUIRED)
        assertEquals(SessionPasswordDenial.Required, h.state().passwordNeeded)
        assertFalse(h.state().notAllowed)
        assertNull("a password prompt, not a snackbar", h.state().error)
    }

    @Test
    fun aGivenPasswordIsTriedOnTheListAloneThenResumesWhatWasRefused() {
        val h = Harness()
        h.ready(); h.listed(bash)
        h.client.sink("t1", 24, 80)
        h.client.attachOrResize("t1", 24, 80)
        h.error(SessionPassword.REQUIRED, id = "t1")
        h.client.create(24, 80)
        h.error(SessionPassword.REQUIRED)
        assertEquals(SessionPasswordDenial.Required, h.state().passwordNeeded)
        assertTrue(h.session().wanted)

        h.sent.clear()
        h.client.unlock("secret")
        h.client.unlock("secret") // a double tap is not a second guess
        h.client.refresh() // the list screen's poll does not carry an unproven password
        assertNull(h.state().passwordNeeded)
        assertEquals(listOf("secret", null), h.controls().map { it.password })
        assertNull("not shared until the desktop accepts it", h.password.get())

        h.sent.clear()
        h.listed(bash)
        assertEquals("secret", h.password.get())
        assertEquals("secret", h.controls(TerminalAction.ATTACH).single().password)
        assertEquals("secret", h.controls(TerminalAction.CREATE).single().password)
    }

    @Test
    fun aSessionWhosePasswordPromptWasCancelledIsNotStartedByALaterUnlock() {
        val h = Harness()
        h.ready(); h.listed(bash)
        h.client.create(24, 80, listOf("claude"))
        h.error(SessionPassword.REQUIRED)
        h.client.cancelUnlock()

        h.client.unlock("secret")
        h.sent.clear()
        h.listed(bash)
        assertTrue("unlocking to browse must not start the cancelled session", h.controls(TerminalAction.CREATE).isEmpty())
    }

    @Test
    fun aWrongPasswordIsForgottenAndNotHiddenByLaterAnswers() {
        val h = Harness()
        h.ready()
        h.error(SessionPassword.REQUIRED)
        h.client.unlock("guess")
        h.error(SessionPassword.REQUIRED) // a poll sent without it
        assertNull(h.state().passwordNeeded)
        h.error(SessionPassword.WRONG)
        assertEquals(SessionPasswordDenial.Wrong, h.state().passwordNeeded)
        h.error(SessionPassword.REQUIRED)
        assertEquals(SessionPasswordDenial.Wrong, h.state().passwordNeeded)
        h.sent.clear()
        h.client.refresh()
        assertNull(h.controls().single().password)

        h.error("${SessionPassword.LOCKED}:60")
        assertEquals(SessionPasswordDenial.Locked(60), h.state().passwordNeeded)
        assertFalse(h.state().notAllowed)
    }

    // ---- ended from the desk -------------------------------------------------------------------------

    @Test
    fun sessionsEndedFromTheDeskAreDetachedNotRefused() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        h.output("t1", 0, "abc")

        h.sent.clear()
        h.fromHost(h.hostControl(TerminalAction.CLOSED).copy(terminalId = "t1", error = TerminalProtocol.ERROR_TERMINAL_ENDED))
        h.error(TerminalProtocol.ERROR_TERMINAL_ENDED)
        assertTrue(h.state().endedByComputer)
        assertFalse(h.state().notAllowed)
        assertNull(h.state().error)
        assertFalse(h.session().attached)
        assertFalse(h.session().wanted)
        assertFalse("it still runs at the desk", h.session().gone)
        assertFalse(h.sinks["t1"]!!.events.any { it.startsWith("ended") })
        assertEquals(listOf(TerminalAction.LIST), h.controls().map { it.action })

        // Not picked up again by a list or a reconnect, only when the person opens it.
        h.listed(bash)
        h.client.onLinkLost(wire)
        h.ready()
        assertTrue(h.controls(TerminalAction.ATTACH).isEmpty())
        h.client.consumeEnded()
        assertFalse(h.state().endedByComputer)
        h.client.attachOrResize("t1", 24, 80)
        assertEquals(3L, h.controls(TerminalAction.ATTACH).single().offset)
    }

    @Test
    fun aDeniedRequestIsNotRetriedInALoop() {
        val h = Harness()
        h.ready()
        h.error(TerminalProtocol.ERROR_NOT_ALLOWED, id = "t1") // the desktop echoes the request's terminal id
        h.error(TerminalProtocol.ERROR_NOT_ALLOWED)
        assertTrue(h.state().notAllowed)
        assertEquals("only the initial list request was ever sent", 1, h.sent.size)
    }

    @Test
    fun theDesktopsCapErrorsSurfaceAsPlainErrors() {
        val h = Harness()
        h.ready()
        h.error("Too many terminal sessions on this device (8)")
        assertEquals("Too many terminal sessions on this device (8)", h.state().error)
        assertFalse(h.state().notAllowed)
    }

    @Test
    fun aRefusedAttachDropsTheSessionInsteadOfWaitingForever() {
        val h = Harness()
        h.ready(); h.listed(bash)
        h.client.sink("t1", 24, 80)
        h.client.attachOrResize("t1", 24, 80)
        h.error("No such DeskLink terminal", id = "t1")
        assertTrue(h.session().gone)
        assertFalse(h.session().wanted)
        assertFalse(h.sinks["t1"]!!.replayingNow)
    }

    @Test
    fun aBackedUpDesktopRefusingAnAttachDoesNotEndTheSession() {
        val h = Harness()
        h.ready(); h.listed(bash)
        h.client.sink("t1", 24, 80)
        h.client.attachOrResize("t1", 24, 80)
        h.output("t1", 0, "abc") // some of it arrived before the refusal
        h.sent.clear()
        h.error("DeskLink terminal output is backed up; try again shortly", id = "t1")

        assertFalse("the session still exists", h.session().gone)
        assertFalse(h.session().wanted)
        assertFalse(h.session().attached)
        assertEquals("DeskLink terminal output is backed up; try again shortly", h.state().error)
        assertEquals("the list settles whether it exists", 1, h.controls(TerminalAction.LIST).size)
        assertFalse(h.sinks["t1"]!!.replayingNow)

        h.client.reattach("t1")
        assertEquals(3L, h.controls(TerminalAction.ATTACH).single().offset)
        assertTrue(h.session().wanted)
    }

    @Test
    fun reattachDoesNothingWhileAttachedOrDisconnected() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        h.sent.clear()
        h.client.reattach("t1")
        assertTrue(h.sent.isEmpty())
        h.client.onLinkLost(wire)
        h.client.reattach("t1")
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun notAllowedDuringAttachLeavesNothingAttached() {
        val h = Harness()
        h.ready(); h.listed(bash)
        h.client.sink("t1", 24, 80)
        h.client.attachOrResize("t1", 24, 80)
        h.error(TerminalProtocol.ERROR_NOT_ALLOWED)
        assertTrue(h.state().notAllowed)
        assertFalse(h.session().attached)
    }

    // ---- hostile input ---------------------------------------------------------------------------

    @Test
    fun hostileFramesAreDiscardedNotApplied() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        h.output("t1", 0, "ok")

        // Wrong direction: an INPUT frame from the host.
        h.client.onChunk(wire, TerminalChunk(ChunkKind.INPUT, "t1", 0, "rm -rf".toByteArray()).encode())
        // Garbage.
        h.client.onChunk(wire, byteArrayOf(1, 2, 3))
        h.client.onControl(wire, "{}".toByteArray())
        // Client-authored action from the host.
        h.fromHost(h.hostControl(TerminalAction.LIST))
        // Binding of another session.
        h.fromHost(TerminalControl.create(TerminalAction.SESSIONS, WebRtcWireBinding("desktop-1", "phone-1", 99, 1)).copy(sessions = emptyList()))
        // Unknown terminal.
        h.output("nope", 0, "x")

        assertEquals("ok", h.sinks["t1"]!!.text.toString())
        assertEquals(1, h.state().sessions.size)
        assertEquals(5, h.discarded.size)
    }

    @Test
    fun framesForAStaleLinkAreIgnored() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        val stale = WebRtcWireBinding("phone-1", "desktop-1", 3, 1)
        h.client.onChunk(stale, TerminalChunk(ChunkKind.OUTPUT, "t1", 0, "stale".toByteArray()).encode())
        h.client.onControl(stale, h.hostControl(TerminalAction.SESSIONS).copy(sessions = emptyList(), sessionId = 3).toJson())
        assertEquals("", h.sinks["t1"]!!.text.toString())
        assertEquals(1, h.state().sessions.size)
    }

    @Test
    fun aFloodBeyondTheInboundBoundIsDroppedAndRepairedByResync() {
        val h = Harness(deferred = true)
        h.ready(); h.executor.drain()
        h.listed(bash); h.executor.drain()
        h.client.sink("t1", 24, 80)
        h.client.attachOrResize("t1", 24, 80); h.executor.drain()
        h.attached("t1"); h.executor.drain()

        val piece = "x".repeat(TerminalProtocol.MAX_CHUNK_BYTES)
        val count = (TerminalClient.MAX_INBOUND_BYTES / TerminalProtocol.MAX_CHUNK_BYTES).toInt() + 40
        repeat(count) { h.output("t1", it.toLong() * piece.length, piece) } // nothing has run yet
        h.executor.drain()

        val delivered = h.sinks["t1"]!!.text.length
        assertTrue("some must have been dropped", delivered < count * piece.length)

        // The next chunk that arrives shows the gap and triggers exactly one resync.
        h.output("t1", count.toLong() * piece.length, "tail"); h.executor.drain()
        val resync = h.controls(TerminalAction.ATTACH).last()
        assertEquals(delivered.toLong(), resync.offset)
        assertEquals(2, h.controls(TerminalAction.ATTACH).size)
    }

    // ---- input ---------------------------------------------------------------------------------------

    @Test
    fun keystrokesAreSentAsInputChunks() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        h.client.sendInput("t1", "ls\r".toByteArray())
        val input = h.inputs().single()
        assertEquals(ChunkKind.INPUT, input.kind)
        assertEquals("t1", input.terminalId)
        assertEquals("ls\r", String(input.data))
    }

    @Test
    fun keystrokesQueuedTogetherLeaveTogether() {
        val h = Harness(deferred = true)
        h.ready(); h.executor.drain()
        h.listed(bash); h.executor.drain()
        h.client.sink("t1", 24, 80)
        h.client.attachOrResize("t1", 24, 80); h.executor.drain()
        h.attached("t1"); h.executor.drain()

        "hello".forEach { h.client.sendInput("t1", byteArrayOf(it.code.toByte())) }
        h.executor.drain()
        assertEquals(listOf("hello"), h.inputs().map { String(it.data) })
    }

    @Test
    fun aLargePasteIsSplitIntoWireLegalFrames() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        h.client.sendInput("t1", ByteArray(TerminalProtocol.MAX_CHUNK_BYTES * 2 + 5) { 'p'.code.toByte() })
        assertEquals(listOf(16384, 16384, 5), h.inputs().map { it.data.size })
    }

    @Test
    fun inputIsDroppedWhenNotAttachedOrDisconnected() {
        val h = Harness()
        h.ready(); h.listed(bash)
        h.client.sendInput("t1", "x".toByteArray()) // not attached yet
        h.open()
        h.client.onLinkLost(wire)
        h.client.sendInput("t1", "y".toByteArray()) // link down
        assertTrue(h.inputs().isEmpty())
    }

    @Test
    fun anUnboundedBacklogOfInputIsRefused() {
        val h = Harness(deferred = true)
        h.ready(); h.executor.drain()
        h.listed(bash); h.executor.drain()
        h.client.sink("t1", 24, 80)
        h.client.attachOrResize("t1", 24, 80); h.executor.drain()
        h.attached("t1"); h.executor.drain()
        repeat(40) { h.client.sendInput("t1", ByteArray(16 * 1024)) } // 640 KiB before anything runs
        h.executor.drain()
        assertTrue(h.inputs().sumOf { it.data.size } <= TerminalClient.MAX_PENDING_INPUT_BYTES)
        assertNotNull(h.state().error)
    }

    @Test
    fun theEmulatorsRepliesGoBackToTheHostAsInput() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open()
        h.sinks["t1"]!!.reply("\u001b[1;1R".toByteArray()) // a cursor-position report
        assertEquals("\u001b[1;1R", String(h.inputs().single().data))
    }

    // ---- resize --------------------------------------------------------------------------------------

    @Test
    fun aChangedGridIsSentOnceAndAnUnchangedOneIsNot() {
        val h = Harness()
        h.ready(); h.listed(bash); h.open(rows = 24, cols = 80)
        h.sent.clear()
        h.client.attachOrResize("t1", 24, 80)
        assertTrue(h.sent.isEmpty())
        h.client.attachOrResize("t1", 40, 60)
        val resize = h.controls(TerminalAction.RESIZE).single()
        assertEquals(40, resize.rows)
        assertEquals(60, resize.cols)
        assertEquals(40 to 60, h.sinks["t1"]!!.size)
    }

    @Test
    fun dimensionsAreClampedToTheWireLimits() {
        val h = Harness()
        h.ready(); h.listed(bash)
        h.client.sink("t1", 24, 80)
        h.client.attachOrResize("t1", 5000, 0)
        h.attached("t1")
        val resize = h.controls(TerminalAction.RESIZE).single()
        assertEquals(TerminalProtocol.MAX_DIMENSION, resize.rows)
        assertTrue(resize.cols!! >= 1)
    }
}
