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
    private val threadName: String,
    private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val executor: ExecutorService = org.desklink.mobile.helpers.Guarded.Pool(1, 1, 0, java.util.concurrent.LinkedBlockingQueue(), threadName)

    fun execute(action: () -> Unit) {
        executor.execute {
            try {
                action()
            } catch (error: Exception) {
                onFailure(error)
            } catch (error: Throwable) {
                if (org.desklink.mobile.helpers.Guarded.fatal(error)) throw error
                org.desklink.mobile.helpers.Guarded.record(threadName, error)
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
    private val maxQueuedEnvelopes: Int = MAX_QUEUED_ENVELOPES,
    private val maxQueuedBytes: Long = MAX_QUEUED_BYTES,
) : WebRtcTransport.Observer {
    private val queuedEnvelopes = java.util.concurrent.atomic.AtomicInteger()
    private val queuedBytes = java.util.concurrent.atomic.AtomicLong()
    private val overflowed = java.util.concurrent.atomic.AtomicBoolean()

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

    /**
     * The queue behind [events] holds what the handlers have not got to yet. A peer that sends faster than they work (or one
     * that wedges a handler) used to grow it until the process ran out of memory. Past the bound nothing is dropped and carried
     * on as if it were not: the connection is failed, which rebuilds it, and the next feature-ready resyncs every snapshot
     * (`desklink.sync.have`). What is already queued is still worked off; envelopes that arrive meanwhile belong to a connection
     * that is being replaced.
     */
    override fun onEnvelope(envelope: WebRtcEnvelope) {
        if (overflowed.get()) return
        val size = envelope.payloadBase64.length.toLong()
        if (queuedEnvelopes.get() >= maxQueuedEnvelopes || queuedBytes.get() + size > maxQueuedBytes) {
            overflow()
            return
        }
        queuedEnvelopes.incrementAndGet()
        queuedBytes.addAndGet(size)
        post {
            try {
                target.onEnvelope(envelope)
            } finally {
                queuedEnvelopes.decrementAndGet()
                queuedBytes.addAndGet(-size)
            }
        }
    }

    private fun overflow() {
        if (!overflowed.compareAndSet(false, true)) return
        val error = IllegalStateException("The peer sent more than could be handled (${queuedEnvelopes.get()} messages waiting); restarting the connection")
        android.util.Log.e("OffThreadObserver", error.message ?: "inbound backlog")
        // Not through [events]: that queue is the one that is full.
        runCatching { Thread({ runCatching { target.onFailure(error) } }, "DeskLink-WebRTC-Backlog").start() }
    }

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

    companion object {
        /** Messages waiting for the handlers; a burst of a few hundred is ordinary, this many is a stuck or hostile peer. */
        const val MAX_QUEUED_ENVELOPES = 5000
        const val MAX_QUEUED_BYTES = 24L * 1024 * 1024
    }
}
