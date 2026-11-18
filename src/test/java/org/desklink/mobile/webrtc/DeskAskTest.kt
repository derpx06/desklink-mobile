/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.desklink.mobile.Device
import org.desklink.mobile.session.DeviceManager
import org.desklink.mobile.session.PairingState
import org.desklink.mobile.transport.SessionTransport
import org.desklink.mobile.webrtc.WebRtcSessionCoordinator.Companion.negotiationInFlight
import org.desklink.mobile.webrtc.terminal.TerminalClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** QA4-17: a responder phone with a live LAN link asks a silent desktop to offer; a scheduled recovery that will not start cannot latch the heartbeat. */
@RunWith(RobolectricTestRunner::class)
class DeskAskTest {
    private fun gate(now: Long = 0) = DeskAskGate(now)

    @Test
    fun `a responder asks after the silence, never when ready or negotiating, never as the initiator`() {
        val s = DeskAskGate.SILENCE_MS
        assertFalse("not yet silent", gate().tryAsk(true, false, false, s - 1))
        assertTrue(gate().tryAsk(true, false, false, s))
        assertFalse("feature-ready", gate().tryAsk(true, true, false, s))
        assertFalse("a negotiation is in flight", gate().tryAsk(true, false, true, s))
        assertFalse("the initiator offers by itself", gate().tryAsk(false, false, false, s))
    }

    /** One ask per gap, doubling while asks do not end in a transport (30, 60, 120, 240, 300 s), reset by ready, a new link, a moved network. */
    @Test
    fun `the gap doubles to five minutes and resets`() {
        val g = gate()
        var now = DeskAskGate.SILENCE_MS
        val gaps = mutableListOf<Long>()
        var last = -1L
        repeat(6) {
            // Wait until it asks again.
            while (!g.tryAsk(true, false, false, now)) now += 1_000
            if (last >= 0) gaps += now - last
            last = now
        }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L), gaps)
        assertEquals("never past five minutes", 300_000L, g.gapMs())
        g.reset(now)
        assertEquals("starts over", 30_000L, g.gapMs())
        assertFalse("and the silence counts from the reset", g.tryAsk(true, false, false, now + DeskAskGate.SILENCE_MS - 1))
        assertTrue(g.tryAsk(true, false, false, now + DeskAskGate.SILENCE_MS))
    }

    /** Anything signed from the desktop restarts the silence (its own offers are not silence). */
    @Test
    fun `a message from the desktop restarts the silence`() {
        val g = gate()
        g.heard(10_000)
        assertFalse(g.tryAsk(true, false, false, 10_000 + DeskAskGate.SILENCE_MS - 1))
        assertTrue(g.tryAsk(true, false, false, 10_000 + DeskAskGate.SILENCE_MS))
        // Asked: the next waits the gap even though the desktop stays silent.
        assertFalse(g.tryAsk(true, false, false, 10_000 + DeskAskGate.SILENCE_MS + 29_999))
    }

    /** The early-return branch of scheduleRecovery's pending task: both flags used to stay set with no attempt. */
    @Test
    fun `an abandoned recovery releases the flags so the heartbeat can start again`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val device = mockk<Device>(relaxed = true)
        every { device.deviceId } returns "desk-qa417"
        val sessions = DeviceManager()
        sessions.register("desk-qa417", mockk<SessionTransport>(relaxed = true), PairingState.PAIRED)
        val coordinator = WebRtcSessionCoordinator(app, device, sessions, terminal = mockk<TerminalClient>(relaxed = true), onStateChanged = { _, _ -> })
        val flag = WebRtcSessionCoordinator::class.java.getDeclaredField("recoveryInProgress").apply { isAccessible = true }
        flag.setBoolean(coordinator, true)
        assertTrue("latched: a recovery is 'in flight' with no attempt", negotiationInFlight(flag.getBoolean(coordinator), false, 0))
        coordinator.abandonPendingRecovery()
        assertFalse(flag.getBoolean(coordinator))
        verify { device.setWebRtcRecoveryPending(false) }
        assertFalse("the heartbeat may start a negotiation again", negotiationInFlight(flag.getBoolean(coordinator), false, 0))
    }
}
