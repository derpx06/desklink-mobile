/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.VideoTrack
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OffThreadObserverTest {
    private class Recorder(
        val entered: CountDownLatch = CountDownLatch(1),
        val hold: CountDownLatch? = null,
    ) : WebRtcTransport.Observer {
        val threads = CopyOnWriteArrayList<Thread>()
        val states = CopyOnWriteArrayList<WebRtcPeerHealth>()

        override fun onConnectionStateChanged(state: WebRtcPeerHealth) {
            threads += Thread.currentThread()
            states += state
            entered.countDown()
            hold?.await()
        }

        override fun onSignalingNeeded(type: SignalingMessageType, payload: JSONObject) = Unit
        override fun onEnvelope(envelope: WebRtcEnvelope) = Unit
        override fun onControlChannelOpen() = Unit
        override fun onRemoteVideoTrack(track: VideoTrack) = Unit
        override fun onScreenCaptureStopped(reason: String) = Unit
        override fun onFailure(error: Throwable) = Unit
    }

    /**
     * libwebrtc runs every observer callback on one signaling thread shared by
     * all peers in the process. A callback that waits on the coordinator lock
     * while another thread holds that lock across `createDataChannel` (which
     * itself waits for the signaling thread) is a deadlock that wedged the LAN
     * receive thread on the first desktop restart.
     */
    @Test
    fun `a blocked callback never holds the calling libwebrtc thread`() {
        val release = CountDownLatch(1)
        val target = Recorder(hold = release)
        val events = DeferredTaskExecutor("test-observer-events")
        val libwebrtc = Executors.newSingleThreadExecutor()
        try {
            val observer = OffThreadObserver(target, events)
            val returned = libwebrtc.submit { observer.onConnectionStateChanged(WebRtcPeerHealth.FAILED) }

            returned.get(1, TimeUnit.SECONDS)
            assertTrue("callback should still run", target.entered.await(1, TimeUnit.SECONDS))
            assertNotEquals(
                "callback must not run on the calling thread",
                Thread.currentThread(),
                target.threads.single(),
            )
        } finally {
            release.countDown()
            events.close()
            libwebrtc.shutdownNow()
        }
    }

    @Test
    fun `callbacks keep their order`() {
        val target = Recorder(entered = CountDownLatch(3))
        val events = DeferredTaskExecutor("test-observer-order")
        val observer = OffThreadObserver(target, events)

        observer.onConnectionStateChanged(WebRtcPeerHealth.CONNECTED)
        observer.onConnectionStateChanged(WebRtcPeerHealth.DISCONNECTED)
        observer.onConnectionStateChanged(WebRtcPeerHealth.FAILED)

        assertTrue(target.entered.await(1, TimeUnit.SECONDS))
        assertEquals(
            listOf(WebRtcPeerHealth.CONNECTED, WebRtcPeerHealth.DISCONNECTED, WebRtcPeerHealth.FAILED),
            target.states.toList(),
        )
        events.close()
    }

    /** A dying peer's last callbacks race the coordinator's shutdown. Throwing
     * from libwebrtc's native thread would take the process down. */
    @Test
    fun `a callback after close is dropped instead of thrown`() {
        val target = Recorder()
        val events = DeferredTaskExecutor("test-observer-closed")
        val observer = OffThreadObserver(target, events)
        events.close()

        observer.onConnectionStateChanged(WebRtcPeerHealth.CLOSED)

        assertTrue(target.states.isEmpty())
    }

    /** A heartbeat must be handled where it arrives, even while the event thread is stuck in a slow handler. */
    @Test
    fun `liveness is delivered at once even when the event thread is busy`() {
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val target = object : WebRtcTransport.Observer {
            override fun onSignalingNeeded(type: SignalingMessageType, payload: JSONObject) = Unit
            override fun onEnvelope(envelope: WebRtcEnvelope) = release.await().let { }
            override fun onLivenessEnvelope(envelope: WebRtcEnvelope) = delivered.countDown()
            override fun onControlChannelOpen() = Unit
            override fun onRemoteVideoTrack(track: VideoTrack) = Unit
            override fun onScreenCaptureStopped(reason: String) = Unit
            override fun onConnectionStateChanged(state: WebRtcPeerHealth) = Unit
            override fun onFailure(error: Throwable) = Unit
        }
        val events = DeferredTaskExecutor("test-observer-liveness")
        try {
            val observer = OffThreadObserver(target, events)
            val envelope = WebRtcEnvelope.create("a", 1, 1, WebRtcChannel.LIVENESS, "desklink.packet.v1", ByteArray(1), 1)
            observer.onEnvelope(envelope) // a feature message that is stuck
            observer.onLivenessEnvelope(envelope)
            assertTrue("the heartbeat waited behind a slow handler", delivered.await(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            events.close()
        }
    }
}
