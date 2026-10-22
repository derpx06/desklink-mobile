/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.json.JSONObject
import org.webrtc.VideoTrack
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Runs potentially blocking work away from caller-held state locks and UI threads. */
internal class DeferredTaskExecutor(
    threadName: String,
    private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }

    fun execute(action: () -> Unit) {
        executor.execute {
            try {
                action()
            } catch (error: Exception) {
                onFailure(error)
            }
        }
    }

    override fun close() {
        // shutdown() drains already-queued work.
        executor.shutdown()
    }
}

/**
 * Hands every libwebrtc callback to [events] so libwebrtc's threads never wait
 * on coordinator state.
 *
 * The `PeerConnectionFactory` is process-wide, so all peers share one signaling
 * thread. A callback that blocks on the coordinator lock while another thread
 * holds that lock across a native call (`createDataChannel` waits for the
 * signaling thread) is a deadlock: on a desktop restart the old peer's ICE
 * failure and the replacement peer's creation wedged the LAN receive thread,
 * so no offer was ever sent.
 */
internal class OffThreadObserver(
    private val target: WebRtcTransport.Observer,
    private val events: DeferredTaskExecutor,
) : WebRtcTransport.Observer {
    private fun post(action: () -> Unit) {
        try {
            events.execute(action)
        } catch (_: RejectedExecutionException) {
            // The coordinator is closed; a dying peer's last callbacks are
            // dropped. Throwing here would unwind into libwebrtc's native thread.
        }
    }

    override fun onSignalingNeeded(type: SignalingMessageType, payload: JSONObject) =
        post { target.onSignalingNeeded(type, payload) }

    override fun onEnvelope(envelope: WebRtcEnvelope) = post { target.onEnvelope(envelope) }

    // Liveness is answered where it arrives: queued behind a slow handler, a healthy peer would look dead.
    override fun onLivenessEnvelope(envelope: WebRtcEnvelope) = target.onLivenessEnvelope(envelope)
    override fun onControlChannelOpen() = post { target.onControlChannelOpen() }
    override fun onRemoteVideoTrack(track: VideoTrack) = post { target.onRemoteVideoTrack(track) }
    override fun onScreenCaptureStopped(reason: String) = post { target.onScreenCaptureStopped(reason) }
    override fun onConnectionStateChanged(state: WebRtcPeerHealth) =
        post { target.onConnectionStateChanged(state) }

    override fun onFailure(error: Throwable) = post { target.onFailure(error) }

    // File frames are queued by the target itself (to its own file thread); a hop through the
    // event thread would put every frame behind unrelated callbacks.
    override fun onFileFrame(frame: ByteArray) = target.onFileFrame(frame)
    override fun onFileBufferedLow() = target.onFileBufferedLow()
}
