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
import org.desklink.mobile.webrtc.WebRtcSessionCoordinator.Companion.DESK_ASK_MIN_GAP_MS
import org.desklink.mobile.webrtc.WebRtcSessionCoordinator.Companion.DESK_SILENCE_BEFORE_ASK_MS
import org.desklink.mobile.webrtc.WebRtcSessionCoordinator.Companion.negotiationInFlight
import org.desklink.mobile.webrtc.WebRtcSessionCoordinator.Companion.shouldAskDeskToOffer
import org.desklink.mobile.webrtc.terminal.TerminalClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** QA4-17: a responder phone with a live LAN link asks a silent desktop to offer; a scheduled recovery that will not start cannot latch the heartbeat. */
@RunWith(RobolectricTestRunner::class)
class DeskAskTest {
    private val quiet = DESK_SILENCE_BEFORE_ASK_MS
    private val gap = DESK_ASK_MIN_GAP_MS

    @Test
    fun `a responder asks after the silence, once per gap, never when ready or negotiating, never as the initiator`() {
        assertTrue(shouldAskDeskToOffer(true, false, false, quiet, gap))
        assertFalse("not yet silent", shouldAskDeskToOffer(true, false, false, quiet - 1, gap))
        assertFalse("asked a moment ago", shouldAskDeskToOffer(true, false, false, quiet, gap - 1))
        assertFalse("feature-ready", shouldAskDeskToOffer(true, true, false, quiet, gap))
        assertFalse("a negotiation is in flight", shouldAskDeskToOffer(true, false, true, quiet, gap))
        assertFalse("the initiator offers by itself", shouldAskDeskToOffer(false, false, false, quiet, gap))
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
