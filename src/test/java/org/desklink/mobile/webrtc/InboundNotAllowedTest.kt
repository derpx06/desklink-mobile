/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.session.DeviceManager
import org.desklink.mobile.webrtc.terminal.TerminalClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Input the phone does not accept (no lease, glide off) is dropped; it never fails the peer. */
@RunWith(RobolectricTestRunner::class)
class InboundNotAllowedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun mousepad(i: Int) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST).apply {
        this["dx"] = 1.0
        this["dy"] = 0.0
        this["remoteSessionId"] = "r"
        this["leaseId"] = "l"
        this["inputSequence"] = i.toLong()
    }

    @Test
    fun twoHundredMousepadPacketsWithNoLeaseAndGlideOffKeepThePeerUp() {
        val states = mutableListOf<String>()
        val device = mockk<Device>(relaxed = true)
        every { device.deviceId } returns "desk-1"
        val coordinator = WebRtcSessionCoordinator(
            app, device, DeviceManager(), terminal = mockk<TerminalClient>(relaxed = true),
            onStateChanged = { state, _ -> states += state },
        )
        val wire = WebRtcWireBinding("phone", "desk-1", 1, 1)
        repeat(200) { coordinator.deliverFeaturePacket(mousepad(it), wire) }
        assertFalse("no Failed: $states", states.contains("Failed"))
        assertEquals(emptyList<String>(), states)
        verify(exactly = 0) { device.onWebRtcPacketReceived(any()) }
    }

    @Test
    fun aPacketThatIsAllowedStillReachesItsPlugin() {
        val device = mockk<Device>(relaxed = true)
        every { device.deviceId } returns "desk-1"
        val coordinator = WebRtcSessionCoordinator(app, device, DeviceManager(), terminal = mockk<TerminalClient>(relaxed = true), onStateChanged = { _, _ -> })
        val wire = WebRtcWireBinding("phone", "desk-1", 1, 1)
        coordinator.deliverFeaturePacket(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_PING), wire)
        verify(exactly = 1) { device.onWebRtcPacketReceived(any()) }
    }

    @Test
    fun aPluginThatThrowsDoesNotFailThePeer() {
        val states = mutableListOf<String>()
        val device = mockk<Device>(relaxed = true)
        every { device.deviceId } returns "desk-1"
        every { device.onWebRtcPacketReceived(any()) } throws IllegalStateException("plugin bug")
        val coordinator = WebRtcSessionCoordinator(app, device, DeviceManager(), terminal = mockk<TerminalClient>(relaxed = true), onStateChanged = { s, _ -> states += s })
        coordinator.deliverFeaturePacket(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_PING), WebRtcWireBinding("phone", "desk-1", 1, 1))
        assertTrue(states.isEmpty())
    }

    @Test
    fun theDropLogSaysOneLinePerWindowWithACount() {
        var now = 0L
        val lines = mutableListOf<String>()
        val log = DropLog("t", everyMs = 5_000, now = { now }, sink = { lines += it })
        repeat(200) { log.dropped("no active lease") }
        assertEquals(1, lines.size)
        assertTrue(lines.single(), "1 inbound" in lines.single())
        now = 5_000
        log.dropped("no active lease")
        assertEquals(2, lines.size)
        assertTrue(lines.last(), "dropped 200" in lines.last())
    }
}
