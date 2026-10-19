package org.desklink.mobile.plugins.mousepad

import org.junit.Assert.assertEquals
import org.junit.Test

class TwoFingerModeTest {
    private val slop = 24f

    @Test
    fun `fingers moving together scroll even though they spread a little`() {
        assertEquals(TwoFingerMode.Scroll, twoFingerMode(travel = 60f, spread = 30f, slop = slop))
    }

    @Test
    fun `fingers moving apart zoom`() {
        assertEquals(TwoFingerMode.Zoom, twoFingerMode(travel = 10f, spread = 80f, slop = slop))
    }

    @Test
    fun `small movements decide nothing yet`() {
        assertEquals(TwoFingerMode.Undecided, twoFingerMode(travel = 10f, spread = 30f, slop = slop))
    }
}
