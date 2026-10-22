/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.heartbeatPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LivenessChannelTest {
    private val phone = WebRtcWireBinding("phone", "desktop", 9, 2)
    private val desk = WebRtcWireBinding("desktop", "phone", 9, 2)

    @Test
    fun theChannelIsOrderedAndFullyReliableWithTheContractedLabel() {
        val liveness = WebRtcChannel.LIVENESS
        assertEquals("desklink-liveness-v1", liveness.label)
        assertTrue(liveness.ordered)
        assertEquals(null, liveness.maxRetransmits)
        assertEquals(liveness, WebRtcChannel.validate("desklink-liveness-v1", true, null))
    }

    @Test
    fun onlyHeartbeatsUseIt() {
        for (reply in listOf(false, true)) for (interactive in listOf(false, true)) {
            assertEquals(WebRtcChannel.LIVENESS, WebRtcPacketBridge.channelFor(heartbeatPacket(reply, interactive)))
        }
        listOf(DeskLinkProtocol.PACKET_TYPE_PING, DeskLinkProtocol.PACKET_TYPE_BATTERY, DeskLinkProtocol.PACKET_TYPE_CLIPBOARD).forEach {
            assertFalse(it, WebRtcPacketBridge.channelFor(NetworkPacket(it)) == WebRtcChannel.LIVENESS)
        }
    }

    @Test
    fun aHeartbeatAndADozeReportFitTheLivenessLimitAndRoundTrip() {
        for (packet in listOf(heartbeatPacket(false, true), heartbeatPacket(true, false))) {
            val envelope = WebRtcPacketBridge.encode(phone, packet, 1_700_000_000_000)
            assertEquals(WebRtcChannel.LIVENESS.label, envelope.channel)
            val size = envelope.toJson().toString().toByteArray(Charsets.UTF_8).size
            assertTrue("$size bytes", size <= WebRtcChannel.MAX_LIVENESS_ENVELOPE_BYTES)
            val back = WebRtcPacketBridge.decode(desk, envelope)
            assertEquals(packet.getBoolean("dozing", false), back.getBoolean("dozing", false))
            assertEquals(packet.getBoolean("reply", false), back.getBoolean("reply", false))
        }
    }

    @Test
    fun aMixedBuildsHeartbeatOnEventsStillCountsButNothingElseMayUseLiveness() {
        val events = WebRtcPacketBridge.encode(phone, heartbeatPacket(false, true), 1, WebRtcChannel.EVENTS)
        assertEquals(DeskLinkProtocol.PACKET_TYPE_HEARTBEAT, WebRtcPacketBridge.decode(desk, events).type)
        val ping = WebRtcPacketBridge.encode(phone, NetworkPacket(DeskLinkProtocol.PACKET_TYPE_PING), 1, WebRtcChannel.LIVENESS)
        try {
            WebRtcPacketBridge.decode(desk, ping)
            fail("a ping on the liveness channel must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun theLimitsAreAsContracted() {
        assertEquals(2048, WebRtcChannel.MAX_LIVENESS_ENVELOPE_BYTES)
        assertEquals(8192L, WebRtcChannel.LIVENESS_MAX_BUFFERED_BYTES)
    }
}
