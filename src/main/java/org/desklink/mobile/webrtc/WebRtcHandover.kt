/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.json.JSONArray
import org.json.JSONObject

enum class WebRtcHandoverState { NEGOTIATING, AUTHENTICATED, CAPABILITIES_CONFIRMED, FEATURE_READY, FAILED }
enum class WebRtcHandoverMessage { AUTHENTICATED, CAPABILITIES, FEATURE_READY, DEGRADED, CLOSE }

fun WebRtcHandoverState.receive(message: WebRtcHandoverMessage): WebRtcHandoverState = when (this to message) {
    WebRtcHandoverState.NEGOTIATING to WebRtcHandoverMessage.AUTHENTICATED -> WebRtcHandoverState.AUTHENTICATED
    WebRtcHandoverState.AUTHENTICATED to WebRtcHandoverMessage.CAPABILITIES -> WebRtcHandoverState.CAPABILITIES_CONFIRMED
    WebRtcHandoverState.CAPABILITIES_CONFIRMED to WebRtcHandoverMessage.FEATURE_READY -> WebRtcHandoverState.FEATURE_READY
    WebRtcHandoverState.FEATURE_READY to WebRtcHandoverMessage.DEGRADED -> WebRtcHandoverState.AUTHENTICATED
    else -> WebRtcHandoverState.FAILED
}

val WebRtcHandoverState.featuresAllowed: Boolean get() = this == WebRtcHandoverState.FEATURE_READY

enum class WebRtcHandoverControlKind(val wireName: String) {
    HELLO("hello"),
    CHALLENGE("challenge"),
    RESPONSE("response"),
    AUTHENTICATED("authenticated"),
    CAPABILITIES("capabilities"),
    FEATURE_READY("feature-ready"),
    DEGRADED("degraded"),
    CLOSE("close");

    companion object {
        fun fromWireName(value: String): WebRtcHandoverControlKind =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unknown DeskLink WebRTC handover message: $value")
    }
}

data class WebRtcHandoverControlMessage(
    val handoverVersion: Int,
    val kind: WebRtcHandoverControlKind,
    val sessionAttemptId: String,
    val deviceId: String,
    val sessionId: Long,
    val connectionGeneration: Long,
    val nonce: String? = null,
    val peerNonce: String? = null,
    val timestamp: Long,
    val signatureBase64: String? = null,
    val incomingCapabilities: List<String> = emptyList(),
    val outgoingCapabilities: List<String> = emptyList(),
) {
    fun validate(wire: WebRtcWireBinding, attemptId: String, now: Long) {
        require(handoverVersion == VERSION) { "Unsupported DeskLink WebRTC handover version" }
        require(
            sessionAttemptId == attemptId &&
                deviceId == wire.peerDeviceId &&
                sessionId == wire.sessionId &&
                connectionGeneration == wire.generation
        ) { "DeskLink WebRTC handover binding mismatch" }
        require(kotlin.math.abs(now - timestamp) <= MAX_AGE_MILLIS) {
            "Expired DeskLink WebRTC handover message"
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("handoverVersion", handoverVersion)
        .put("kind", kind.wireName)
        .put("sessionAttemptId", sessionAttemptId)
        .put("deviceId", deviceId)
        .put("sessionId", sessionId)
        .put("connectionGeneration", connectionGeneration)
        .put("timestamp", timestamp)
        .put("incomingCapabilities", JSONArray(incomingCapabilities))
        .put("outgoingCapabilities", JSONArray(outgoingCapabilities))
        .also { value ->
            nonce?.let { value.put("nonce", it) }
            peerNonce?.let { value.put("peerNonce", it) }
            signatureBase64?.let { value.put("signatureBase64", it) }
        }

    companion object {
        const val VERSION = 1
        const val MESSAGE_TYPE = "desklink.handover.v1"
        private const val MAX_AGE_MILLIS = 5 * 60 * 1000L

        fun fromJson(value: JSONObject): WebRtcHandoverControlMessage =
            WebRtcHandoverControlMessage(
                handoverVersion = value.getInt("handoverVersion"),
                kind = WebRtcHandoverControlKind.fromWireName(value.getString("kind")),
                sessionAttemptId = value.getString("sessionAttemptId"),
                deviceId = value.getString("deviceId"),
                sessionId = value.getLong("sessionId"),
                connectionGeneration = value.getLong("connectionGeneration"),
                nonce = value.optString("nonce").takeIf(String::isNotEmpty),
                peerNonce = value.optString("peerNonce").takeIf(String::isNotEmpty),
                timestamp = value.getLong("timestamp"),
                signatureBase64 = value.optString("signatureBase64").takeIf(String::isNotEmpty),
                incomingCapabilities = value.optJSONArray("incomingCapabilities").toStrings(),
                outgoingCapabilities = value.optJSONArray("outgoingCapabilities").toStrings(),
            )
    }
}

private fun JSONArray?.toStrings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map(::getString)
