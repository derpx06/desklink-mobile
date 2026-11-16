/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose

import org.desklink.mobile.ui.compose.components.stacksTrailing
import org.desklink.mobile.ui.compose.screen.home.tilesAcross
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F47: words that would break inside a word at a large font (or on a narrow phone) get a layout where they fit. */
class LargeFontLayoutTest {
    @Test
    fun aRowsButtonGoesUnderItsWordsWhenTheWordsWouldBeSqueezed() {
        // 268 px for the words and the button, 14 px gap, the words keep at least 120.
        assertFalse("a switch-sized trailing part stays beside", stacksTrailing(268, 52, 14, 120))
        assertFalse("nothing trailing", stacksTrailing(268, 0, 14, 120))
        assertTrue("'Ask on computer' beside 'Off on manas-82xf'", stacksTrailing(268, 140, 14, 120))
        assertFalse("a wide row keeps it beside", stacksTrailing(600, 140, 14, 120))
    }

    @Test
    fun tilesGoFewerAcrossWhenAWordWouldBreak() {
        // The hero tiles in a 360 dp phone's hero card (292 dp, 8 dp gaps, 24 dp padding): "Terminals" 60 dp fits three across.
        assertEquals(3, tilesAcross(292f, 3, gap = 8f, fixed = 24f, word = 60f))
        // The same word at 1.3 (78 dp): not three across.
        assertEquals(2, tilesAcross(292f, 3, gap = 8f, fixed = 24f, word = 78f))
        // A 320 dp phone (252 dp): "Termina / ls" at 1.0.
        assertEquals(2, tilesAcross(252f, 3, gap = 8f, fixed = 24f, word = 60f))
        // Nothing fits: still one.
        assertEquals(1, tilesAcross(100f, 3, gap = 8f, fixed = 24f, word = 200f))
        // Two offline tiles (74 dp each fixed) in 328 dp: two across for 66 dp, one for 90 dp.
        assertEquals(2, tilesAcross(328f, 2, gap = 8f, fixed = 74f, word = 66f))
        assertEquals(1, tilesAcross(328f, 2, gap = 8f, fixed = 74f, word = 90f))
    }
}
