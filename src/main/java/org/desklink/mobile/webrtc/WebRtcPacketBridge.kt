/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

/**
 * The sole conversion point between paired DeskLink packets and WebRTC data
 * channel envelopes. Pairing, signaling, and payload-bearing packets are
 * intentionally excluded: they have security/session semantics that cannot
 * safely be treated as an ordinary JSON message.
 */
object WebRtcPacketBridge {
    const val NETWORK_PACKET_MESSAGE_TYPE = "desklink.packet.v1"

    fun encode(wire: WebRtcWireBinding, packet: NetworkPacket, timestamp: Long, channelOverride: WebRtcChannel? = null): WebRtcEnvelope {
        ensureFeaturePacket(packet)
        val channel = channelOverride ?: channelFor(packet)
        return WebRtcEnvelope.create(
            wire.senderDeviceId,
            wire.sessionId,
            wire.generation,
            channel,
            NETWORK_PACKET_MESSAGE_TYPE,
            packet.serialize().toByteArray(Charsets.UTF_8),
            timestamp,
        )
    }

    fun decode(wire: WebRtcWireBinding, envelope: WebRtcEnvelope): NetworkPacket {
        val payload = envelope.validate(wire.peerDeviceId, wire.sessionId, wire.generation)
        require(envelope.messageType == NETWORK_PACKET_MESSAGE_TYPE) {
            "Unsupported DeskLink WebRTC message type"
        }
        val packet = NetworkPacket.unserialize(String(payload, Charsets.UTF_8))
        ensureFeaturePacket(packet)
        require(envelope.channel == channelFor(packet).label || isHeartbeatOnEvents(packet, envelope.channel)) {
            "DeskLink packet arrived on the wrong WebRTC channel"
        }
        return packet
    }

    /**
     * A heartbeat from a peer that has no liveness channel (a mixed build) still counts; senders
     * use the liveness channel whenever it is open. Nothing else may use that channel.
     */
    private fun isHeartbeatOnEvents(packet: NetworkPacket, channel: String) =
        packet.type == DeskLinkProtocol.PACKET_TYPE_HEARTBEAT && channel == WebRtcChannel.EVENTS.label

    fun channelFor(packet: NetworkPacket): WebRtcChannel = when {
        packet.type == DeskLinkProtocol.PACKET_TYPE_HEARTBEAT -> WebRtcChannel.LIVENESS
        isReplaceablePointerMotion(packet) -> WebRtcChannel.INPUT_REALTIME
        packet.type == DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST -> WebRtcChannel.INPUT_RELIABLE
        else -> WebRtcChannel.EVENTS
    }

    private fun isReplaceablePointerMotion(packet: NetworkPacket): Boolean {
        val hasMotion = listOf("dx", "dy", "x", "y").any(packet::has)
        if (!hasMotion) return false
        if (packet.type != DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST) return false
        val discrete = listOf(
            "singleclick",
            "doubleclick",
            "middleclick",
            "rightclick",
            "singlehold",
            "singlerelease",
            "scroll",
        ).any { packet.getBoolean(it, false) }
        return !discrete && !packet.has("key") && !packet.has("specialKey")
    }

    private fun ensureFeaturePacket(packet: NetworkPacket) {
        require(!packet.hasPayload()) { "Payload packet must not use the WebRTC event bridge" }
        require(packet.type !in setOf(
            NetworkPacket.PACKET_TYPE_IDENTITY,
            NetworkPacket.PACKET_TYPE_PAIR,
            DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1,
        )) { "Handshake or signaling packet must not use the WebRTC event bridge" }
    }
}
