/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.mpris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaColorsTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    // Light tokens: surface, text, textDim, bg (the play icon).
    private val surface = 0xFFFFFFFF.toInt()
    private val text = 0xFF15171A.toInt()
    private val textDim = 0xFF5E636B.toInt()
    private val bg = 0xFFF6F6F3.toInt()

    @Test
    fun contrastIsTheWcagRatio() {
        assertEquals(21.0, MediaColors.contrast(white, black), 0.01)
        assertEquals(1.0, MediaColors.contrast(white, white), 0.01)
    }

    @Test
    fun aColourfulCoverTintsTheCardAndKeepsEverythingReadable() {
        val scheme = MediaColors.scheme(0xFFE0402A.toInt(), surface, text, textDim, bg, dark = false)
        assertNotNull(scheme)
        scheme!!
        assertTrue(MediaColors.contrast(text, scheme.cardTop) >= 4.5)
        assertTrue(MediaColors.contrast(textDim, scheme.cardTop) >= 4.5)
        assertTrue(MediaColors.contrast(bg, scheme.accent) >= 4.5)
        assertTrue(MediaColors.contrast(scheme.accent, scheme.cardTop) >= 3.0)
    }

    @Test
    fun aTintThatHurtsTheTextFallsBackToTheTokens() {
        // A dim text that only just reads on the plain card: any tint at all takes it below 4.5:1.
        val dim = 0xFF767676.toInt() // 4.54:1 on white
        assertNull(MediaColors.scheme(0xFF2040E0.toInt(), surface, text, dim, bg, dark = false))
        assertNull(MediaColors.scheme(null, surface, text, textDim, bg, dark = false))
    }

    @Test
    fun theVibrantColourIsTheSaturatedPartAndGreyHasNone() {
        val red = 0xFFD03020.toInt()
        val pixels = IntArray(64) { if (it < 16) red else 0xFF808080.toInt() }
        assertEquals(red, MediaColors.vibrant(pixels))
        assertNull(MediaColors.vibrant(IntArray(64) { 0xFF777777.toInt() }))
        assertNull(MediaColors.vibrant(IntArray(16) { 0x00FF0000 }))
    }
}
