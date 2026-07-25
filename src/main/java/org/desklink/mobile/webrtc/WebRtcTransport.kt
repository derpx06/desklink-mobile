/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.desklink.mobile.transport.DisconnectReason
import org.desklink.mobile.transport.LogicalChannel
import org.desklink.mobile.transport.SessionTransport
import org.desklink.mobile.transport.TransportCallback
import org.desklink.mobile.transport.TransportError
import org.desklink.mobile.transport.TransportErrorCode
import org.desklink.mobile.transport.TransportState
import org.desklink.mobile.transport.TransportType
import org.desklink.mobile.NetworkPacket
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

enum class WebRtcPeerHealth { CONNECTED, DISCONNECTED, FAILED, CLOSED }

/** One authenticated WebRTC peer connection for one DeskLink device session. */
class WebRtcTransport(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    override val transportId: String,
    private val wireBinding: WebRtcWireBinding,
    iceServers: List<PeerConnection.IceServer> = emptyList(),
    private val createLocalChannels: Boolean = false,
    private val observer: Observer,
) : SessionTransport {
    interface Observer {
        fun onSignalingNeeded(type: SignalingMessageType, payload: JSONObject)
        fun onEnvelope(envelope: WebRtcEnvelope)
        fun onControlChannelOpen()
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onConnectionStateChanged(state: WebRtcPeerHealth)
        fun onFailure(error: Throwable)
    }

    private val stateRef = AtomicReference(TransportState.CONNECTING)
    private val handoverRef = AtomicReference(WebRtcHandoverState.NEGOTIATING)
    private val channels = ConcurrentHashMap<String, DataChannel>()
    private val peer: PeerConnection
    private val videoSource: VideoSource = factory.createVideoSource(true).also {
        it.setIsScreencast(true)
    }
    private val videoTrack: VideoTrack = factory.createVideoTrack("desklink-screen", videoSource)
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var screenTextureHelper: SurfaceTextureHelper? = null

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
        peer.addTrack(videoTrack, listOf("desklink-screen"))
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
                if (channel.state() == DataChannel.State.OPEN) {
                    stateRef.compareAndSet(TransportState.CONNECTING, TransportState.CONNECTED)
                    if (channel.label() == WebRtcChannel.CONTROL.label) observer.onControlChannelOpen()
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                runCatching {
                    val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                    WebRtcEnvelope.fromJson(String(bytes, Charsets.UTF_8))
                }.onSuccess { envelope ->
                    require(envelope.channel == channel.label()) {
                        "WebRTC envelope channel does not match its data channel"
                    }
                    envelope.validate(
                        wireBinding.peerDeviceId,
                        wireBinding.sessionId,
                        wireBinding.generation,
                    )
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
        sendEnvelope(webRtcChannel, "desklink.raw.v1", payload, System.currentTimeMillis(), callback)
    }

    override fun sendRealtime(channel: LogicalChannel, payload: ByteArray, callback: TransportCallback) {
        sendOn(WebRtcChannel.INPUT_REALTIME, payload, callback)
    }

    private fun sendOn(webRtcChannel: WebRtcChannel, payload: ByteArray, callback: TransportCallback) {
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
        val encoded = WebRtcEnvelope.create(
            wireBinding.senderDeviceId,
            wireBinding.sessionId,
            wireBinding.generation,
            channel,
            messageType,
            payload,
            timestamp,
        ).toJson().toString().toByteArray(Charsets.UTF_8)
        sendOn(channel, encoded, callback)
    }

    fun isReadyForPackets(): Boolean =
        state == TransportState.CONNECTED &&
            handoverRef.get().featuresAllowed &&
            channels[WebRtcChannel.CONTROL.label]?.state() == DataChannel.State.OPEN

    fun advanceHandover(message: WebRtcHandoverMessage): WebRtcHandoverState {
        while (true) {
            val current = handoverRef.get()
            val next = current.receive(message)
            require(next != WebRtcHandoverState.FAILED || message == WebRtcHandoverMessage.CLOSE) {
                "Invalid DeskLink WebRTC handover transition: $current -> $message"
            }
            if (handoverRef.compareAndSet(current, next)) return next
        }
    }

    fun handoverState(): WebRtcHandoverState = handoverRef.get()

    fun sendPacket(packet: NetworkPacket, callback: TransportCallback) {
        if (!handoverRef.get().featuresAllowed) {
            callback.onFailure(
                TransportError(
                    TransportErrorCode.CLOSED,
                    "DeskLink WebRTC feature handover is incomplete",
                ),
            )
            return
        }
        val envelope = try {
            WebRtcPacketBridge.encode(wireBinding, packet, System.currentTimeMillis())
        } catch (error: Throwable) {
            callback.onFailure(TransportError(TransportErrorCode.INVALID_PACKET, error.message ?: "Invalid WebRTC packet", error))
            return
        }
        sendOn(
            WebRtcChannel.fromLabel(envelope.channel) ?: error("Unknown DeskLink WebRTC channel"),
            envelope.toJson().toString().toByteArray(Charsets.UTF_8),
            callback,
        )
    }

    fun createOffer() {
        peer.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription) {
                peer.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        observer.onSignalingNeeded(
                            SignalingMessageType.OFFER,
                            JSONObject().put("sdp", description.description),
                        )
                    }

                    override fun onSetFailure(error: String) = observer.onFailure(
                        IllegalStateException("Could not set local WebRTC offer: $error"),
                    )
                }, description)
            }
            override fun onCreateFailure(error: String) = observer.onFailure(
                IllegalStateException("Could not create WebRTC offer: $error"),
            )
        }, MediaConstraints())
    }

    fun createAnswer() {
        peer.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription) {
                peer.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        observer.onSignalingNeeded(
                            SignalingMessageType.ANSWER,
                            JSONObject().put("sdp", description.description),
                        )
                    }

                    override fun onSetFailure(error: String) = observer.onFailure(
                        IllegalStateException("Could not set local WebRTC answer: $error"),
                    )
                }, description)
            }
            override fun onCreateFailure(error: String) = observer.onFailure(
                IllegalStateException("Could not create WebRTC answer: $error"),
            )
        }, MediaConstraints())
    }

    fun setRemoteDescription(
        type: SessionDescription.Type,
        sdp: String,
        onSuccess: () -> Unit = {},
    ) {
        peer.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() = onSuccess()
            override fun onSetFailure(error: String) = observer.onFailure(
                IllegalStateException("Could not set remote WebRTC description: $error"),
            )
        }, SessionDescription(type, sdp))
    }

    fun addIceCandidate(candidate: IceCandidate) { peer.addIceCandidate(candidate) }

    fun restartIce() = peer.restartIce()

    fun startScreenCapture(
        permissionData: Intent,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 12,
    ) {
        stopScreenCapture()
        val capturer = ScreenCapturerAndroid(
            permissionData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    observer.onFailure(IllegalStateException("Android screen capture permission was revoked"))
                }
            },
        )
        val helper = SurfaceTextureHelper.create(
            "DeskLink-screen-capture",
            WebRtcRuntime.eglContext(context),
        )
        capturer.initialize(helper, context, videoSource.capturerObserver)
        capturer.startCapture(
            width.coerceIn(320, 1920),
            height.coerceIn(240, 1920),
            fps.coerceIn(1, 30),
        )
        screenCapturer = capturer
        screenTextureHelper = helper
    }

    fun stopScreenCapture() {
        screenCapturer?.let { capturer ->
            runCatching { capturer.stopCapture() }
            capturer.dispose()
        }
        screenCapturer = null
        screenTextureHelper?.dispose()
        screenTextureHelper = null
    }

    override fun close(reason: DisconnectReason) {
        val previous = stateRef.getAndSet(TransportState.CLOSING)
        if (previous == TransportState.CLOSED || previous == TransportState.CLOSING) return
        stopScreenCapture()
        videoTrack.dispose()
        videoSource.dispose()
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
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    stateRef.set(TransportState.CONNECTED)
                    observer.onConnectionStateChanged(WebRtcPeerHealth.CONNECTED)
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    stateRef.set(TransportState.FAILED)
                    observer.onConnectionStateChanged(WebRtcPeerHealth.DISCONNECTED)
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    stateRef.set(TransportState.FAILED)
                    observer.onConnectionStateChanged(WebRtcPeerHealth.FAILED)
                }
                PeerConnection.IceConnectionState.CLOSED ->
                    observer.onConnectionStateChanged(WebRtcPeerHealth.CLOSED)
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                observer.onSignalingNeeded(SignalingMessageType.END_OF_CANDIDATES, JSONObject())
            }
        }
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
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) {
            (receiver.track() as? VideoTrack)?.let(observer::onRemoteVideoTrack)
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (newState == PeerConnection.PeerConnectionState.FAILED) {
                stateRef.set(TransportState.FAILED)
                observer.onConnectionStateChanged(WebRtcPeerHealth.FAILED)
            }
        }
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }
}
