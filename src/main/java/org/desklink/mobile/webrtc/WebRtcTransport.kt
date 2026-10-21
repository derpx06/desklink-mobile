/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
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
import org.webrtc.DeskLinkVideoEncoderFactories
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

enum class WebRtcPeerHealth { CONNECTED, DISCONNECTED, FAILED, CLOSED }

/**
 * The screen track is attached up front, in both roles, so a video m-line
 * exists from the first exchange.
 *
 * This was `false`, on the reasoning that offering an idle track made "some
 * native peers negotiate a malformed, codec-less video answer", and that screen
 * media would instead be added by "the explicit screen-session negotiation
 * path". **That path was never built**, and the flag guarded the only
 * `addTrack` in the app — so the phone captured pixels into a track attached to
 * no sender and no video could ever leave it. That is the blank mirror.
 *
 * Adding media later is not a smaller change, it is a much larger one:
 * `onRenegotiationNeeded` is a no-op, a second offer for a live attempt is
 * refused, and only the deterministic initiator may offer — so a phone that
 * wanted to start sharing had no way to ask. Negotiating an idle m-line up
 * front avoids renegotiation entirely.
 *
 * Attaching in **both** roles is deliberate. As offerer this puts video in the
 * offer; as answerer it associates the track with the transceiver `webrtcbin`
 * auto-creates from the desktop's `sendrecv` offer, which previously went
 * unanswered and left the phone `recvonly`. Since the initiator is chosen by
 * device-id comparison, fixing only one role would leave roughly half of all
 * device pairs unable to mirror.
 *
 * The original concern is real but unverified against our only peer. It is
 * tested directly against `webrtcbin` rather than assumed — see the P0 gate in
 * the mirroring plan.
 */
/** Sort key for the screen codecs: H.264 (constrained baseline, non-interleaved
 * first — what every hardware encoder and desktop decoder handles), then the
 * rest in libwebrtc's order. `sortedBy` is stable, so ties keep that order. */
internal fun h264Rank(codec: org.webrtc.RtpCapabilities.CodecCapability): Int {
    if (!codec.name.equals("H264", ignoreCase = true)) return 3
    val params = codec.parameters.orEmpty()
    val nonInterleaved = params["packetization-mode"] == "1"
    val baseline = params["profile-level-id"]?.startsWith("42e0", ignoreCase = true) == true
    return when {
        nonInterleaved && baseline -> 0
        nonInterleaved -> 1
        else -> 2
    }
}

/** Text stays sharp while the desktop only watches; motion matters once it drives the phone. */
internal fun screenDegradationPreference(controlled: Boolean): RtpParameters.DegradationPreference =
    if (controlled) RtpParameters.DegradationPreference.BALANCED
    else RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION

/** The capture long side to retry at after the encoder failed at [current]; null to give up. */
internal fun nextCaptureLongSide(current: Int): Int? = CAPTURE_LONG_SIDES.firstOrNull { it < current }

internal val CAPTURE_LONG_SIDES = listOf(1280, 960, 720)

internal object WebRtcMediaNegotiationPolicy {
    const val includeScreenTrackInInitialOffer = true
}

/** State of one local SDP offer operation. A peer must never have two offers
 * in flight: native WebRTC callbacks are asynchronous and a second offer can
 * leave the signaling state stuck without producing an error. */
internal enum class OfferOperationState {
    IDLE,
    CREATING,
    SETTING_LOCAL_DESCRIPTION,
    COMPLETED,
    FAILED,
}

/** Small, testable gate used by the native SDP callbacks. */
internal class OfferOperationGate {
    private val stateRef = AtomicReference(OfferOperationState.IDLE)

    fun begin(): Boolean = stateRef.compareAndSet(
        OfferOperationState.IDLE,
        OfferOperationState.CREATING,
    )

    fun beginSettingLocalDescription(): Boolean = stateRef.compareAndSet(
        OfferOperationState.CREATING,
        OfferOperationState.SETTING_LOCAL_DESCRIPTION,
    )

    fun complete(): Boolean = stateRef.compareAndSet(
        OfferOperationState.SETTING_LOCAL_DESCRIPTION,
        OfferOperationState.COMPLETED,
    )

