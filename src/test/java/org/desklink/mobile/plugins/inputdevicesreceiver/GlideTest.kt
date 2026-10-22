/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.inputdevicesreceiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Contract F4 G1/G3 on the phone: where the pointer enters, and the deltas it goes back with. */
@RunWith(RobolectricTestRunner::class)
class GlideTest {
    private val w = 1080
    private val h = 2400

    @Test
    fun theDesksExitEdgeIsTheOppositePhoneEdge() {
        // Desk right (Qt 4) → phone left; desk left (2) → phone right; desk bottom (8) → phone top.
        assertEquals(1 to 1200, Glide.entry(Glide.LEFT_EDGE, 0.0, 540.0, 1080.0, w, h))
        assertEquals(w - 1 to 1200, Glide.entry(Glide.RIGHT_EDGE, 0.0, 540.0, 1080.0, w, h))
        assertEquals(540 to 1, Glide.entry(Glide.TOP_EDGE, 960.0, 0.0, 1920.0, w, h))
        assertEquals(540 to h - 1, Glide.entry(Glide.BOTTOM_EDGE, 960.0, 0.0, 1920.0, w, h))
        assertTrue(Glide.validEdge(4) && !Glide.validEdge(0) && !Glide.validEdge(3) && !Glide.validEdge(16))
    }

    @Test
    fun withoutAnEdgeLengthDeltasArePixelsClampedOnScreen() {
        assertEquals(1 to 700, Glide.entry(Glide.LEFT_EDGE, 0.0, 700.0, null, w, h))
        assertEquals(1 to h - 1, Glide.entry(Glide.LEFT_EDGE, 0.0, 99_999.0, null, w, h))
        assertEquals(1 to 0, Glide.entry(Glide.LEFT_EDGE, 0.0, -50.0, null, w, h))
        // A junk delta past the bound cannot overflow.
        assertEquals(1 to h - 1, Glide.entry(Glide.LEFT_EDGE, 0.0, 1e300, 1080.0, w, h))
    }

    @Test
    fun releaseComesOnlyAtTheEntryEdgeInTheDesksUnits() {
        assertNull(Glide.release(Glide.LEFT_EDGE, 5, 1200, 1080.0, w, h))
        assertEquals(0 to 540, Glide.release(Glide.LEFT_EDGE, 0, 1200, 1080.0, w, h))
        assertEquals(0 to 1200, Glide.release(Glide.LEFT_EDGE, 0, 1200, null, w, h))
        assertEquals(960 to 0, Glide.release(Glide.TOP_EDGE, 540, 0, 1920.0, w, h))
        assertNull(Glide.release(Glide.TOP_EDGE, 540, h, 1920.0, w, h))
        assertEquals(0 to 540, Glide.release(Glide.RIGHT_EDGE, w, 1200, 1080.0, w, h))
        // Stop from the phone: back where the pointer is along the edge, wherever it is.
        assertEquals(0 to 540, Glide.handBack(Glide.LEFT_EDGE, 500, 1200, 1080.0, w, h))
    }

    @Test
    fun aGlideEndsWhenIdleAndBelongsToOneDesk() {
        Glide.begin("desk", Glide.LEFT_EDGE, null, now = 1_000)
        assertTrue(Glide.isGliding("desk", now = 1_000 + Glide.IDLE_END_MS - 1))
        assertFalse(Glide.isGliding("other", now = 1_001))
        assertFalse(Glide.isGliding("desk", now = 1_000 + Glide.IDLE_END_MS))
        Glide.end()
        assertFalse(Glide.isGliding("desk", now = 1_001))
    }

    @Test
    fun keyTextOverTheBoundIsRefused() {
        assertTrue(Glide.accepts("k1", "a".repeat(Glide.MAX_KEY_CHARS)))
        assertFalse(Glide.accepts("k1", "a".repeat(Glide.MAX_KEY_CHARS + 1)))
        assertTrue(Glide.accepts("k1", null))
    }
}
