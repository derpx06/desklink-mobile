/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mousereceiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Glide's pointer: one overlay update per frame from the latest position, fractions kept, held inside the screen. */
class PointerFramesTest {
    private var asked = 0
    private val frames = PointerFrames { asked++; true }.apply { setBounds(1080, 2400) }

    @Test
    fun aBurstOfMovesAsksForOneFrameThatDrawsTheLatestPosition() {
        frames.moveTo(500.0, 500.0)
        frames.frame()
        asked = 0
        // 125 packets a second against a 60 Hz panel: two moves per frame, and a fast mouse many more.
        repeat(20) { frames.moveBy(3.0, -1.0) }
        assertEquals("one frame for the whole burst (was one window relayout per move)", 1, asked)
        assertTrue(frames.pending())
        assertEquals(listOf(560, 480), frames.frame().toList())
        assertFalse(frames.pending())
        frames.moveBy(1.0, 0.0)
        assertEquals("the next move asks for the next frame", 2, asked)
    }

    @Test
    fun fractionsAddUpInsteadOfEachBecomingAPixel() {
        frames.moveTo(100.0, 100.0)
        // A slow, mostly horizontal move: 0.3 px right and 0.1 px down per packet.
        repeat(10) { frames.moveBy(0.3, 0.1) }
        assertEquals("3 px right, as the desk's pointer moved (rounding up each made it 10)", 103, frames.x())
        assertEquals("and 1 px down, not 10", 101, frames.y())
    }

    @Test
    fun thePointerStaysOnTheScreenAndReachesEveryEdge() {
        frames.moveTo(10.0, 10.0)
        frames.moveBy(-500.0, -500.0)
        assertEquals(0, frames.x())
        assertEquals(0, frames.y())
        frames.moveBy(5000.0, 5000.0)
        assertEquals("the right edge itself, where a glide entered from the right hands back", 1080, frames.x())
        assertEquals(2400, frames.y())
        frames.moveBy(Double.NaN, 1.0)
        assertEquals("junk is ignored", 1080, frames.x())
    }

    @Test
    fun noOverlayYetMeansTheNextMoveAsksAgain() {
        var overlay = false
        var tries = 0
        val early = PointerFrames { tries++; overlay }
        early.moveBy(1.0, 1.0)
        assertFalse("nothing could be asked: not marked as asked", early.pending())
        overlay = true
        early.moveBy(1.0, 1.0)
        assertTrue(early.pending())
        assertEquals(2, tries)
    }
}
