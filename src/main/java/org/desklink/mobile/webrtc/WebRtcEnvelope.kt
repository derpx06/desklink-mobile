/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.json.JSONObject
import java.util.Base64
import java.util.UUID

data class WebRtcEnvelope(
    val protocolVersion: Int,
    val messageId: String,
    val deviceId: String,
    val sessionId: Long,
    val connectionGeneration: Long,
    val channel: String,
    val messageType: String,
    val timestamp: Long,
    val flags: Int,
    val payloadLength: Int,
    val payloadBase64: String,
) {
    /**
     * Checks everything except the payload itself.
     *
     * Split out so a caller that only needs to know the envelope belongs to
     * this session — the replay check, which runs on every message — does not
     * pay for a base64 decode of up to [MAX_PAYLOAD_BYTES] that the branch
     * handling the message is about to perform anyway.
     */
    fun requireBoundTo(expectedDeviceId: String, expectedSessionId: Long, expectedGeneration: Long) {
        require(protocolVersion == VERSION) { "Unsupported DeskLink WebRTC envelope version" }
        require(deviceId == expectedDeviceId) { "WebRTC envelope device binding mismatch" }
        require(sessionId == expectedSessionId && connectionGeneration == expectedGeneration) {
            "WebRTC envelope generation is stale"
        }
        require(messageId.isNotEmpty() && messageType.isNotEmpty()) { "Malformed WebRTC envelope" }
    }

    fun validate(expectedDeviceId: String, expectedSessionId: Long, expectedGeneration: Long): ByteArray {
        requireBoundTo(expectedDeviceId, expectedSessionId, expectedGeneration)
        val bytes = Base64.getDecoder().decode(payloadBase64)
        require(bytes.size == payloadLength) { "WebRTC envelope payload length mismatch" }
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "WebRTC envelope payload is too large" }
        WebRtcChannel.fromLabel(channel) ?: error("Unknown DeskLink WebRTC channel: $channel")
        return bytes
    }

    fun toJson(): JSONObject = JSONObject()
        .put("protocolVersion", protocolVersion)
        .put("messageId", messageId)
        .put("deviceId", deviceId)
        .put("sessionId", sessionId)
        .put("connectionGeneration", connectionGeneration)
        .put("channel", channel)
        .put("messageType", messageType)
        .put("timestamp", timestamp)
        .put("flags", flags)
        .put("payloadLength", payloadLength)
        .put("payloadBase64", payloadBase64)

    companion object {
        const val VERSION = 1
        const val MAX_PAYLOAD_BYTES = 128 * 1024
        const val MAX_ENVELOPE_BYTES = 256 * 1024

        /**
         * The largest message ever handed to a data channel. The desk's webrtcbin puts no
         * `a=max-message-size` in its SDP, so the peer assumes the SCTP default of 64 KiB; a larger
         * message puts the SCTP association into an error state, which closes every channel and
         * drops the whole link (and, re-sent after the rebuild, loops forever). Bigger sends fail
         * here with an ordinary error instead. File chunks are sized to fit (`MAX_CHUNK_BYTES`).
         */
        const val MAX_WIRE_MESSAGE_BYTES = 64 * 1024

        /**
         * What a feature packer may put in one packet (its serialized JSON): base64 makes it 4/3 as
         * large and the envelope adds a few hundred bytes, so 40 KiB becomes about 55 KiB on the wire,
         * under [MAX_WIRE_MESSAGE_BYTES].
         */
        const val FEATURE_PACKET_BUDGET = 40 * 1024

        /** Why a wire message of [size] bytes may not be sent, or null when it may. */
        fun wireRefusal(size: Int): String? =
            if (size > MAX_WIRE_MESSAGE_BYTES) "WebRTC message of $size bytes is over the $MAX_WIRE_MESSAGE_BYTES-byte data-channel limit" else null

        fun create(
            deviceId: String,
            sessionId: Long,
            generation: Long,
            channel: WebRtcChannel,
            messageType: String,
            payload: ByteArray,
            timestamp: Long,
        ): WebRtcEnvelope {
            require(payload.size <= MAX_PAYLOAD_BYTES) { "WebRTC envelope payload is too large" }
            return WebRtcEnvelope(
                VERSION,
                UUID.randomUUID().toString(),
                deviceId,
                sessionId,
                generation,
                channel.label,
                messageType,
                timestamp,
                0,
                payload.size,
                Base64.getEncoder().encodeToString(payload),
            )
        }

        fun fromJson(json: String): WebRtcEnvelope {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_ENVELOPE_BYTES) {
                "WebRTC envelope is too large"
            }
            val objectValue = JSONObject(json)
            return WebRtcEnvelope(
                objectValue.getInt("protocolVersion"),
                objectValue.getString("messageId"),
                objectValue.getString("deviceId"),
                objectValue.getLong("sessionId"),
                objectValue.getLong("connectionGeneration"),
                objectValue.getString("channel"),
                objectValue.getString("messageType"),
                objectValue.getLong("timestamp"),
                objectValue.getInt("flags"),
                objectValue.getInt("payloadLength"),
                objectValue.getString("payloadBase64"),
            )
        }
    }
}
