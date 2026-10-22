/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.desklink.mobile.NetworkPacket

/** A packet's size on the data channel, through the real bridge and envelope encoding. */
fun wireSize(packet: NetworkPacket): Int =
    WebRtcPacketBridge.encode(WebRtcWireBinding("phone", "desktop", 9, 2), packet, 1_700_000_000_000)
        .toJson().toString().toByteArray(Charsets.UTF_8).size

/** Every packet fits one data-channel message. */
fun assertAllCross(packets: List<NetworkPacket>) {
    packets.forEach { p ->
        val size = wireSize(p)
        org.junit.Assert.assertTrue("${p.type}: $size bytes on the wire", size <= WebRtcEnvelope.MAX_WIRE_MESSAGE_BYTES)
    }
}
