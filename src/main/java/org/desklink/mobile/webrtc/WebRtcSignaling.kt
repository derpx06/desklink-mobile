/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.LinkedHashSet
import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

enum class SignalingMessageType {
    OFFER,
    ANSWER,
    ICE_CANDIDATE,
    END_OF_CANDIDATES,
    ICE_RESTART,
    RESTART_REQUEST,
    CLOSE,
}

data class WebRtcSignalingMessage(
    val signalingVersion: Int,
    val requestId: String,
    val sessionAttemptId: String,
    val fromDeviceId: String,
    val toDeviceId: String,
    val timestamp: Long,
    val messageType: SignalingMessageType,
    val payload: JSONObject,
    val signature: String,
) {
    fun validateFor(localDeviceId: String, now: Long) {
        require(signalingVersion == 1) { "Unsupported WebRTC signaling version" }
        require(toDeviceId == localDeviceId) { "WebRTC signaling destination mismatch" }
        require(fromDeviceId.isNotEmpty() && fromDeviceId != localDeviceId) {
            "Malformed WebRTC signaling sender"
        }
        require(requestId.isNotEmpty() && sessionAttemptId.isNotEmpty() && signature.isNotEmpty()) {
            "Malformed WebRTC signaling message"
        }
        require(kotlin.math.abs(now - timestamp) <= 5 * 60 * 1000) { "Expired WebRTC signaling message" }
    }

    /**
     * Cross-platform signature record. Do not replace this with JSONObject
     * serialization: Android and Rust are free to store object keys in
     * different orders, while the paired identity signature must be stable.
     */
    fun canonicalBytes(): ByteArray {
        val values = listOf(
            signalingVersion.toString(),
            requestId,
            sessionAttemptId,
            fromDeviceId,
            toDeviceId,
            timestamp.toString(),
            messageType.name.lowercase(),
            canonicalPayload(),
        )
        return buildString {
            values.forEach { value -> append(value.toByteArray(StandardCharsets.UTF_8).size).append(':').append(value) }
        }.toByteArray(StandardCharsets.UTF_8)
    }

    fun sign(privateKey: PrivateKey): WebRtcSignalingMessage {
        val signature = signatureFor(privateKey.algorithm).run {
            initSign(privateKey)
            update(canonicalBytes())
            Base64.encodeToString(sign(), Base64.NO_WRAP)
        }
        return copy(signature = signature)
    }

    fun verify(publicKey: PublicKey): Boolean = runCatching {
        signatureFor(publicKey.algorithm).run {
            initVerify(publicKey)
            update(canonicalBytes())
            verify(Base64.decode(signature, Base64.NO_WRAP))
        }
    }.getOrDefault(false)

    fun toJson(): JSONObject = JSONObject()
        .put("signalingVersion", signalingVersion)
        .put("requestId", requestId)
        .put("sessionAttemptId", sessionAttemptId)
        .put("fromDeviceId", fromDeviceId)
        .put("toDeviceId", toDeviceId)
        .put("timestamp", timestamp)
        .put("messageType", messageType.name.lowercase())
        .put("payload", payload)
        .put("signature", signature)

    fun toNetworkPacket(): NetworkPacket = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1).also {
        it["signalingVersion"] = signalingVersion
        it["requestId"] = requestId
        it["sessionAttemptId"] = sessionAttemptId
        it["fromDeviceId"] = fromDeviceId
        it["toDeviceId"] = toDeviceId
        it["timestamp"] = timestamp
        it["messageType"] = messageType.name.lowercase()
        it["payload"] = payload
        it["signature"] = signature
    }

    private fun canonicalPayload(): String = when (messageType) {
        SignalingMessageType.OFFER, SignalingMessageType.ANSWER ->
            "sdp=${payload.getString("sdp")}"
        SignalingMessageType.ICE_CANDIDATE ->
            "sdpMLineIndex=${payload.getInt("sdpMLineIndex")}\ncandidate=${payload.getString("candidate")}"
        SignalingMessageType.END_OF_CANDIDATES,
        SignalingMessageType.ICE_RESTART,
        SignalingMessageType.RESTART_REQUEST,
        SignalingMessageType.CLOSE -> ""
    }

    private fun signatureFor(keyAlgorithm: String): Signature = Signature.getInstance(
        if (keyAlgorithm.equals("RSA", ignoreCase = true)) "SHA256withRSA" else "SHA256withECDSA",
    )

    companion object {
        fun fromJson(json: String): WebRtcSignalingMessage {
            val value = JSONObject(json)
            return WebRtcSignalingMessage(
                value.getInt("signalingVersion"),
                value.getString("requestId"),
                value.getString("sessionAttemptId"),
                value.getString("fromDeviceId"),
                value.getString("toDeviceId"),
                value.getLong("timestamp"),
                SignalingMessageType.valueOf(value.getString("messageType").uppercase()),
                value.getJSONObject("payload"),
                value.getString("signature"),
            )
        }

        fun fromNetworkPacket(packet: NetworkPacket): WebRtcSignalingMessage {
            require(packet.type == DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1) {
                "Wrong DeskLink WebRTC signaling packet type"
            }
            return WebRtcSignalingMessage(
                packet.getInt("signalingVersion"),
                packet.getString("requestId"),
                packet.getString("sessionAttemptId"),
                packet.getString("fromDeviceId"),
                packet.getString("toDeviceId"),
                packet.getLong("timestamp"),
                SignalingMessageType.valueOf(packet.getString("messageType").uppercase()),
                requireNotNull(packet.getJSONObject("payload")) { "Missing WebRTC signaling payload" },
                packet.getString("signature"),
            )
        }
    }
}

/** Optional cloud signaling client. Empty endpoint means disabled. */
class CloudWebSocketSignaling(
    private val endpoint: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val listener: Listener,
    private val localDeviceId: String? = null,
) {
    interface Listener {
        fun onOpen()
        fun onMessage(message: WebRtcSignalingMessage)
        fun onFailure(error: Throwable)
        fun onClosed()
    }

    private var socket: WebSocket? = null
    private val seenRequestIds = LinkedHashSet<String>()

    fun connect() {
        require(endpoint.startsWith("wss://")) { "WebRTC signaling endpoint must use wss://" }
        socket = client.newWebSocket(Request.Builder().url(endpoint).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { WebRtcSignalingMessage.fromJson(text) }
                    .onSuccess { message ->
                        localDeviceId?.let { message.validateFor(it, System.currentTimeMillis()) }
                        synchronized(seenRequestIds) {
                            require(seenRequestIds.add(message.requestId)) { "Replayed WebRTC signaling request" }
                            while (seenRequestIds.size > 4096) {
                                seenRequestIds.iterator().run { next(); remove() }
                            }
                        }
                        listener.onMessage(message)
                    }
                    .onFailure(listener::onFailure)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure(t)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed()
        })
    }

    fun send(message: WebRtcSignalingMessage): Boolean = socket?.send(message.toJson().toString()) == true

    fun close() {
        socket?.close(1000, "DeskLink session closed")
        socket = null
    }
}
