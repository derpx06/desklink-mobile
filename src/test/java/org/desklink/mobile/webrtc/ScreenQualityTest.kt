/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1c on the phone: what it asks of the desk, what it measures, how it refines. */
class ScreenQualityTest {
    @Test
    fun theDeskIsAskedForThisPanelsSizeAndRate() {
        assertEquals(2400 to 60, desktopViewRequest(2400, 120f, lan = true))
        assertEquals(2400 to 30, desktopViewRequest(2400, 120f, lan = false))
        assertEquals(1600 to 30, desktopViewRequest(1600, 50f, lan = true))
        assertEquals(2560 to 30, desktopViewRequest(3200, 60f, lan = false))
        assertEquals(640 to 30, desktopViewRequest(480, 60f, lan = false))
    }

    @Test
    fun theStatsLineGivesPerFrameTimesForTheInterval() {
        val before = mapOf("totalDecodeTime" to 1.0, "framesDecoded" to 100L, "jitterBufferDelay" to 5.0, "jitterBufferEmittedCount" to 100L)
        val after = mapOf(
            "totalDecodeTime" to 1.2, "framesDecoded" to 140L, "jitterBufferDelay" to 5.4, "jitterBufferEmittedCount" to 140L,
            "framesPerSecond" to 20.0, "jitter" to 0.004, "freezeCount" to 1L, "decoderImplementation" to "libvpx",
        )
        val line = inboundStatsLine(before, after)
        assertTrue(line, line.contains("decodeMs=5.0"))
        assertTrue(line, line.contains("jitterBufferMs=10.0"))
        assertTrue(line, line.contains("jitterMs=4.0"))
        assertTrue(line, line.contains("fps=20"))
        assertTrue(line, line.contains("freezes=1"))
        assertTrue(line, line.contains("decoder=libvpx"))
        // No frames in the interval: no division by zero.
        assertTrue(inboundStatsLine(after, after).contains("decodeMs=-"))
    }

    @Test
    fun aStillPictureIsRedeliveredSeveralTimesWhenMotionStops() {
        val pending = mutableListOf<Runnable>()
        var forced = 0
        lateinit var refinement: StaticRefinement
        refinement = StaticRefinement(
            100,
            repeats = 3,
            schedule = { task, _ -> pending += task },
            cancel = { task -> pending.remove(task) },
            force = { forced++; refinement.onFrame() }, // the capture delivers the frame again
        )
        repeat(5) { refinement.onFrame() } // motion: only the latest deadline is pending
        assertEquals(1, pending.size)
        while (pending.isNotEmpty()) pending.removeAt(0).run()
        assertEquals(3, forced)
        // New motion starts the count again.
        refinement.onFrame()
        while (pending.isNotEmpty()) pending.removeAt(0).run()
        assertEquals(6, forced)
    }

    /** libwebrtc's frame-rate limiter drops a frame whose timestamp is not new: a re-delivery gets a fresh one. */
    @Test
    fun aRedeliveredFrameReachesLibwebrtcWithANewerTimestamp() {
        val seen = mutableListOf<Long>()
        val target = object : org.webrtc.CapturerObserver {
            override fun onCapturerStarted(success: Boolean) = Unit
            override fun onCapturerStopped() = Unit
            override fun onFrameCaptured(frame: org.webrtc.VideoFrame) { seen += frame.timestampNs }
        }
        var now = 5_000L
        val observer = RefiningCapturerObserver(target, StaticRefinement(100, 0, { _, _ -> }, { }, { }), nowNs = { now })
        val buffer = object : org.webrtc.VideoFrame.Buffer {
            override fun getWidth() = 16
            override fun getHeight() = 16
            override fun toI420(): org.webrtc.VideoFrame.I420Buffer? = null
            override fun retain() = Unit
            override fun release() = Unit
            override fun cropAndScale(x: Int, y: Int, w: Int, h: Int, sw: Int, sh: Int): org.webrtc.VideoFrame.Buffer = this
        }
        observer.onFrameCaptured(org.webrtc.VideoFrame(buffer, 0, 1_000L))
        observer.onFrameCaptured(org.webrtc.VideoFrame(buffer, 0, 1_000L)) // forceFrame: same timestamp
        now = 500L // a clock behind the last stamp still moves forward
        observer.onFrameCaptured(org.webrtc.VideoFrame(buffer, 0, 1_000L))
        observer.onFrameCaptured(org.webrtc.VideoFrame(buffer, 0, 9_000L)) // a real new frame keeps its own
        assertEquals(listOf(1_000L, 5_000L, 5_001L, 9_000L), seen)
    }

    @Test
    fun theScreenGoesAtTheDisplayRateOnTheLanAndAtMost30Elsewhere() {
        assertEquals(60, phoneScreenFps(120f))
        assertEquals(60, phoneScreenFps(60f))
        assertEquals(30, phoneScreenFps(50f))
        assertEquals(60, screenMaxFramerate(60, lan = true))
        assertEquals(30, screenMaxFramerate(60, lan = false))
        assertEquals(30, screenMaxFramerate(30, lan = true))
        assertEquals(8_000_000, startScreenBitrate(lan = true))
        assertEquals(2_500_000, startScreenBitrate(lan = false))
    }

    @Test
    fun onTheLanTheScreenNeverFallsBelowFourMegabits() {
        // A still screen sends far below its target, and one Wi-Fi delay spike used to drop the
        // estimate to 300 kbps: a smeared picture for the first seconds of every share.
        assertEquals(4_000_000, minScreenBitrate(lan = true))
        assertEquals(300_000, minScreenBitrate(lan = false))
        assertTrue(minScreenBitrate(lan = true) <= startScreenBitrate(lan = true))
        assertTrue(minScreenBitrate(lan = false) <= startScreenBitrate(lan = false))
    }

    @Test
    fun theFieldTrialsKeepPlayoutAtZeroAndFlushTheQueueOnKeyframes() {
        assertEquals(
            "WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:0/" +
                "WebRTC-Pacer-KeyframeFlushing/Enabled/WebRTC-Pacer-FastRetransmissions/Enabled/" +
                "WebRTC-Video-Pacing/max_delay:250ms/",
            WebRtcRuntime.FIELD_TRIALS,
        )
        // libwebrtc's format: name/group/ pairs, so an odd count of separators would garble every trial after it.
        assertEquals(0, WebRtcRuntime.FIELD_TRIALS.count { it == '/' } % 2)
    }

    @Test
    fun thePacerWaitIsTheMeanOverTheLastPackets() {
        assertEquals(437L, pacerWaitMs(344, 4.271, 1124, 345.26))
        assertEquals(null, pacerWaitMs(80, 1.0, 80, 1.0))
    }
}