    fun fail(): Boolean {
        while (true) {
            val current = stateRef.get()
            when (current) {
                OfferOperationState.CREATING,
                OfferOperationState.SETTING_LOCAL_DESCRIPTION ->
                    if (stateRef.compareAndSet(current, OfferOperationState.FAILED)) return true

                OfferOperationState.IDLE,
                OfferOperationState.COMPLETED,
                OfferOperationState.FAILED -> return false
            }
        }
    }

    fun state(): OfferOperationState = stateRef.get()
}

/** One authenticated WebRTC peer connection for one DeskLink device session. */
class WebRtcTransport(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    override val transportId: String,
    private val wireBinding: WebRtcWireBinding,
    iceServers: List<PeerConnection.IceServer> = emptyList(),
    private val createLocalChannels: Boolean = false,
    /** Debug "Force TURN": gather relay candidates only. */
    relayOnly: Boolean = false,
    private val observer: Observer,
) : SessionTransport {
    interface Observer {
        fun onSignalingNeeded(type: SignalingMessageType, payload: JSONObject)
        fun onEnvelope(envelope: WebRtcEnvelope)
        fun onControlChannelOpen()
        fun onRemoteVideoTrack(track: VideoTrack)
        /** MediaProjection revocation is a screen-session event, not a peer
         * failure. The authenticated WebRTC connection stays alive so the
         * user can explicitly resume sharing after unlock or renewed consent. */
        fun onScreenCaptureStopped(reason: String)
        fun onConnectionStateChanged(state: WebRtcPeerHealth)
        fun onFailure(error: Throwable)
    }

    private val stateRef = AtomicReference(TransportState.CONNECTING)
    private val handoverRef = AtomicReference(WebRtcHandoverState.NEGOTIATING)
    private val offerOperation = OfferOperationGate()
    private val channels = ConcurrentHashMap<String, DataChannel>()
    private val peer: PeerConnection
    private val videoSource: VideoSource = factory.createVideoSource(true).also {
        it.setIsScreencast(true)
    }
    private val videoTrack: VideoTrack = factory.createVideoTrack("desklink-screen", videoSource)
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var screenTextureHelper: SurfaceTextureHelper? = null
    private var nextScreenCaptureEpoch = 0L
    private var activeScreenCaptureEpoch: Long? = null
    private var suppressedStopEpoch: Long? = null
    @Volatile private var screenControlled = false
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureFps = 0
    /** Long side of the running capture, 0 when none; lowered on encoder failure. */
    private val captureLongSide = AtomicInteger(0)
    @Volatile private var encoderGaveUp = false
    private val encoderFailureListener =
        DeskLinkVideoEncoderFactories.FailureListener { width, height -> onEncoderFailure(width, height) }

    init {
        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Must match the desktop, which sets webrtcbin's bundle-policy to
            // max-bundle. libwebrtc defaults to BALANCED, so a data channel plus
            // a video m-line negotiated two ICE components and two DTLS
            // handshakes instead of one — slower to connect and one more thing
            // that can fail on a hostile network.
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            if (relayOnly) iceTransportsType = PeerConnection.IceTransportsType.RELAY
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
        if (org.desklink.mobile.BuildConfig.DEBUG) startInboundStatsLog()
        if (WebRtcMediaNegotiationPolicy.includeScreenTrackInInitialOffer) {
            val sender = peer.addTrack(videoTrack, listOf("desklink-screen"))
            preferHardwareH264(sender)
        }
    }

    /**
     * Puts H.264 first when this phone can **encode** it. libwebrtc offers its
     * software codecs first, so the screen went out as software VP8 even on
     * phones with a hardware H.264 encoder — and hardware VP8 is rare (a
     * Snapdragon 6-series has none). On Android 10+ libwebrtc only offers
     * H.264 for sending when `MediaCodecInfo.isHardwareAccelerated()`, so a
     * phone without one keeps its current order and stays on VP8. Receive
     * capabilities are what `setCodecPreferences` accepts; VP8 stays in the
     * list because the desktop sends VP8.
     */
    private fun preferHardwareH264(sender: org.webrtc.RtpSender?) {
        sender ?: return
        runCatching {
            val canEncodeH264 = factory
                .getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
                .codecs.any { it.name.equals("H264", ignoreCase = true) }
            if (!canEncodeH264) return
            val transceiver = peer.transceivers.firstOrNull { it.sender.id() == sender.id() } ?: return
            val codecs = factory
                .getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
                .codecs
            transceiver.setCodecPreferences(codecs.sortedBy { h264Rank(it) })
        }.onFailure { Log.w(TAG, "Could not prefer H.264 for the screen", it) }
    }

    override val transportType: TransportType = TransportType.WEBRTC
    override val state: TransportState get() = stateRef.get()

    private fun installChannel(channel: DataChannel) {
        // Refused, not thrown. This runs inside onDataChannel on libwebrtc's
        // native callback thread, where an exception takes the process down
        // rather than being reported -- so an unrecognised label from the
        // desktop must not be a `require`.
        if (WebRtcChannel.fromLabel(channel.label()) == null) {
            Log.w("DeskLink/WebRTC", "Ignoring unknown data channel: ${channel.label()}")
            runCatching { channel.close() }
            return
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
                // Everything stays inside the runCatching. `Result.onSuccess`
                // is inline and does **not** catch, so the require and the
                // validate below used to throw straight out of onMessage into
                // libwebrtc's native callback thread -- a crash, not a
                // rejection. A stale-generation envelope arriving during a
                // peer replacement was enough to trigger it, which is exactly
                // when it is most likely to happen.
                runCatching {
                    val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                    val envelope = WebRtcEnvelope.fromJson(String(bytes, Charsets.UTF_8))
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

    fun createOffer(): Boolean {
        if (!offerOperation.begin()) {
            Log.w(TAG, "Ignoring duplicate WebRTC offer request in state ${offerOperation.state()}")
            return false
        }
        Log.i(TAG, "Creating WebRTC offer")
        try {
        peer.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription) {
                if (!offerOperation.beginSettingLocalDescription()) {
                    Log.w(TAG, "Ignoring stale WebRTC offer creation callback")
                    return
                }
                Log.i(TAG, "WebRTC offer created (${description.description.length} bytes); setting local description")
                peer.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        if (!offerOperation.complete()) {
                            Log.w(TAG, "Ignoring stale WebRTC local-description callback")
                            return
                        }
                        Log.i(TAG, "WebRTC local offer description set")
                        observer.onSignalingNeeded(
                            SignalingMessageType.OFFER,
                            JSONObject().put("sdp", description.description),
                        )
                    }

                    override fun onSetFailure(error: String) {
                        if (offerOperation.fail()) {
                            Log.e(TAG, "Could not set local WebRTC offer: $error")
                            observer.onFailure(
                                IllegalStateException("Could not set local WebRTC offer: $error"),
                            )
                        }
                    }
                }, description)
            }
            override fun onCreateFailure(error: String) {
                if (offerOperation.fail()) {
                    Log.e(TAG, "Could not create WebRTC offer: $error")
                    observer.onFailure(
                        IllegalStateException("Could not create WebRTC offer: $error"),
                    )
                }
            }
        }, MediaConstraints())
        } catch (error: Throwable) {
            if (offerOperation.fail()) {
                Log.e(TAG, "Could not start WebRTC offer creation", error)
                observer.onFailure(error)
            }
            return false
        }
        return true
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

    /**
     * Rounds a capture dimension down to a multiple of 16.
     *
     * Android MediaCodec encoders want 16-pixel alignment
     * ([bugs.webrtc.org/13089]); vendor implementations variously crash, add a
     * green stripe down the edge, or silently pad. A 1080-wide phone is the
     * common case and **1080 is not a multiple of 16** — 1080/16 = 67.5. The
     * resulting 1080 → 1072 loses 0.7% of width and is invisible, which is a
     * good trade against a device-specific encoder failure.
     *
     * Applied to both axes because rotation swaps them.
     */
    private fun align16(value: Int): Int = value and 0xF.inv()

    fun startScreenCapture(
        permissionData: Intent,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 12,
    ) {
        // A replacement capture is intentional. Do not turn its old
        // MediaProjection callback into a false "screen locked" event for the
        // new remote session.
        stopScreenCapture()
        val captureEpoch = ++nextScreenCaptureEpoch
        val capturer = ScreenCapturerAndroid(
            permissionData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    onScreenCaptureStopped(captureEpoch)
                }
            },
        )
        val helper = SurfaceTextureHelper.create(
            "DeskLink-screen-capture",
            WebRtcRuntime.eglContext(context),
        )
        screenCapturer = capturer
        screenTextureHelper = helper
        activeScreenCaptureEpoch = captureEpoch
        captureWidth = width
        captureHeight = height
        captureFps = fps.coerceIn(1, 30)
        encoderGaveUp = false
        try {
            capturer.initialize(helper, context, videoSource.capturerObserver)
            val longSide = minOf(CAPTURE_LONG_SIDES.first(), maxOf(width, height, 1))
            captureLongSide.set(longSide)
            DeskLinkVideoEncoderFactories.setFailureListener(encoderFailureListener)
            val (w, h) = captureSize(longSide)
            capturer.startCapture(w, h, captureFps)
        } catch (error: Throwable) {
            releaseScreenCaptureLocked(stopCapture = true)
            throw error
        }
        configureScreenSender(captureFps)
        if (org.desklink.mobile.BuildConfig.DEBUG) startScreenStatsLog()
    }

    /**
     * Scale proportionally so the long side is at most [longSide]. Clamping
     * each side separately squashed a tall phone (2400 → 1920 high, width
     * untouched), and encoding the full panel cost twice the bandwidth and
     * encoder time for no gain on a desktop window.
     */
    private fun captureSize(longSide: Int): Pair<Int, Int> {
        val scale = minOf(1.0, longSide.toDouble() / maxOf(captureWidth, captureHeight, 1))
        return align16((captureWidth * scale).toInt().coerceAtLeast(240)) to
            align16((captureHeight * scale).toInt().coerceAtLeast(240))
    }

    /**
     * Encoder thread. A hardware encoder that fails (often a size it will not
     * take) gets the capture one step smaller (1280 → 960 → 720 long side)
     * before we give up. Failures for frames larger than the current capture
     * are stale ones from before the last step, so a burst steps only once.
     */
    private fun onEncoderFailure(width: Int, height: Int) {
        val current = captureLongSide.get()
        if (current == 0 || maxOf(width, height) > current) return
        val next = nextCaptureLongSide(current)
        if (next == null) {
            if (!encoderGaveUp) {
                encoderGaveUp = true
                Log.e(TAG, "Screen encoder failed at ${width}x$height, the smallest capture size; giving up")
            }
            return
        }
        if (!captureLongSide.compareAndSet(current, next)) return
        Log.w(TAG, "Screen encoder failed at ${width}x$height; retrying with the capture's long side at $next")
        // Off the encoder thread, and without this monitor: changeCaptureFormat
        // waits on the capture thread, which takes the monitor in onStop. A
        // capturer disposed meanwhile throws, which is caught.
        thread(name = "DeskLink-encoder-retry") {
            val capturer = synchronized(this) { screenCapturer } ?: return@thread
            if (captureLongSide.get() != next) return@thread
            val (w, h) = captureSize(next)
            runCatching { capturer.changeCaptureFormat(w, h, captureFps) }
                .onFailure { Log.w(TAG, "Could not shrink the screen capture", it) }
        }
    }

    /**
     * Whether the desktop is controlling (not just viewing) the phone's
     * screen. Switches the live sender's degradation preference.
     */
    @Synchronized
    fun setScreenControlled(controlled: Boolean) {
        if (screenControlled == controlled) return
        screenControlled = controlled
        if (activeScreenCaptureEpoch == null) return
        updateScreenSender { it.degradationPreference = screenDegradationPreference(controlled) }
        Log.i(TAG, "Screen degradation preference: ${screenDegradationPreference(controlled)}")
    }

    /**
     * Selected candidate pair, RTT and outgoing bandwidth estimate, for a
     * connection chip; cheap enough to poll every couple of seconds.
     * [bootstrapViaRelay]: this device was reached through the relay link.
     */
    fun requestConnectionStats(bootstrapViaRelay: Boolean, callback: WebRtcConnectionStatsCallback) {
        if (state == TransportState.CLOSING || state == TransportState.CLOSED) {
            callback.onStats(null)
            return
        }
        runCatching {
            peer.getStats { report -> callback.onStats(connectionStatsFrom(report.statsMap, bootstrapViaRelay)) }
        }.onFailure { callback.onStats(null) }
    }

    /**
     * The desktop's screen track on this peer, as libwebrtc announced it.
     *
     * Kept from `onAddTrack` rather than fetched with `peer.transceivers`:
     * every `getTransceivers()` call disposes the wrappers the previous call
     * returned, and disposing a `VideoTrack` wrapper detaches all its
     * renderers — a picture that decodes and is never drawn.
     */
    @Volatile
    private var announcedRemoteTrack: org.webrtc.VideoTrack? = null

    fun remoteVideoTrack(): org.webrtc.VideoTrack? = announcedRemoteTrack

    private var screenStatsTimer: java.util.Timer? = null
    private var inboundStatsTimer: java.util.Timer? = null
    private val debugFramesAtTrack = java.util.concurrent.atomic.AtomicLong()

    /** Debug builds: the desktop's screen as this phone receives it, every
     * 2 s while packets arrive — "waiting for the first picture" otherwise
     * gives no hint whether nothing arrives or nothing decodes. */
    private fun startInboundStatsLog() {
        var lastPackets = -1L
        inboundStatsTimer = java.util.Timer("DeskLink-inbound-stats", true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() {
                    runCatching {
                        peer.getStats { report ->
                            val inbound = report.statsMap.values.firstOrNull {
                                it.type == "inbound-rtp" && it.members["kind"] == "video"
                            } ?: return@getStats
                            val m = inbound.members
                            val packets = (m["packetsReceived"] as? Number)?.toLong() ?: 0L
                            if (packets == lastPackets) return@getStats
                            lastPackets = packets
                            val codec = (m["codecId"] as? String)?.let { report.statsMap[it]?.members?.get("mimeType") }
                            Log.i(
                                TAG,
                                "inbound-stats codec=$codec decoder=${m["decoderImplementation"]} " +
                                    "packets=$packets lost=${m["packetsLost"]} framesReceived=${m["framesReceived"]} " +
                                    "framesDecoded=${m["framesDecoded"]} keyFramesDecoded=${m["keyFramesDecoded"]} " +
                                    "framesDropped=${m["framesDropped"]} nack=${m["nackCount"]} pli=${m["pliCount"]} " +
                                    "size=${m["frameWidth"]}x${m["frameHeight"]} framesAtTrack=${debugFramesAtTrack.get()}",
                            )
                        }
                    }
                }
            }, 2_000, 2_000)
        }
    }

    /** Debug builds: one line every 2 s of what the screen sender is doing —
     * the only way to see why libwebrtc holds frames back on a real network. */
    private fun startScreenStatsLog() {
        screenStatsTimer?.cancel()
        screenStatsTimer = java.util.Timer("DeskLink-screen-stats", true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() {
                    runCatching {
                        peer.getStats { report ->
                            val out = report.statsMap.values.firstOrNull {
                                it.type == "outbound-rtp" && it.members["kind"] == "video"
                            }?.members ?: return@getStats
                            val pair = report.statsMap.values.firstOrNull {
                                it.type == "candidate-pair" && it.members["nominated"] == true &&
                                    it.members["state"] == "succeeded"
                            }?.members
                            val source = report.statsMap.values.firstOrNull {
                                it.type == "media-source" && it.members["kind"] == "video"
                            }?.members
                            Log.i(
                                TAG,
                                "screen-stats encoder=${out["encoderImplementation"]} " +
                                    "srcFps=${source?.get("framesPerSecond")} encFps=${out["framesPerSecond"]} " +
                                    "framesEncoded=${out["framesEncoded"]} framesSent=${out["framesSent"]} " +
                                    "target=${out["targetBitrate"]} availOut=${pair?.get("availableOutgoingBitrate")} " +
                                    "rtt=${pair?.get("currentRoundTripTime")} limit=${out["qualityLimitationReason"]} " +
                                    "nack=${out["nackCount"]} pli=${out["pliCount"]} fir=${out["firCount"]} " +
                                    "keyframes=${out["keyFramesEncoded"]} packets=${out["packetsSent"]} " +
                                    "retx=${out["retransmittedPacketsSent"]} " +
                                    "sendDelay=${out["totalPacketSendDelay"]} size=${out["frameWidth"]}x${out["frameHeight"]}",
                            )
                        }
                    }
                }
            }, 2_000, 2_000)
        }
    }

    /**
     * libwebrtc otherwise starts the screen at ~300 kbps with no ceiling and
     * takes seconds to look sharp. Its congestion control still adapts inside
     * these bounds. A viewing desktop keeps text readable (drop frames before
     * resolution); a controlling one keeps motion ([setScreenControlled]).
     */
    @Synchronized
    private fun configureScreenSender(fps: Int) {
        runCatching { peer.setBitrate(MIN_SCREEN_BITRATE, START_SCREEN_BITRATE, MAX_SCREEN_BITRATE) }
        updateScreenSender { parameters ->
            parameters.degradationPreference = screenDegradationPreference(screenControlled)
            parameters.encodings.forEach { encoding ->
                encoding.minBitrateBps = MIN_SCREEN_BITRATE
                encoding.maxBitrateBps = MAX_SCREEN_BITRATE
                encoding.maxFramerate = fps
            }
        }
    }

    private fun updateScreenSender(change: (RtpParameters) -> Unit) {
        val sender = peer.senders.firstOrNull { it.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND } ?: return
        runCatching {
            val parameters = sender.parameters
            change(parameters)
            sender.parameters = parameters
        }.onFailure { Log.w(TAG, "Could not set screen sender parameters", it) }
    }

    /**
     * Ends a locally requested capture without reporting it as a privacy
     * revocation. The MediaProjection callback may arrive later, so it is
     * bound to the exact capture epoch and cannot pause a newer capture.
     */
    @Synchronized
    fun stopScreenCapture() {
        val epoch = activeScreenCaptureEpoch ?: return
        suppressedStopEpoch = epoch
        releaseScreenCaptureLocked(stopCapture = true)
    }

    private fun onScreenCaptureStopped(captureEpoch: Long) {
        val shouldNotify = synchronized(this) {
            when {
                suppressedStopEpoch == captureEpoch -> {
                    suppressedStopEpoch = null
                    false
                }
                activeScreenCaptureEpoch != captureEpoch -> false
                else -> {
                    releaseScreenCaptureLocked(stopCapture = false)
                    true
                }
            }
        }
        if (shouldNotify) {
            observer.onScreenCaptureStopped(
                "Android screen capture permission was revoked or stopped",
            )
        }
    }

    /** Caller holds this instance monitor. */
    private fun releaseScreenCaptureLocked(stopCapture: Boolean) {
        screenStatsTimer?.cancel()
        screenStatsTimer = null
        val capturer = screenCapturer
        val helper = screenTextureHelper
        screenCapturer = null
        screenTextureHelper = null
        activeScreenCaptureEpoch = null
        captureLongSide.set(0)
        DeskLinkVideoEncoderFactories.clearFailureListener(encoderFailureListener)
        if (stopCapture) runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        helper?.dispose()
    }

    override fun close(reason: DisconnectReason) {
        val previous = stateRef.getAndSet(TransportState.CLOSING)
        if (previous == TransportState.CLOSED || previous == TransportState.CLOSING) return
        stopScreenCapture()
        inboundStatsTimer?.cancel()
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
            Log.i(TAG, "onAddTrack ${receiver.track()?.kind()} streams=${mediaStreams.size}")
            (receiver.track() as? VideoTrack)?.let(::announceRemoteTrack)
        }

        // Unified Plan's callback. The legacy onAddTrack is not reliably
        // delivered for a webrtcbin answer; without one of them the desktop's
        // picture decoded but no renderer was ever given its track.
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
            Log.i(TAG, "onTrack ${transceiver.mediaType} mid=${transceiver.mid}")
            (transceiver.receiver.track() as? VideoTrack)?.let(::announceRemoteTrack)
        }

        private fun announceRemoteTrack(track: VideoTrack) {
            if (announcedRemoteTrack?.id() == track.id()) return
            announcedRemoteTrack = track
            Log.i(TAG, "Remote video track ${track.id()} enabled=${track.enabled()} state=${track.state()}")
            if (org.desklink.mobile.BuildConfig.DEBUG) {
                runCatching { track.addSink { debugFramesAtTrack.incrementAndGet() } }
            }
            observer.onRemoteVideoTrack(track)
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (newState == PeerConnection.PeerConnectionState.FAILED) {
                stateRef.set(TransportState.FAILED)
                observer.onConnectionStateChanged(WebRtcPeerHealth.FAILED)
            }
        }
    }

    internal fun offerOperationState(): OfferOperationState = offerOperation.state()

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    private companion object {
        const val TAG = "DeskLink/WebRTC"
        const val MIN_SCREEN_BITRATE = 300_000
        const val START_SCREEN_BITRATE = 2_500_000
        const val MAX_SCREEN_BITRATE = 8_000_000
    }
}
