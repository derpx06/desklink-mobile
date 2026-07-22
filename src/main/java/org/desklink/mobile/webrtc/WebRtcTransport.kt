/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.desklink.mobile.transport.DisconnectReason
import org.desklink.mobile.transport.LogicalChannel
import org.desklink.mobile.transport.SessionTransport
import org.desklink.mobile.transport.TransportCallback
import org.desklink.mobile.transport.TransportError
import org.desklink.mobile.transport.TransportErrorCode
import org.desklink.mobile.transport.TransportState
import org.desklink.mobile.transport.TransportType
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** One authenticated WebRTC peer connection for one DeskLink device session. */
class WebRtcTransport(
    private val factory: PeerConnectionFactory,
    override val transportId: String,
    private val deviceId: String,
    private val sessionId: Long,
    private val generation: Long,
    iceServers: List<PeerConnection.IceServer> = emptyList(),
    private val createLocalChannels: Boolean = false,
    private val observer: Observer,
) : SessionTransport {
    interface Observer {
        fun onSignalingNeeded(type: SignalingMessageType, payload: JSONObject)
        fun onEnvelope(envelope: WebRtcEnvelope)
        fun onFailure(error: Throwable)
    }

    private val stateRef = AtomicReference(TransportState.CONNECTING)
    private val channels = ConcurrentHashMap<String, DataChannel>()
    private val peer: PeerConnection

    init {
        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peer = factory.createPeerConnection(configuration, PeerObserver())
            ?: error("Unable to create DeskLink WebRTC peer connection")
        if (createLocalChannels) {
            WebRtcChannel.entries.forEach { channel ->
                val init = DataChannel.Init().apply {
                    ordered = channel.ordered
                    maxRetransmits = channel.maxRetransmits ?: -1
                }
                peer.createDataChannel(channel.label, init)?.also { installChannel(it) }
            }
        }
    }

    override val transportType: TransportType = TransportType.WEBRTC
    override val state: TransportState get() = stateRef.get()

    private fun installChannel(channel: DataChannel) {
        require(WebRtcChannel.fromLabel(channel.label()) != null) {
            "Unknown DeskLink WebRTC data channel: ${channel.label()}"
        }
        channels[channel.label()] = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) stateRef.compareAndSet(TransportState.CONNECTING, TransportState.CONNECTED)
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                runCatching {
                    val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                    WebRtcEnvelope.fromJson(String(bytes, Charsets.UTF_8))
                }.onSuccess { envelope ->
                    require(envelope.channel == channel.label()) {
                        "WebRTC envelope channel does not match its data channel"
                    }
                    envelope.validate(deviceId, sessionId, generation)
                    observer.onEnvelope(envelope)
                }.onFailure(observer::onFailure)
            }
        })
    }

    override fun send(channel: LogicalChannel, payload: ByteArray, callback: TransportCallback) {
        val webRtcChannel = when (channel) {
            LogicalChannel.CONTROL -> WebRtcChannel.CONTROL
            LogicalChannel.PAYLOAD -> WebRtcChannel.FILE_DATA
            LogicalChannel.STREAM -> WebRtcChannel.EVENTS
        }
        val dataChannel = channels[webRtcChannel.label]
        if (dataChannel == null || dataChannel.state() != DataChannel.State.OPEN) {
            callback.onFailure(TransportError(TransportErrorCode.CLOSED, "WebRTC data channel is not open"))
            return
        }
        if (payload.size > WebRtcEnvelope.MAX_PAYLOAD_BYTES) {
            callback.onFailure(TransportError(TransportErrorCode.INVALID_PACKET, "WebRTC message is too large"))
            return
        }
        if (!dataChannel.send(DataChannel.Buffer(ByteBuffer.wrap(payload), false))) {
            callback.onFailure(TransportError(TransportErrorCode.SEND_FAILED, "WebRTC data channel rejected message"))
            return
        }
        callback.onSuccess()
    }

    fun sendEnvelope(
        channel: WebRtcChannel,
        messageType: String,
        payload: ByteArray,
        timestamp: Long,
        callback: TransportCallback,
    ) {
        val logicalChannel = when (channel) {
            WebRtcChannel.CONTROL -> LogicalChannel.CONTROL
            WebRtcChannel.FILE_DATA -> LogicalChannel.PAYLOAD
            else -> LogicalChannel.STREAM
        }
        send(
            logicalChannel,
            WebRtcEnvelope.create(
                deviceId,
                sessionId,
                generation,
                channel,
                messageType,
                payload,
                timestamp,
            ).toJson().toString().toByteArray(Charsets.UTF_8),
            callback,
        )
    }

    fun createOffer() {
        peer.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription) {
                peer.setLocalDescription(SdpObserverAdapter(), description)
                observer.onSignalingNeeded(SignalingMessageType.OFFER, JSONObject().put("sdp", description.description))
            }
        }, MediaConstraints())
    }

    fun setRemoteDescription(type: SessionDescription.Type, sdp: String) {
        peer.setRemoteDescription(SdpObserverAdapter(), SessionDescription(type, sdp))
    }

    fun addIceCandidate(candidate: IceCandidate) { peer.addIceCandidate(candidate) }

    override fun close(reason: DisconnectReason) {
        if (stateRef.getAndSet(TransportState.CLOSING) == TransportState.CLOSED) return
        channels.values.forEach(DataChannel::dispose)
        channels.clear()
        peer.close()
        peer.dispose()
        stateRef.set(TransportState.CLOSED)
    }

    private inner class PeerObserver : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> stateRef.set(TransportState.CONNECTED)
                PeerConnection.IceConnectionState.DISCONNECTED -> stateRef.set(TransportState.FAILED)
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            observer.onSignalingNeeded(SignalingMessageType.ICE_CANDIDATE, JSONObject()
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                .put("candidate", candidate.sdp))
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) = installChannel(dataChannel)
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) = Unit
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (newState == PeerConnection.PeerConnectionState.FAILED) observer.onFailure(IllegalStateException("WebRTC peer connection failed"))
        }
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }
}
