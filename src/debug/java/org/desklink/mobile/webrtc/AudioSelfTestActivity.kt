/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * Debug builds only: F1's negotiation checked on a device with real libwebrtc, in-process.
 * `am start -n org.desklink.mobile.debug/org.desklink.mobile.webrtc.AudioSelfTestActivity --es case new|old-answerer|old-offerer`
 * - new: two phone transports — both SDPs carry video, audio, data; Opus fmtp per A1.
 * - old-answerer: the phone offers to a peer without audio (an old build).
 * - old-offerer: an old peer without audio offers to the phone.
 * Logs `AUDIO-SELFTEST` lines; recording is turned on 8 s after connecting and off 8 s later, so
 * `dumpsys audio` can be read while idle, recording and idle again.
 */
class AudioSelfTestActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val closers = mutableListOf<() -> Unit>()

    private fun log(message: String) = Log.i(TAG, message)

    private fun sections(sdp: String) = sdp.lines().filter { it.startsWith("m=") }.joinToString(" | ") { it.substringBefore(' ') + " " + it.split(' ').getOrNull(1) } +
        " ; " + sdp.lines().filter { it.startsWith("a=fmtp:") && sdp.lines().any { l -> l.startsWith("a=rtpmap:" + it.removePrefix("a=fmtp:").substringBefore(' ') + " opus") } }.joinToString() +
        " ; directions=" + sdp.lines().filter { it in setOf("a=sendrecv", "a=recvonly", "a=sendonly", "a=inactive") }.joinToString(",")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val factory = WebRtcRuntime.initialize(this)
        when (intent.getStringExtra("case") ?: "new") {
            "old-answerer" -> phoneToOld(factory)
            "old-offerer" -> oldToPhone(factory)
            else -> phoneToPhone(factory)
        }
    }

    private inner class Sink(val name: String, val onConnected: () -> Unit) : WebRtcTransport.Observer {
        var other: ((SignalingMessageType, JSONObject) -> Unit)? = null
        override fun onSignalingNeeded(type: SignalingMessageType, payload: JSONObject) {
            if (type == SignalingMessageType.OFFER || type == SignalingMessageType.ANSWER) log("$name $type ${sections(payload.getString("sdp"))}")
            main.post { other?.invoke(type, payload) }
        }
        override fun onEnvelope(envelope: WebRtcEnvelope) = Unit
        override fun onControlChannelOpen() { log("$name control channel open"); onConnected() }
        override fun onRemoteVideoTrack(track: VideoTrack) = Unit
        override fun onScreenCaptureStopped(reason: String) = Unit
        override fun onConnectionStateChanged(state: WebRtcPeerHealth) { log("$name peer $state") }
        override fun onFailure(error: Throwable) { log("$name FAILURE ${error.message}") }
    }

    private fun transport(factory: org.webrtc.PeerConnectionFactory, name: String, offerer: Boolean, sink: Sink) = WebRtcTransport(
        this, factory, name, WebRtcWireBinding("a", "b", 1), createLocalChannels = offerer, observer = sink,
    ).also { closers += { it.close(org.desklink.mobile.transport.DisconnectReason.REPLACED) } }

    private fun deliver(target: WebRtcTransport, type: SignalingMessageType, payload: JSONObject) {
        when (type) {
            SignalingMessageType.OFFER -> target.setRemoteDescription(SessionDescription.Type.OFFER, payload.getString("sdp")) { target.createAnswer() }
            SignalingMessageType.ANSWER -> target.setRemoteDescription(SessionDescription.Type.ANSWER, payload.getString("sdp"))
            SignalingMessageType.ICE_CANDIDATE -> target.addIceCandidate(IceCandidate(payload.getString("sdpMid"), payload.getInt("sdpMLineIndex"), payload.getString("candidate")))
            else -> Unit
        }
    }

    /**
     * Idle, then the microphone on [sender] for 8 s, off, then on again (the path that aborted when
     * recording was started after only flipping the module's switch), off: read `dumpsys audio` meanwhile.
     */
    private fun cycle(sender: WebRtcTransport, receiver: WebRtcTransport?) {
        log("IDLE (no recording, no playout expected)")
        fun on(round: Int) {
            sender.restartRecording { PhoneAudioEngine.useMicrophone() }
            receiver?.setAudioPlayout(true)
            log("RECORDING ON ($round)")
        }
        fun off(round: Int) {
            sender.setAudioRecording(false)
            PhoneAudioEngine.reset()
            receiver?.setAudioPlayout(false)
            log("RECORDING OFF ($round)")
        }
        main.postDelayed({ on(1) }, 8_000)
        main.postDelayed({ off(1) }, 16_000)
        main.postDelayed({ on(2) }, 20_000)
        main.postDelayed({ off(2) }, 28_000)
        main.postDelayed({ closers.forEach { it() }; log("DONE") }, 32_000)
    }

    private fun phoneToPhone(factory: org.webrtc.PeerConnectionFactory) {
        var started = false
        lateinit var a: WebRtcTransport
        lateinit var b: WebRtcTransport
        val sa = Sink("phoneA") { if (!started) { started = true; cycle(a, b) } }
        val sb = Sink("phoneB") {}
        a = transport(factory, "a", offerer = true, sink = sa)
        b = transport(factory, "b", offerer = false, sink = sb)
        sa.other = { t, p -> deliver(b, t, p) }
        sb.other = { t, p -> deliver(a, t, p) }
        a.createOffer()
    }

    /** A plain peer like a build without F1: video + data channels, no audio. */
    private fun oldPeer(factory: org.webrtc.PeerConnectionFactory, name: String, toPhone: (SignalingMessageType, JSONObject) -> Unit): PeerConnection {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }
        lateinit var peer: PeerConnection
        peer = requireNotNull(factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) = toPhone(SignalingMessageType.ICE_CANDIDATE, JSONObject().put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex).put("candidate", candidate.sdp))
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) { log("$name peer $newState") }
            override fun onDataChannel(dataChannel: DataChannel) { log("$name got data channel ${dataChannel.label()}") }
            override fun onSignalingChange(p0: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState) = Unit
            override fun onIceConnectionReceivingChange(p0: Boolean) = Unit
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>) = Unit
            override fun onAddStream(p0: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(p0: org.webrtc.MediaStream) = Unit
            override fun onRenegotiationNeeded() = Unit
        }))
        val video = factory.createVideoTrack("old-video", factory.createVideoSource(false))
        peer.addTrack(video, listOf("old"))
        closers += { peer.dispose() }
        return peer
    }

    private open inner class Sdp(val onCreated: (SessionDescription) -> Unit = {}) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onCreated(description)
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) { log("SDP create failure $error") }
        override fun onSetFailure(error: String) { log("SDP set failure $error") }
    }

    private fun phoneToOld(factory: org.webrtc.PeerConnectionFactory) {
        var started = false
        lateinit var phone: WebRtcTransport
        lateinit var old: PeerConnection
        val sink = Sink("phone") { if (!started) { started = true; cycle(phone, null) } }
        phone = transport(factory, "phone", offerer = true, sink = sink)
        old = oldPeer(factory, "old") { t, p -> main.post { deliver(phone, t, p) } }
        sink.other = { type, payload ->
            when (type) {
                SignalingMessageType.OFFER -> old.setRemoteDescription(object : Sdp() {
                    override fun onSetSuccess() {
                        old.createAnswer(Sdp { answer ->
                            log("old ANSWER ${sections(answer.description)}")
                            old.setLocalDescription(Sdp(), answer)
                            main.post { deliver(phone, SignalingMessageType.ANSWER, JSONObject().put("sdp", answer.description)) }
                        }, MediaConstraints())
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, payload.getString("sdp")))
                SignalingMessageType.ICE_CANDIDATE -> old.addIceCandidate(IceCandidate(payload.getString("sdpMid"), payload.getInt("sdpMLineIndex"), payload.getString("candidate")))
                else -> Unit
            }
        }
        phone.createOffer()
    }

    private fun oldToPhone(factory: org.webrtc.PeerConnectionFactory) {
        var started = false
        lateinit var phone: WebRtcTransport
        lateinit var old: PeerConnection
        val sink = Sink("phone") { if (!started) { started = true; cycle(phone, null) } }
        phone = transport(factory, "phone", offerer = false, sink = sink)
        old = oldPeer(factory, "old") { t, p -> main.post { deliver(phone, t, p) } }
        WebRtcChannel.entries.forEach { channel ->
            old.createDataChannel(channel.label, DataChannel.Init().apply { ordered = channel.ordered; maxRetransmits = channel.maxRetransmits ?: -1 })
        }
        sink.other = { type, payload ->
            when (type) {
                SignalingMessageType.ANSWER -> old.setRemoteDescription(Sdp(), SessionDescription(SessionDescription.Type.ANSWER, payload.getString("sdp")))
                SignalingMessageType.ICE_CANDIDATE -> old.addIceCandidate(IceCandidate(payload.getString("sdpMid"), payload.getInt("sdpMLineIndex"), payload.getString("candidate")))
                else -> Unit
            }
        }
        old.createOffer(Sdp { offer ->
            log("old OFFER ${sections(offer.description)}")
            old.setLocalDescription(Sdp(), offer)
            main.post { deliver(phone, SignalingMessageType.OFFER, JSONObject().put("sdp", offer.description)) }
        }, MediaConstraints())
    }

    override fun onDestroy() {
        closers.forEach { runCatching { it() } }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AUDIO-SELFTEST"
    }
}
