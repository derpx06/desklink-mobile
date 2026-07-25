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
import org.desklink.mobile.plugins.screen.ScreenControlPlugin
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.session.DeviceManager
import org.desklink.mobile.session.SessionBinding
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.security.MessageDigest
import java.net.URI
import java.util.Base64
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.webrtc.PeerConnection

/**
 * Paired-session WebRTC signaling coordinator. The existing LAN TLS link is
 * retained only for discovery, pairing, identity, and signed signaling.
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
    private var activeWireBinding: WebRtcWireBinding? = null
    private var transport: WebRtcTransport? = null
    private var handover = HandoverRuntime()
    private var closed = false
    private val recovery = WebRtcRecoveryPolicy()
    private val recoveryExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "DeskLink-WebRTC-Recovery-${device.deviceId}").apply { isDaemon = true }
    }
    private val fileExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DeskLink-WebRTC-File-${device.deviceId}").apply { isDaemon = true }
    }
    private val fileTransfers = WebRtcFileTransferManager(context, device, ::sendFileMessage)
    private val fileBrowser = WebRtcPhoneFileBrowser(context, device)

    init {
        device.setWebRtcPayloadHandler { packet, callback ->
            val wire = synchronized(lock) {
                val current = transport
                require(current != null && current.isReadyForPackets()) {
                    "DeskLink WebRTC file transport is not ready"
                }
                requireNotNull(activeWireBinding) { "No active DeskLink WebRTC wire binding" }
            }
            fileTransfers.sendPayload(wire, packet, callback)
        }
    }

    private data class HandoverRuntime(
        var offerSdp: String? = null,
        var answerSdp: String? = null,
        var localNonce: String? = null,
        var remoteNonce: String? = null,
        var authenticationTimestamp: Long? = null,
        var peerAuthenticated: Boolean = false,
        var localCapabilitiesSent: Boolean = false,
        var remoteCapabilitiesReceived: Boolean = false,
        var localFeatureReadySent: Boolean = false,
        var remoteFeatureReadyReceived: Boolean = false,
    )

    fun beginIfSupported() {
        val binding = sessions.currentBinding(device.deviceId) ?: return
        if (!device.isPaired || !isEnabled() || !remoteAcceptsSignal()) return
        val localDeviceId = DeviceHelper.getDeviceId(context)
        if (localDeviceId >= device.deviceId) {
            publish("WaitingForOffer", "The paired desktop is the deterministic WebRTC initiator")
            return
        }
        synchronized(lock) {
            if (closed) return
            if (activeAttemptId != null && activeBinding?.let(sessions::isCurrent) == true) return
            startInitiatorLocked(binding)
        }
    }

    fun startPhoneScreenCapture(permissionData: android.content.Intent) {
        synchronized(lock) {
            val active = requireNotNull(transport) { "No active DeskLink WebRTC peer" }
            require(active.isReadyForPackets()) {
                "DeskLink WebRTC screen transport is not ready"
            }
            active.startScreenCapture(permissionData)
            publish("ScreenCaptureReady", "VP8")
        }
    }

    fun stopPhoneScreenCapture() {
        synchronized(lock) {
            transport?.stopScreenCapture()
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
                    activeWireBinding = WebRtcWireBinding.fromAttempt(
                        DeviceHelper.getDeviceId(context), device.deviceId, message.sessionAttemptId,
                    )
                    handover.offerSdp = sdp
                    transport = createTransport(binding, message.sessionAttemptId, createLocalChannels = false)
                    publish("CreatingAnswer", null)
                    transport!!.setRemoteDescription(SessionDescription.Type.OFFER, sdp) {
                        transport?.createAnswer()
                    }
                }
            }
            SignalingMessageType.ANSWER -> {
                val sdp = requiredString(message.payload, "sdp", MAX_SDP_BYTES)
                synchronized(lock) { handover.answerSdp = sdp }
                currentTransport(binding, message.sessionAttemptId)
                    .setRemoteDescription(SessionDescription.Type.ANSWER, sdp)
            }
            SignalingMessageType.ICE_CANDIDATE -> {
                val candidate = requiredString(message.payload, "candidate", MAX_CANDIDATE_BYTES)
                val index = message.payload.getInt("sdpMLineIndex")
                currentTransport(binding, message.sessionAttemptId)
                    .addIceCandidate(IceCandidate(message.payload.optString("sdpMid"), index, candidate))
            }
            SignalingMessageType.END_OF_CANDIDATES -> currentTransport(binding, message.sessionAttemptId)
            SignalingMessageType.ICE_RESTART -> {
                currentTransport(binding, message.sessionAttemptId)
                scheduleRecovery(
                    binding,
                    message.sessionAttemptId,
                    "The peer requested WebRTC recovery",
                    notifyPeer = false,
                )
            }
            SignalingMessageType.CLOSE -> synchronized(lock) { closeLocked() }
        }
        true
    }.getOrElse { error ->
        Log.w(TAG, "Rejected WebRTC signaling from ${device.deviceId}: ${error.message}", error)
        publish("Failed", error.message)
        true // It was a control packet, never a plugin packet.
    }

    fun close() {
        synchronized(lock) {
            closed = true
            closeLocked()
        }
        recoveryExecutor.shutdownNow()
        fileExecutor.shutdownNow()
    }

    private fun createTransport(
        binding: SessionBinding,
        attemptId: String,
        createLocalChannels: Boolean,
    ): WebRtcTransport = WebRtcTransport(
        context = context,
        factory = WebRtcRuntime.initialize(context),
        transportId = "webrtc:${device.deviceId}:${binding.sessionId}:${binding.connectionGeneration}",
        wireBinding = requireNotNull(activeWireBinding) { "WebRTC wire binding was not initialized" },
        iceServers = configuredIceServers(),
        createLocalChannels = createLocalChannels,
        observer = object : WebRtcTransport.Observer {
            override fun onSignalingNeeded(type: SignalingMessageType, payload: JSONObject) {
                when (type) {
                    SignalingMessageType.OFFER -> synchronized(lock) {
                        handover.offerSdp = payload.getString("sdp")
                    }
                    SignalingMessageType.ANSWER -> synchronized(lock) {
                        handover.answerSdp = payload.getString("sdp")
                    }
                    else -> Unit
                }
                sendSignal(binding, attemptId, type, payload)
            }

            override fun onEnvelope(envelope: WebRtcEnvelope) {
                runCatching {
                    require(sessions.isCurrent(binding)) { "Stale DeskLink session" }
                    if (
                        envelope.channel == WebRtcChannel.CONTROL.label &&
                        envelope.messageType == WebRtcHandoverControlMessage.MESSAGE_TYPE
                    ) {
                        handleHandoverMessage(binding, attemptId, envelope)
                        return@runCatching
                    }
                    require(currentTransport(binding, attemptId).handoverState().featuresAllowed) {
                        "DeskLink WebRTC feature handover is incomplete"
                    }
                    if (
                        envelope.messageType == WebRtcFileControl.CONTROL_MESSAGE_TYPE ||
                        envelope.messageType == WebRtcFileControl.CHUNK_MESSAGE_TYPE
                    ) {
                        val wire = requireNotNull(activeWireBinding) {
                            "No active DeskLink WebRTC wire binding"
                        }
                        val payload = envelope.validate(
                            wire.peerDeviceId,
                            wire.sessionId,
                            wire.generation,
                        )
                        fileExecutor.execute {
                            runCatching {
                                when (envelope.messageType) {
                                    WebRtcFileControl.CONTROL_MESSAGE_TYPE -> {
                                        require(envelope.channel == WebRtcChannel.FILE_CONTROL.label) {
                                            "WebRTC file control arrived on the wrong channel"
                                        }
                                        fileTransfers.handleControl(
                                            wire,
                                            WebRtcFileControl.fromJson(
                                                JSONObject(String(payload, Charsets.UTF_8)),
                                            ),
                                        )
                                    }
                                    WebRtcFileControl.CHUNK_MESSAGE_TYPE -> {
                                        require(envelope.channel == WebRtcChannel.FILE_DATA.label) {
                                            "WebRTC file chunk arrived on the wrong channel"
                                        }
                                        fileTransfers.handleChunk(wire, payload)
                                    }
                                }
                            }.onFailure { error ->
                                Log.w(TAG, "Rejected WebRTC file message", error)
                                publish("Failed", error.message)
                            }
                        }
                        return@runCatching
                    }
                    if (envelope.messageType == WebRtcPhoneFileBrowser.MESSAGE_TYPE) {
                        require(envelope.channel == WebRtcChannel.FILE_CONTROL.label) {
                            "Phone-file request arrived on the wrong channel"
                        }
                        val wire = requireNotNull(activeWireBinding) {
                            "No active DeskLink WebRTC wire binding"
                        }
                        val payload = envelope.validate(
                            wire.peerDeviceId,
                            wire.sessionId,
                            wire.generation,
                        )
                        fileExecutor.execute {
                            runCatching {
                                val request = JSONObject(String(payload, Charsets.UTF_8))
                                val response = fileBrowser.handle(payload)
                                sendBrowserMessage(response)
                                if (request.optString("action") == "download" &&
                                    JSONObject(String(response, Charsets.UTF_8)).optBoolean("ok")
                                ) {
                                    val packet = fileBrowser.downloadPacket(payload)
                                    fileTransfers.sendPayload(
                                        wire,
                                        packet,
                                        object : Device.SendPacketStatusCallback() {
                                            override fun onSuccess() {
                                                publish("PhoneFileDownloadComplete", packet.getString("filename"))
                                            }

                                            override fun onFailure(error: Throwable) {
                                                publish("PhoneFileDownloadFailed", error.message)
                                            }
                                        },
                                    )
                                }
                            }.onFailure { error ->
                                publish("Failed", error.message)
                            }
                        }
                        return@runCatching
                    }
                    val packet = WebRtcPacketBridge.decode(
                        requireNotNull(activeWireBinding) { "No active DeskLink WebRTC wire binding" },
                        envelope,
                    )
                    device.onWebRtcPacketReceived(packet)
                }.onFailure { error ->
                    Log.w(TAG, "Rejected WebRTC feature packet", error)
                    publish("Failed", error.message)
                }
            }

            override fun onControlChannelOpen() {
                if (sessions.isCurrent(binding)) {
                    beginHandover(binding, attemptId)
                }
            }

            override fun onRemoteVideoTrack(track: org.webrtc.VideoTrack) {
                device.getPlugin(ScreenControlPlugin::class.java)?.onRemoteVideoTrack(track)
                publish("ScreenTrackReady", track.id())
            }

            override fun onConnectionStateChanged(state: WebRtcPeerHealth) {
                when (state) {
                    WebRtcPeerHealth.CONNECTED -> publish("Connected", null)
                    WebRtcPeerHealth.DISCONNECTED,
                    WebRtcPeerHealth.FAILED -> scheduleRecovery(
                        binding,
                        attemptId,
                        "WebRTC peer connection ${state.name.lowercase()}",
                        notifyPeer = true,
                    )
                    WebRtcPeerHealth.CLOSED -> {
                        device.getPlugin(ScreenControlPlugin::class.java)?.clearRemoteVideoTrack()
                    }
                }
            }

            override fun onFailure(error: Throwable) {
                Log.w(TAG, "WebRTC peer connection failed", error)
                publish("Failed", error.message)
            }
        },
    )

    private fun beginHandover(binding: SessionBinding, attemptId: String) = synchronized(lock) {
        require(sessions.isCurrent(binding)) { "Stale DeskLink session" }
        val localDeviceId = DeviceHelper.getDeviceId(context)
        publish("Authenticating", null)
        if (localDeviceId >= device.deviceId) return@synchronized
        val nonce = UUID.randomUUID().toString()
        handover.localNonce = nonce
        sendControl(
            binding,
            attemptId,
            controlMessage(
                WebRtcHandoverControlKind.HELLO,
                attemptId,
                nonce = nonce,
            ),
        )
    }

    private fun handleHandoverMessage(
        binding: SessionBinding,
        attemptId: String,
        envelope: WebRtcEnvelope,
    ) = synchronized(lock) {
        val wire = requireNotNull(activeWireBinding) { "No active DeskLink WebRTC wire binding" }
        val payload = envelope.validate(wire.peerDeviceId, wire.sessionId, wire.generation)
        val message = WebRtcHandoverControlMessage.fromJson(
            JSONObject(String(payload, Charsets.UTF_8)),
        )
        message.validate(wire, attemptId, System.currentTimeMillis())
        val localDeviceId = DeviceHelper.getDeviceId(context)
        val localIsInitiator = localDeviceId < device.deviceId
        val activeTransport = currentTransport(binding, attemptId)

        when (message.kind) {
            WebRtcHandoverControlKind.HELLO -> {
                require(!localIsInitiator) { "WebRTC initiator received an unexpected hello" }
                val initiatorNonce = message.nonce.required("hello nonce")
                val responderNonce = UUID.randomUUID().toString()
                val timestamp = System.currentTimeMillis()
                handover.remoteNonce = initiatorNonce
                handover.localNonce = responderNonce
                handover.authenticationTimestamp = timestamp
                val signature = buildAuthenticationTranscript(attemptId, wire, timestamp)
                    .sign(RsaHelper.getPrivateKey(context))
                sendControl(
                    binding,
                    attemptId,
                    controlMessage(
                        WebRtcHandoverControlKind.CHALLENGE,
                        attemptId,
                        nonce = responderNonce,
                        peerNonce = initiatorNonce,
                        timestamp = timestamp,
                        signatureBase64 = Base64.getEncoder().encodeToString(signature),
                    ),
                )
            }
            WebRtcHandoverControlKind.CHALLENGE -> {
                require(localIsInitiator) { "WebRTC responder received an unexpected challenge" }
                val responderNonce = message.nonce.required("challenge nonce")
                val initiatorNonce = message.peerNonce.required("peer nonce")
                require(handover.localNonce == initiatorNonce) {
                    "WebRTC challenge nonce does not match the initiator"
                }
                handover.remoteNonce = responderNonce
                handover.authenticationTimestamp = message.timestamp
                val transcript = buildAuthenticationTranscript(attemptId, wire, message.timestamp)
                require(transcript.verify(device.certificate.publicKey, message.signature())) {
                    "Invalid paired identity signature on WebRTC challenge"
                }
                handover.peerAuthenticated = true
                sendControl(
                    binding,
                    attemptId,
                    controlMessage(
                        WebRtcHandoverControlKind.RESPONSE,
                        attemptId,
                        nonce = initiatorNonce,
                        peerNonce = responderNonce,
                        timestamp = message.timestamp,
                        signatureBase64 = Base64.getEncoder().encodeToString(
                            transcript.sign(RsaHelper.getPrivateKey(context)),
                        ),
                    ),
                )
            }
            WebRtcHandoverControlKind.RESPONSE -> {
                require(!localIsInitiator) { "WebRTC initiator received an unexpected response" }
                val initiatorNonce = message.nonce.required("response nonce")
                val responderNonce = message.peerNonce.required("peer nonce")
                require(
                    handover.remoteNonce == initiatorNonce &&
                        handover.localNonce == responderNonce &&
                        handover.authenticationTimestamp == message.timestamp
                ) { "WebRTC response does not match the active challenge" }
                val transcript = buildAuthenticationTranscript(attemptId, wire, message.timestamp)
                require(transcript.verify(device.certificate.publicKey, message.signature())) {
                    "Invalid paired identity signature on WebRTC response"
                }
                handover.peerAuthenticated = true
                activeTransport.advanceHandover(WebRtcHandoverMessage.AUTHENTICATED)
                sendControl(
                    binding,
                    attemptId,
                    controlMessage(WebRtcHandoverControlKind.AUTHENTICATED, attemptId),
                )
                sendCapabilities(binding, attemptId)
            }
            WebRtcHandoverControlKind.AUTHENTICATED -> {
                require(localIsInitiator && handover.peerAuthenticated) {
                    "WebRTC authentication acknowledgement arrived before peer verification"
                }
                activeTransport.advanceHandover(WebRtcHandoverMessage.AUTHENTICATED)
                sendCapabilities(binding, attemptId)
            }
            WebRtcHandoverControlKind.CAPABILITIES -> {
                verifyRemoteCapabilities(message)
                require(!handover.remoteCapabilitiesReceived) {
                    "Duplicate WebRTC capability confirmation"
                }
                handover.remoteCapabilitiesReceived = true
                activeTransport.advanceHandover(WebRtcHandoverMessage.CAPABILITIES)
                sendFeatureReady(binding, attemptId)
            }
            WebRtcHandoverControlKind.FEATURE_READY -> {
                handover.remoteFeatureReadyReceived = true
                if (handover.localFeatureReadySent && handover.remoteFeatureReadyReceived) {
                    activeTransport.advanceHandover(WebRtcHandoverMessage.FEATURE_READY)
                    device.setWebRtcTransport(activeTransport)
                    val wire = requireNotNull(activeWireBinding)
                    fileExecutor.execute {
                        runCatching { fileTransfers.resumeSends(wire) }
                            .onFailure { publish("Failed", it.message) }
                    }
                    recovery.reset()
                    publish("FeatureReady", "LAN is now bootstrap and signaling only")
                }
            }
            WebRtcHandoverControlKind.DEGRADED -> {
                activeTransport.advanceHandover(WebRtcHandoverMessage.DEGRADED)
                device.setWebRtcTransport(null)
                publish("Degraded", "WebRTC recovery is required")
            }
            WebRtcHandoverControlKind.CLOSE -> closeLocked()
        }
    }

    private fun sendCapabilities(binding: SessionBinding, attemptId: String) {
        if (handover.localCapabilitiesSent) return
        val local = DeviceHelper.getDeviceInfo(context)
        sendControl(
            binding,
            attemptId,
            controlMessage(
                WebRtcHandoverControlKind.CAPABILITIES,
                attemptId,
                incomingCapabilities = featureCapabilities(local.incomingCapabilities),
                outgoingCapabilities = featureCapabilities(local.outgoingCapabilities),
            ),
        )
        handover.localCapabilitiesSent = true
    }

    private fun sendFeatureReady(binding: SessionBinding, attemptId: String) {
        sendControl(
            binding,
            attemptId,
            controlMessage(WebRtcHandoverControlKind.FEATURE_READY, attemptId),
        )
        handover.localFeatureReadySent = true
    }

    private fun sendControl(
        binding: SessionBinding,
        attemptId: String,
        message: WebRtcHandoverControlMessage,
    ) {
        currentTransport(binding, attemptId).sendEnvelope(
            WebRtcChannel.CONTROL,
            WebRtcHandoverControlMessage.MESSAGE_TYPE,
            message.toJson().toString().toByteArray(Charsets.UTF_8),
            System.currentTimeMillis(),
            object : org.desklink.mobile.transport.TransportCallback {
                override fun onSuccess() = Unit
                override fun onFailure(error: org.desklink.mobile.transport.TransportError) {
                    publish("Failed", "Could not send WebRTC handover message: ${error.message}")
                }
            },
        )
    }

    private fun sendFileMessage(message: OutboundWebRtcFileMessage) {
        val activeTransport = synchronized(lock) {
            val value = requireNotNull(transport) { "No active WebRTC peer connection" }
            require(value.isReadyForPackets()) { "WebRTC file transport is not ready" }
            value
        }
        val (channel, messageType, payload) = when (message) {
            is OutboundWebRtcFileMessage.Control -> Triple(
                WebRtcChannel.FILE_CONTROL,
                WebRtcFileControl.CONTROL_MESSAGE_TYPE,
                message.value.toJson().toString().toByteArray(Charsets.UTF_8),
            )
            is OutboundWebRtcFileMessage.Chunk -> Triple(
                WebRtcChannel.FILE_DATA,
                WebRtcFileControl.CHUNK_MESSAGE_TYPE,
                message.value,
            )
        }
        val completion = CompletableFuture<Unit>()
        activeTransport.sendEnvelope(
            channel,
            messageType,
            payload,
            System.currentTimeMillis(),
            object : org.desklink.mobile.transport.TransportCallback {
                override fun onSuccess() {
                    completion.complete(Unit)
                }

                override fun onFailure(error: org.desklink.mobile.transport.TransportError) {
                    completion.completeExceptionally(error)
                }
            },
        )
        completion.get(10, TimeUnit.SECONDS)
    }

    private fun sendBrowserMessage(payload: ByteArray) {
        val activeTransport = synchronized(lock) {
            requireNotNull(transport) { "No active WebRTC peer connection" }
                .also { require(it.isReadyForPackets()) { "WebRTC phone-file transport is not ready" } }
        }
        val completion = CompletableFuture<Unit>()
        activeTransport.sendEnvelope(
            WebRtcChannel.FILE_CONTROL,
            WebRtcPhoneFileBrowser.MESSAGE_TYPE,
            payload,
            System.currentTimeMillis(),
            object : org.desklink.mobile.transport.TransportCallback {
                override fun onSuccess() = completion.complete(Unit).let { Unit }
                override fun onFailure(error: org.desklink.mobile.transport.TransportError) {
                    completion.completeExceptionally(error)
                }
            },
        )
        completion.get(10, TimeUnit.SECONDS)
    }

    private fun controlMessage(
        kind: WebRtcHandoverControlKind,
        attemptId: String,
        nonce: String? = null,
        peerNonce: String? = null,
        timestamp: Long = System.currentTimeMillis(),
        signatureBase64: String? = null,
        incomingCapabilities: List<String> = emptyList(),
        outgoingCapabilities: List<String> = emptyList(),
    ): WebRtcHandoverControlMessage {
        val wire = requireNotNull(activeWireBinding)
        return WebRtcHandoverControlMessage(
            handoverVersion = WebRtcHandoverControlMessage.VERSION,
            kind = kind,
            sessionAttemptId = attemptId,
            deviceId = DeviceHelper.getDeviceId(context),
            sessionId = wire.sessionId,
            connectionGeneration = wire.generation,
            nonce = nonce,
            peerNonce = peerNonce,
            timestamp = timestamp,
            signatureBase64 = signatureBase64,
            incomingCapabilities = incomingCapabilities,
            outgoingCapabilities = outgoingCapabilities,
        )
    }

    private fun buildAuthenticationTranscript(
        attemptId: String,
        wire: WebRtcWireBinding,
        timestamp: Long,
    ): WebRtcAuthenticationTranscript {
        val offer = requireNotNull(handover.offerSdp) { "WebRTC authentication is missing the offer SDP" }
        val answer = requireNotNull(handover.answerSdp) { "WebRTC authentication is missing the answer SDP" }
        val localNonce = requireNotNull(handover.localNonce) { "WebRTC authentication is missing the local nonce" }
        val remoteNonce = requireNotNull(handover.remoteNonce) { "WebRTC authentication is missing the peer nonce" }
        val localDeviceId = DeviceHelper.getDeviceId(context)
        val localIsInitiator = localDeviceId < device.deviceId
        return WebRtcAuthenticationTranscript(
            sessionAttemptId = attemptId,
            initiatorDeviceId = if (localIsInitiator) localDeviceId else device.deviceId,
            responderDeviceId = if (localIsInitiator) device.deviceId else localDeviceId,
            sessionId = wire.sessionId,
            connectionGeneration = wire.generation,
            initiatorNonce = if (localIsInitiator) localNonce else remoteNonce,
            responderNonce = if (localIsInitiator) remoteNonce else localNonce,
            offerSha256 = sha256Hex(offer),
            answerSha256 = sha256Hex(answer),
            initiatorDtlsFingerprint = dtlsFingerprint(offer),
            responderDtlsFingerprint = dtlsFingerprint(answer),
            protocolVersion = 1,
            timestamp = timestamp,
        )
    }

    private fun verifyRemoteCapabilities(message: WebRtcHandoverControlMessage) {
        val expectedIncoming = featureCapabilities(device.deviceInfo.incomingCapabilities)
        val expectedOutgoing = featureCapabilities(device.deviceInfo.outgoingCapabilities)
        require(message.incomingCapabilities.distinct().sorted() == expectedIncoming) {
            "WebRTC incoming capabilities do not match the authenticated device identity"
        }
        require(message.outgoingCapabilities.distinct().sorted() == expectedOutgoing) {
            "WebRTC outgoing capabilities do not match the authenticated device identity"
        }
    }

    private fun featureCapabilities(capabilities: Set<String>?): List<String> = capabilities
        .orEmpty()
        .filterNot { it == DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1 }
        .distinct()
        .sorted()

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun dtlsFingerprint(sdp: String): String = sdp.lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("a=fingerprint:sha-256 ") }
        ?.removePrefix("a=fingerprint:sha-256 ")
        ?.takeIf(String::isNotEmpty)
        ?: error("WebRTC SDP has no SHA-256 DTLS fingerprint")

    private fun String?.required(label: String): String =
        requireNotNull(this?.takeIf { it.isNotEmpty() && it.length <= 256 }) {
            "WebRTC handover has no valid $label"
        }

    private fun WebRtcHandoverControlMessage.signature(): ByteArray {
        val encoded = requireNotNull(signatureBase64) { "WebRTC authentication signature is missing" }
        require(encoded.length <= 16 * 1024) { "WebRTC authentication signature is too large" }
        return Base64.getDecoder().decode(encoded)
    }

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

    private fun startInitiatorLocked(binding: SessionBinding) {
        require(!closed) { "DeskLink WebRTC coordinator is closed" }
        val attemptId = UUID.randomUUID().toString()
        activeAttemptId = attemptId
        activeBinding = binding
        activeWireBinding = WebRtcWireBinding.fromAttempt(
            DeviceHelper.getDeviceId(context),
            device.deviceId,
            attemptId,
        )
        handover = HandoverRuntime()
        transport = createTransport(binding, attemptId, createLocalChannels = true)
        publish("CreatingOffer", null)
        transport?.createOffer()
    }

    private fun scheduleRecovery(
        binding: SessionBinding,
        attemptId: String,
        reason: String,
        notifyPeer: Boolean,
    ) {
        val delay = synchronized(lock) {
            if (closed || !sessions.isCurrent(binding) || activeAttemptId != attemptId) return
            val claimed = recovery.claimDelayMillis() ?: return
            device.setWebRtcTransport(null)
            publish("Degraded", reason)
            claimed
        }
        recoveryExecutor.execute {
            if (notifyPeer) {
                runCatching {
                    sendSignal(binding, attemptId, SignalingMessageType.ICE_RESTART, JSONObject())
                }.onFailure { publish("Failed", "Could not request WebRTC recovery: ${it.message}") }
            }
            synchronized(lock) {
                if (activeAttemptId == attemptId) closeLocked()
            }
            if (DeviceHelper.getDeviceId(context) >= device.deviceId) {
                publish("WaitingForRecoveryOffer", null)
                return@execute
            }
            recoveryExecutor.schedule({
                val current = sessions.currentBinding(device.deviceId)
                recovery.release()
                if (current == null || !sessions.isCurrent(current)) return@schedule
                synchronized(lock) {
                    if (closed || activeAttemptId != null) return@synchronized
                    runCatching { startInitiatorLocked(current) }
                        .onFailure { error ->
                            publish("Failed", error.message)
                            scheduleRecovery(current, attemptId, error.message ?: "WebRTC recovery failed", false)
                        }
                }
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun configuredIceServers(): List<PeerConnection.IceServer> {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val stun = parseServerList(
            preferences.getString(DeviceHelper.KEY_WEBRTC_STUN_SERVERS_PREFERENCE, "").orEmpty(),
            setOf("stun", "stuns"),
        )
        val turn = parseServerList(
            preferences.getString(DeviceHelper.KEY_WEBRTC_TURN_SERVERS_PREFERENCE, "").orEmpty(),
            setOf("turn", "turns"),
        )
        return (stun + turn).map { raw ->
            val uri = URI(raw)
            val host = requireNotNull(uri.host) { "ICE server URI has no host: $raw" }
            val endpoint = buildString {
                append(uri.scheme).append(':').append(host)
                if (uri.port >= 0) append(':').append(uri.port)
                if (!uri.query.isNullOrEmpty()) append('?').append(uri.query)
            }
            PeerConnection.IceServer.builder(endpoint).apply {
                uri.userInfo?.split(':', limit = 2)?.let { credentials ->
                    setUsername(credentials.first())
                    if (credentials.size == 2) setPassword(credentials[1])
                }
            }.createIceServer()
        }
    }

    private fun parseServerList(value: String, schemes: Set<String>): List<String> = value
        .split(',', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .also { require(it.size <= 8) { "At most eight ICE servers may be configured" } }
        .onEach { raw ->
            val uri = URI(raw)
            require(uri.scheme in schemes && !uri.host.isNullOrEmpty()) {
                "Unsupported ICE server URI: $raw"
            }
        }

    private fun currentTransport(binding: SessionBinding, attemptId: String): WebRtcTransport = synchronized(lock) {
        require(sessions.isCurrent(binding)) { "Stale DeskLink session" }
        require(activeAttemptId == attemptId && activeBinding?.let(sessions::isCurrent) == true) {
            "Stale WebRTC negotiation"
        }
        requireNotNull(transport) { "No active WebRTC peer connection" }
    }

    private fun closeLocked() {
        fileTransfers.close("WebRTC session closed")
        device.getPlugin(ScreenControlPlugin::class.java)?.clearRemoteVideoTrack()
        transport?.close(org.desklink.mobile.transport.DisconnectReason.REPLACED)
        transport = null
        activeAttemptId = null
        activeBinding = null
        activeWireBinding = null
        handover = HandoverRuntime()
        device.setWebRtcTransport(null)
    }

    private fun acceptRequestId(requestId: String) = synchronized(seenRequestIds) {
        require(seenRequestIds.add(requestId)) { "Replayed WebRTC signaling request" }
        while (seenRequestIds.size > MAX_SEEN_REQUESTS) {
            seenRequestIds.iterator().run { next(); remove() }
        }
    }

    private fun isEnabled(): Boolean = PreferenceManager
        .getDefaultSharedPreferences(context)
        .getBoolean(DeviceHelper.KEY_WEBRTC_ENABLED_PREFERENCE, true)

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
