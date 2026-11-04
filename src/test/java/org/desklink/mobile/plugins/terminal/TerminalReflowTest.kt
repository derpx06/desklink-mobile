/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Why the phone's screen takes only the size the desktop reports. Termux crops the alternate screen when it
 * shrinks and does not give the cells back when it grows, so the phone's old habit of resizing its
 * emulator to its own view (and back, on every attach and keyboard change) while the program still drew
 * for the desk's size destroyed what Claude Code, Codex, vim and htop had on screen until they repainted.
 */
class TerminalReflowTest {
    private fun alt(name: String): TerminalGrid.Case = TerminalGrid.cases().first { it.name == name && it.cols == 120 }

    @Test
    fun aRoundTripThroughASmallerSizeLosesTheAlternateScreen() {
        val case = alt("ratatui")
        val screen = TerminalScreen(TerminalGrid.ROWS, 120, reply = {})
        TerminalGrid.play(screen, case.bytes)
        val before = TerminalGrid.grid(screen)
        screen.resize(20, 45)
        screen.resize(TerminalGrid.ROWS, 120)
        assertNotEquals("Termux keeps the cropped cells: this is the damage a local resize did", before, TerminalGrid.grid(screen))
    }

    @Test
    fun aScreenThatOnlyFollowsTheHostKeepsItsCells() {
        val case = alt("ratatui")
        val screen = TerminalScreen(TerminalGrid.ROWS, 120, reply = {})
        TerminalGrid.play(screen, case.bytes)
        val before = TerminalGrid.grid(screen)
        // The host answering a refused resize: the same size again, a no-op.
        screen.resize(TerminalGrid.ROWS, 120)
        assertEquals(before, TerminalGrid.grid(screen))
    }
}
