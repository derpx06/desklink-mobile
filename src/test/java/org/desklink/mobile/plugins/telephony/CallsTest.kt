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
        assertEquals(listOf("dial:5551234"), controls.done)
        assertNotNull(CallTracker.errorPacket("no-call", "answer").getString("error"))
    }
}
