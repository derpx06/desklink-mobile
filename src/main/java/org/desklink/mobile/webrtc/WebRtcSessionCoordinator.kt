/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import android.content.Context
import android.preference.PreferenceManager
import android.util.Log
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.helpers.DeviceHelper
import org.desklink.mobile.helpers.security.RsaHelper
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.session.DeviceManager
import org.desklink.mobile.session.SessionBinding
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.LinkedHashSet
import java.util.UUID

/**
 * Paired-session WebRTC signaling coordinator. The existing LAN TLS link is
 * the signed signaling and fallback path. A completed peer connection is
 * deliberately diagnostic-only until feature transport handover is enabled.
 */
class WebRtcSessionCoordinator(
    private val context: Context,
    private val device: Device,
    private val sessions: DeviceManager,
    private val onStateChanged: (String, String?) -> Unit,
) : Device.ControlPacketHandler {
    private val lock = Any()
    private val seenRequestIds = LinkedHashSet<String>()
    private var activeAttemptId: String? = null
    private var activeBinding: SessionBinding? = null
    private var transport: WebRtcTransport? = null

    fun beginIfSupported() {
        val binding = sessions.currentBinding(device.deviceId) ?: return
        if (!device.isPaired || !isEnabled() || !remoteAcceptsSignal()) return
        val localDeviceId = DeviceHelper.getDeviceId(context)
        if (localDeviceId >= device.deviceId) {
            publish("WaitingForOffer", "The paired desktop is the deterministic WebRTC initiator")
            return
        }
        synchronized(lock) {
            if (activeAttemptId != null && activeBinding?.let(sessions::isCurrent) == true) return
            val attemptId = UUID.randomUUID().toString()
            activeAttemptId = attemptId
            activeBinding = binding
            transport = createTransport(binding, attemptId, createLocalChannels = true)
            publish("CreatingOffer", null)
            transport?.createOffer()
        }
    }

    override fun onControlPacket(packet: NetworkPacket): Boolean = runCatching {
        require(packet.type == DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1)
        val binding = sessions.currentBinding(device.deviceId)
            ?: error("No current DeskLink session for signaling")
        require(device.isPaired && isEnabled()) { "WebRTC signaling requires an enabled paired session" }
        val message = WebRtcSignalingMessage.fromNetworkPacket(packet)
        message.validateFor(DeviceHelper.getDeviceId(context), System.currentTimeMillis())
        require(message.fromDeviceId == device.deviceId) { "WebRTC signaling sender does not match paired device" }
        require(message.verify(device.certificate.publicKey)) { "Invalid paired identity signature on WebRTC signaling" }
        acceptRequestId(message.requestId)

        when (message.messageType) {
            SignalingMessageType.OFFER -> {
                require(DeviceHelper.getDeviceId(context) > device.deviceId) {
                    "Rejected unexpected WebRTC offer from non-initiator"
                }
                val sdp = requiredString(message.payload, "sdp", MAX_SDP_BYTES)
                synchronized(lock) {
                    closeLocked()
                    activeAttemptId = message.sessionAttemptId
                    activeBinding = binding
                    transport = createTransport(binding, message.sessionAttemptId, createLocalChannels = false)
                    publish("CreatingAnswer", null)
                    transport!!.setRemoteDescription(SessionDescription.Type.OFFER, sdp) {
                        transport?.createAnswer()
                    }
                }
            }
            SignalingMessageType.ANSWER -> {
                val sdp = requiredString(message.payload, "sdp", MAX_SDP_BYTES)
                currentTransport(binding, message.sessionAttemptId)
                    .setRemoteDescription(SessionDescription.Type.ANSWER, sdp)
            }
            SignalingMessageType.ICE_CANDIDATE -> {
                val candidate = requiredString(message.payload, "candidate", MAX_CANDIDATE_BYTES)
                val index = message.payload.getInt("sdpMLineIndex")
                currentTransport(binding, message.sessionAttemptId)
                    .addIceCandidate(IceCandidate(message.payload.optString("sdpMid", null), index, candidate))
            }
            SignalingMessageType.END_OF_CANDIDATES -> currentTransport(binding, message.sessionAttemptId)
            SignalingMessageType.ICE_RESTART -> error("WebRTC ICE restart is not implemented yet")
            SignalingMessageType.CLOSE -> synchronized(lock) { closeLocked() }
        }
        true
    }.getOrElse { error ->
        Log.w(TAG, "Rejected WebRTC signaling from ${device.deviceId}: ${error.message}", error)
        publish("Failed", error.message)
        true // It was a control packet, never a plugin packet.
    }

    fun close() = synchronized(lock) { closeLocked() }

    private fun createTransport(
        binding: SessionBinding,
        attemptId: String,
        createLocalChannels: Boolean,
    ): WebRtcTransport = WebRtcTransport(
        factory = WebRtcRuntime.initialize(context),
        transportId = "webrtc:${device.deviceId}:${binding.sessionId}:${binding.connectionGeneration}",
        deviceId = device.deviceId,
        sessionId = binding.sessionId,
        generation = binding.connectionGeneration,
        createLocalChannels = createLocalChannels,
        observer = object : WebRtcTransport.Observer {
            override fun onSignalingNeeded(type: SignalingMessageType, payload: JSONObject) {
                sendSignal(binding, attemptId, type, payload)
            }

            override fun onEnvelope(envelope: WebRtcEnvelope) {
                // Feature messages are not routed until the handover slice is
                // enabled. Refusing them avoids a false “WebRTC works” state.
                publish("Failed", "Received WebRTC feature data before transport handover")
            }

            override fun onControlChannelOpen() {
                if (sessions.isCurrent(binding)) {
                    publish("Ready", "Peer connection and control data channel are open; LAN remains the active feature fallback")
                }
            }

            override fun onFailure(error: Throwable) {
                Log.w(TAG, "WebRTC peer connection failed", error)
                publish("Failed", error.message)
            }
        },
    )

    private fun sendSignal(
        binding: SessionBinding,
        attemptId: String,
        type: SignalingMessageType,
        payload: JSONObject,
    ) {
        if (!sessions.isCurrent(binding)) return
        val message = WebRtcSignalingMessage(
            signalingVersion = 1,
            requestId = UUID.randomUUID().toString(),
            sessionAttemptId = attemptId,
            fromDeviceId = DeviceHelper.getDeviceId(context),
            toDeviceId = device.deviceId,
            timestamp = System.currentTimeMillis(),
            messageType = type,
            payload = payload,
            signature = "",
        ).sign(RsaHelper.getPrivateKey(context))
        device.sendPacket(message.toNetworkPacket(), object : Device.SendPacketStatusCallback() {
            override fun onSuccess() = Unit
            override fun onFailure(e: Throwable) {
                publish("Failed", "Could not send WebRTC signaling: ${e.message}")
            }
        })
    }

    private fun currentTransport(binding: SessionBinding, attemptId: String): WebRtcTransport = synchronized(lock) {
        require(sessions.isCurrent(binding)) { "Stale DeskLink session" }
        require(activeAttemptId == attemptId && activeBinding?.let(sessions::isCurrent) == true) {
            "Stale WebRTC negotiation"
        }
        requireNotNull(transport) { "No active WebRTC peer connection" }
    }

    private fun closeLocked() {
        transport?.close(org.desklink.mobile.transport.DisconnectReason.REPLACED)
        transport = null
        activeAttemptId = null
        activeBinding = null
    }

    private fun acceptRequestId(requestId: String) = synchronized(seenRequestIds) {
        require(seenRequestIds.add(requestId)) { "Replayed WebRTC signaling request" }
        while (seenRequestIds.size > MAX_SEEN_REQUESTS) {
            seenRequestIds.iterator().run { next(); remove() }
        }
    }

    private fun isEnabled(): Boolean = PreferenceManager
        .getDefaultSharedPreferences(context)
        .getBoolean(DeviceHelper.KEY_WEBRTC_ENABLED_PREFERENCE, false)

    private fun remoteAcceptsSignal(): Boolean = device.deviceInfo.incomingCapabilities
        ?.contains(DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1) == true

    private fun publish(state: String, detail: String?) {
        Log.i(TAG, "${device.deviceId}: $state${detail?.let { " ($it)" } ?: ""}")
        onStateChanged(state, detail)
    }

    private fun requiredString(payload: JSONObject, key: String, maximum: Int): String {
        val value = payload.getString(key)
        require(value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= maximum) {
            "Invalid WebRTC signaling $key"
        }
        return value
    }

    companion object {
        private const val TAG = "DeskLink/WebRTC"
        private const val MAX_SDP_BYTES = 256 * 1024
        private const val MAX_CANDIDATE_BYTES = 16 * 1024
        private const val MAX_SEEN_REQUESTS = 4096
    }
}
