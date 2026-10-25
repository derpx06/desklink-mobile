/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.telephony

import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsTest {
    private var clock = 1_000L
    private var ids = 0
    private fun tracker() = CallTracker({ clock }) { "c${++ids}" }

    @Test
    fun anIncomingCallRingsIsAnsweredAndEnds() {
        val t = tracker()
        assertNull(t.onLine(LineState.RINGING))
        assertEquals(listOf(TrackedCall("c1", "ringing", incoming = true, startedAt = null)), t.calls)
        clock = 5_000
        assertNull(t.onLine(LineState.OFFHOOK))
        assertEquals(TrackedCall("c1", "talking", incoming = true, startedAt = 5_000), t.calls.single())
        assertNull(t.onLine(LineState.IDLE))
        assertTrue(t.calls.isEmpty())
    }

    @Test
    fun aLookupForOneCallIsNeverWrittenOntoAnother() {
        val t = tracker()
        t.onLine(LineState.RINGING) // c1 rings; its contact lookup starts
        val forFirst = t.ringing()!!.callId
        t.onLine(LineState.IDLE) // it ends, another call rings before the answer comes back
        t.onLine(LineState.RINGING)
        assertEquals("c2", t.ringing()!!.callId)
        assertFalse("the answer is dropped", t.annotate("Ada", "+15551234", null, forCallId = forFirst))
        assertNull(t.calls.single().contactName)
        assertNull(t.calls.single().number)
        assertTrue(t.annotate("Grace", "+15559999", null, forCallId = "c2"))
        assertEquals("Grace", t.calls.single().contactName)
    }

    @Test
    fun anUnansweredCallIsMissedButADeclinedOneIsNot() {
        val t = tracker()
        t.onLine(LineState.RINGING)
        assertEquals("c1", t.onLine(LineState.IDLE)?.callId)
        t.onLine(LineState.RINGING)
        t.markDeclined()
        assertNull(t.onLine(LineState.IDLE))
    }

    @Test
    fun anOutgoingCallAndACallWaiting() {
        val t = tracker()
        t.onLine(LineState.OFFHOOK)
        assertEquals(TrackedCall("c1", "talking", incoming = false, startedAt = 1_000), t.calls.single())
        // A second call rings while the first is up, then is answered: the first is held.
        t.onLine(LineState.RINGING)
        assertEquals(listOf("talking", "ringing"), t.calls.map { it.state })
        t.onLine(LineState.OFFHOOK)
        assertEquals(listOf("held", "talking"), t.calls.map { it.state })
    }

    @Test
    fun theSnapshotNamesCallersOnlyWhenAllowed() {
        val calls = listOf(TrackedCall("c1", "ringing", true, null, contactName = "Ada", number = "+15551234"))
        val hidden = CallTracker.snapshotPacket(calls, muted = false, silenced = true, showCaller = false)
        assertEquals(DeskLinkProtocol.PACKET_TYPE_TELEPHONY, hidden.type)
        val call = hidden.getJSONArray("calls")!!.getJSONObject(0)
        assertEquals("c1", call.getString("callId"))
        assertEquals("ringing", call.getString("state"))
        assertTrue(call.getBoolean("incoming"))
        assertFalse(call.has("contactName"))
        assertFalse(call.has("number"))
        assertFalse(call.has("startedAt"))
        assertTrue(hidden.getBoolean("silenced"))
        val shown = CallTracker.snapshotPacket(calls, muted = false, silenced = false, showCaller = true).getJSONArray("calls")!!.getJSONObject(0)
        assertEquals("Ada", shown.getString("contactName"))
        assertEquals("+15551234", shown.getString("number"))
        // Off or without permission: the empty form.
        val empty = CallTracker.emptySnapshot()
        assertEquals(0, empty.getJSONArray("calls")!!.length())
        assertFalse(empty.getBoolean("muted"))
        val missed = CallTracker.missedPacket(calls.single(), at = 9, showCaller = false).getJSONObject("missed")!!
        assertEquals("c1", missed.getString("callId"))
        assertEquals(9L, missed.getLong("at"))
        assertFalse(missed.has("number"))
    }

    @Test
    fun dialerDirectionMarksDoNotHideANumber() {
        assertEquals("+1 555 123 4567", CallTracker.dialable("\u2066+1 555 123 4567\u2069"))
        assertEquals("+1 555", CallTracker.dialable("\u202A\u200E+1 555\u200F\u202C"))
        assertEquals("Ada", CallTracker.plain("\u2068Ada\u2069"))
    }

    @Test
    fun aCallUpAcrossAnAppRestartKeepsItsIdAndTimer() {
        val first = tracker()
        first.onLine(LineState.RINGING)
        clock = 5_000
        first.onLine(LineState.OFFHOOK)
        val saved = CallTracker.decode(CallTracker.encode(CallTracker.toSave(first.calls)!!))
        assertEquals(first.calls.single(), saved)
        clock = 60_000
        val restarted = CallTracker({ clock }, saved) { "c${++ids}" }
        restarted.onLine(LineState.OFFHOOK)
        assertEquals(first.calls.single(), restarted.calls.single())
        // Once the line has been seen, a later call is a new one.
        val idleFirst = CallTracker({ clock }, saved) { "new" }
        idleFirst.onLine(LineState.IDLE)
        idleFirst.onLine(LineState.OFFHOOK)
        assertEquals("new", idleFirst.calls.single().callId)
        assertEquals(60_000L, idleFirst.calls.single().startedAt)
    }

    @Test
    fun onlyDialableNumbersAreDialled() {
        assertEquals("+1 (555) 123-4567", CallTracker.dialable(" +1 (555) 123-4567 "))
        assertNull(CallTracker.dialable("Ada Lovelace"))
        assertNull(CallTracker.dialable("*#"))
        assertNull(CallTracker.dialable("1".repeat(33)))
        assertNull(CallTracker.dialable(null))
    }

    private class FakeControls(
        var answer: Boolean = true,
        var dial: Boolean = true,
        var endCallWorks: Boolean = true,
        var notificationWorks: Boolean = false,
        var micWorks: Boolean = true,
        var messagesWork: Boolean = true,
    ) : CallControls {
        val done = mutableListOf<String>()
        override fun canAnswer() = answer
        override fun canDial() = dial
        override fun acceptRingingCall() = true.also { done += "accept" }
        override fun endCall() = endCallWorks.also { if (it) done += "end" }
        override fun notificationAction(hangUp: Boolean) = notificationWorks.also { if (it) done += "notification" }
        override fun muteRinger() = true.also { done += "silence" }
        override fun setMicrophoneMute(mute: Boolean) = micWorks.also { if (it) done += "mic:$mute" }
        override fun placeCall(number: String) = true.also { done += "dial:$number" }
        override fun askToPlaceCall(number: String) = true.also { done += "ask:$number" }
        override fun openMessages(number: String) = messagesWork.also { if (it) done += "message:$number" }
    }

    @Test
    fun messageOpensComposeForADialableNumberOnly() {
        val t = tracker()
        val controls = FakeControls()
        // No call needed: it is not about a call.
        assertNull(CallRequests.handle("message", "\u2066+1 555 0100\u2069", t, controls, CallFlags()))
        assertEquals(listOf("message:+1 555 0100"), controls.done)
        assertEquals(CallRequests.ERROR_FAILED, CallRequests.handle("message", "Ada", t, controls, CallFlags()))
        assertEquals(CallRequests.ERROR_FAILED, CallRequests.handle("message", null, t, controls, CallFlags()))
        // No messaging app (or no way to show it): failed, as K5 says.
        assertEquals(CallRequests.ERROR_FAILED, CallRequests.handle("message", "555", t, FakeControls(messagesWork = false), CallFlags()))
    }

    @Test
    fun aSharedContactsUidGoesOutEvenWithoutShowCaller() {
        val t = tracker()
        t.onLine(LineState.RINGING)
        t.annotate(null, null, contactUid = "42")
        val call = CallTracker.snapshotPacket(t.calls, muted = false, silenced = false, showCaller = false).getJSONArray("calls")!!.getJSONObject(0)
        assertEquals("42", call.getString("contactUid"))
        assertFalse(call.has("contactName"))
        assertFalse(call.has("number"))
        val missed = t.onLine(LineState.IDLE)!!
        val packet = CallTracker.missedPacket(missed, at = 9, showCaller = false).getJSONObject("missed")!!
        assertEquals("42", packet.getString("contactUid"))
        assertFalse(packet.has("number"))
        // Not shared: no uid at all.
        assertFalse(CallTracker.snapshotPacket(listOf(TrackedCall("c9", "ringing", true, null)), false, false, true)
            .getJSONArray("calls")!!.getJSONObject(0).has("contactUid"))
    }

    @Test
    fun requestsActOnTheRightCallAndSayWhyNot() {
        val t = tracker()
        val flags = CallFlags()
        val controls = FakeControls()
        assertEquals(CallRequests.ERROR_NO_CALL, CallRequests.handle("answer", null, t, controls, flags))
        t.onLine(LineState.RINGING)
        assertNull(CallRequests.handle("silence", null, t, controls, flags))
        assertTrue(flags.silenced)
        assertEquals(CallRequests.ERROR_PERMISSION, CallRequests.handle("answer", null, t, FakeControls(answer = false), flags))
        assertNull(CallRequests.handle("answer", null, t, controls, flags))
        t.onLine(LineState.OFFHOOK)
        assertNull(CallRequests.handle("mute", null, t, controls, flags))
        assertTrue(flags.muted)
        assertEquals(CallRequests.ERROR_FAILED, CallRequests.handle("unmute", null, t, FakeControls(micWorks = false), flags))
        assertNull(CallRequests.handle("end", null, t, controls, flags))
        assertEquals(listOf("silence", "accept", "mic:true", "end"), controls.done)
        assertEquals(CallRequests.ERROR_FAILED, CallRequests.handle("bogus", null, t, controls, flags))
    }

    @Test
    fun declineFallsBackToTheDialersOwnButtonAndIsNotMissed() {
        val t = tracker()
        t.onLine(LineState.RINGING)
        val old = FakeControls(endCallWorks = false, notificationWorks = true)
        assertNull(CallRequests.handle("decline", null, t, old, CallFlags()))
        assertEquals(listOf("notification"), old.done)
        assertNull(t.onLine(LineState.IDLE)) // declined, so not reported as missed
        t.onLine(LineState.RINGING)
        assertEquals(CallRequests.ERROR_PERMISSION, CallRequests.handle("decline", null, t, FakeControls(answer = false, endCallWorks = false), CallFlags()))
    }

    @Test
    fun dialNeedsThePermissionAndARealNumber() {
        val t = tracker()
        val controls = FakeControls()
        assertEquals(CallRequests.ERROR_PERMISSION, CallRequests.handle("dial", "5551234", t, FakeControls(dial = false), CallFlags()))
        assertEquals(CallRequests.ERROR_FAILED, CallRequests.handle("dial", "not a number", t, controls, CallFlags()))
        assertNull(CallRequests.handle("dial", "5551234", t, controls, CallFlags()))
        assertEquals("the phone's person decides: a request only asks", listOf("ask:5551234"), controls.done)
        assertNotNull(CallTracker.errorPacket("no-call", "answer").getString("error"))
    }

    @Test
    fun hostileNumbersNeverBecomeACallOrAnMmiCode() {
        for (hostile in listOf("**21*+19005551234#", "*#06#", "#31#5551234", "**04*1234*5678*5678#", "*21#", "5551234#", "555*1234", "++1555", "1+555", "+1 555\u0000", "12345678901234567890123456789012345", "")) {
            assertNull("refused: $hostile", CallTracker.dialable(hostile))
            val controls = FakeControls()
            assertEquals(CallRequests.ERROR_FAILED, CallRequests.handle("dial", hostile, tracker(), controls, CallFlags()))
            assertEquals(emptyList<String>(), controls.done)
        }
        assertEquals("+1 (555) 123-4567", CallTracker.dialable("+1 (555) 123-4567"))
        assertEquals("5551234", CallTracker.dialable("5551234"))
    }

    @Test
    fun aCallAskIsOneTimeBoundToItsDeskAndNumberAndRateLimited() {
        var now = 0L
        val approvals = CallApprovals { now }
        val first = approvals.ask("desk-1", "5551234")!!
        assertEquals("5551234", approvals.take(first.nonce)!!.number)
        assertNull("used once", approvals.take(first.nonce))
        val second = approvals.ask("desk-1", "5559999")!!
        now += CallApprovals.LIFETIME_MS + 1
        assertNull("expired", approvals.take(second.nonce))
        assertNull(approvals.take("not-a-nonce"))
        assertNull(approvals.take(null))
        // Five a minute, then no more until the minute is over.
        now = 0
        val rated = CallApprovals { now }
        repeat(CallApprovals.MAX_ASKS_PER_WINDOW) { assertNotNull(rated.ask("desk-2", "5551234")) }
        assertNull(rated.ask("desk-2", "5551234"))
        assertNotNull("another desk is unaffected", rated.ask("desk-3", "5551234"))
        now = CallApprovals.WINDOW_MS
        assertNotNull(rated.ask("desk-2", "5551234"))
    }

    @Test
    fun askingAgainReplacesWhatThatDeskAskedBefore() {
        val approvals = CallApprovals { 0L }
        val old = approvals.ask("desk-1", "5551234")!!
        val new = approvals.ask("desk-1", "5559999")!!
        assertNull(approvals.take(old.nonce))
        assertEquals("5559999", approvals.take(new.nonce)!!.number)
    }

    @Test
    fun theDialersEndButtonIsFoundByWholeTitleAndNeverBySubstring() {
        assertEquals(1, CallRequests.endAction(listOf("Answer", "Decline"), hangUp = false))
        assertEquals(1, CallRequests.endAction(listOf("Mute", "Hang up"), hangUp = true))
        assertEquals(0, CallRequests.endAction(listOf(" END CALL ", "Speaker"), hangUp = true))
        assertNull("'Send message' is not 'end'", CallRequests.endAction(listOf("Answer", "Send message"), hangUp = false))
        assertNull(CallRequests.endAction(listOf("Friend", "Attend", "Dismiss"), hangUp = true))
        assertNull("two candidates: not tapped", CallRequests.endAction(listOf("Decline", "Reject"), hangUp = false))
        assertNull(CallRequests.endAction(listOf(null, ""), hangUp = true))
    }
}
