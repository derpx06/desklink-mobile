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
    fun theLastFrameIsRedeliveredOnceWhenMotionStops() {
        val pending = mutableListOf<Runnable>()
        var forced = 0
        lateinit var refinement: StaticRefinement
        refinement = StaticRefinement(
            100,
            schedule = { task, _ -> pending += task },
            cancel = { task -> pending.remove(task) },
            force = { forced++; refinement.onFrame() }, // the capture delivers the frame again
        )
        repeat(5) { refinement.onFrame() } // motion: only the latest deadline is pending
        assertEquals(1, pending.size)
        pending.removeAt(0).run()
        assertEquals(1, forced)
        // The re-delivered frame does not schedule another.
        assertTrue(pending.isEmpty())
    }

    @Test
    fun thePlayoutDelayTrialIsZero() {
        assertEquals("WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:0/", WebRtcRuntime.FIELD_TRIALS)
    }
}
