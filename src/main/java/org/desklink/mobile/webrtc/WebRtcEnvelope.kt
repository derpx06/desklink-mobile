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
    fun validate(expectedDeviceId: String, expectedSessionId: Long, expectedGeneration: Long): ByteArray {
        require(protocolVersion == VERSION) { "Unsupported DeskLink WebRTC envelope version" }
        require(deviceId == expectedDeviceId) { "WebRTC envelope device binding mismatch" }
        require(sessionId == expectedSessionId && connectionGeneration == expectedGeneration) {
            "WebRTC envelope generation is stale"
        }
        require(messageId.isNotEmpty() && messageType.isNotEmpty()) { "Malformed WebRTC envelope" }
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
