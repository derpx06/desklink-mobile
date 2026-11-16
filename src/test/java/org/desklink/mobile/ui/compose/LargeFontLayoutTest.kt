/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose

import org.desklink.mobile.ui.compose.components.stacksTrailing
import org.desklink.mobile.ui.compose.screen.home.heroTilesStacked
import org.desklink.mobile.ui.compose.screen.home.offlineTileColumns
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
    fun theHeroTilesStackWhenThreeAcrossWouldBreakTheirNames() {
        assertFalse(heroTilesStacked(360, 1.0f))
        assertTrue("Termina / ls on a 360 dp phone at 1.3", heroTilesStacked(360, 1.3f))
        assertFalse("a 411 dp phone keeps three across at 1.3", heroTilesStacked(411, 1.3f))
        assertTrue(heroTilesStacked(411, 1.4f))
        assertTrue(heroTilesStacked(411, 2.0f))
        assertTrue("Termina / ls on a 320 dp phone at 1.0", heroTilesStacked(320, 1.0f))
    }

    @Test
    fun theOfflineTilesGoOneAcrossWhenTwoWouldBreakTheirNames() {
        assertEquals(2, offlineTileColumns(360, 1.0f))
        assertEquals(2, offlineTileColumns(320, 1.0f))
        assertEquals("Terminal / s on a 360 dp phone at 1.3", 1, offlineTileColumns(360, 1.3f))
        assertEquals(2, offlineTileColumns(412, 1.3f))
        assertEquals(1, offlineTileColumns(412, 1.5f))
    }
}
