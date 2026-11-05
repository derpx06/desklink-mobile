/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The painter on the screens a view can end up with: tiny ones, a wide glyph at the edge, a stale scroll position. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xxhdpi")
class TerminalPainterTest {
    private val painter = TerminalPainter(Typeface.MONOSPACE, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).also { it.setTextSize(36) }

    private fun draw(screen: TerminalScreen, topRow: Int = 0): Bitmap {
        val w = painter.cellWidth * (screen.columns + 1)
        val h = painter.cellHeight * (screen.rows + 1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        screen.withEmulator { painter.render(Canvas(bitmap), it, topRow, null, cursorOn = true) }
        return bitmap
    }

    /** Number of lit pixels in column range [fromX, toX). */
    private fun lit(bitmap: Bitmap, fromX: Int, toX: Int): Int {
        var n = 0
        for (x in fromX until toX.coerceAtMost(bitmap.width)) for (y in 0 until bitmap.height) if (bitmap.getPixel(x, y) != 0) n++
        return n
    }

    @Test
    fun aOneRowTwoColumnScreenDrawsWithoutThrowing() {
        val screen = TerminalScreen(1, 2, reply = {})
        screen.write("ab\u001b[1;1H\u001b[31m█".toByteArray())
        val bitmap = draw(screen)
        assertTrue("the cells were painted", lit(bitmap, 0, painter.cellWidth * 2) > 0)
        assertEquals("nothing is drawn past the grid", 0, lit(bitmap, painter.cellWidth * 2, bitmap.width) - lit(bitmap, painter.cellWidth * 2, bitmap.width))
    }

    @Test
    fun aWideCharacterThatDoesNotFitTheLastColumnWrapsInsteadOfBleeding() {
        val screen = TerminalScreen(3, 4, reply = {})
        screen.write("abc日".toByteArray())
        assertEquals("abc", screen.tailLines(3).first())
        val bitmap = draw(screen)
        // The glyph is on the second row, in its own two cells.
        val rowTwoTop = painter.cellHeight
        var spill = 0
        for (x in painter.cellWidth * 4 until bitmap.width) for (y in 0 until bitmap.height) if (bitmap.getPixel(x, y) != 0) spill++
        assertEquals("nothing right of the grid", 0, spill)
        var second = 0
        for (x in 0 until painter.cellWidth * 2) for (y in rowTwoTop until rowTwoTop + painter.cellHeight) if (bitmap.getPixel(x, y) != 0) second++
        assertTrue("the wide glyph is on the second row", second > 0)
    }

    @Test
    fun aWideCharacterAtTheLastColumnOfAOneRowScreenDoesNotThrow() {
        val screen = TerminalScreen(1, 2, reply = {})
        screen.write("a日".toByteArray())
        draw(screen)
    }

    @Test
    fun aScrollPositionLeftOutOfRangeIsClamped() {
        val screen = TerminalScreen(3, 10, reply = {})
        screen.write("one\r\ntwo\r\nthree\r\nfour\r\nfive\r\n".toByteArray())
        val live = draw(screen, 0)
        // Far above the history, and below the live screen: both used to throw out of Termux.
        val above = draw(screen, -10_000)
        val below = draw(screen, 50)
        assertNotEquals("the history is shorter than the request, but there is some", 0, screen.withEmulator { it.screen.activeTranscriptRows })
        assertTrue(lit(above, 0, above.width) > 0)
        assertTrue(lit(below, 0, below.width) == lit(live, 0, live.width))
    }

    @Test
    fun theLastLinesSkipTrailingBlankRowsAndStayOnTheLockForOneRowAtATime() {
        val screen = TerminalScreen(10, 20, reply = {})
        screen.write("first\r\n\r\nthird\r\nfourth".toByteArray())
        assertEquals(listOf("third", "fourth"), screen.tailLines(2))
        assertEquals(listOf("first", "", "third", "fourth"), screen.tailLines(9))
    }
}
