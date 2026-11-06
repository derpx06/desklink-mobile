/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How often a person would see a half-drawn terminal. [FlickerSim] delivers repaint bursts the way the
 * desktop does (4 KiB PTY reads, an 8 ms pump) and counts the presented screens that are not a
 * whole frame. `legacy` is what the view did before [TerminalFramePacer]: draw at the first vsync after any
 * output.
 */
class TerminalFlickerTest {
    private val frames = 400

    private val streams = linkedMapOf(
        "ink erase+redraw (Claude Code classic, no sync)" to FlickerSim.inkErase(frames),
        "clear+redraw (curses, vim, htop, no sync)" to FlickerSim.clearRedraw(frames),
        "ink erase+redraw inside ?2026" to FlickerSim.synced(FlickerSim.inkErase(frames)),
        "clear+redraw inside ?2026 (Codex)" to FlickerSim.synced(FlickerSim.clearRedraw(frames)),
    )

    private fun measure(pacer: () -> TerminalFramePacer) = streams.mapValues { (_, bursts) -> FlickerSim(pacerFactory = pacer).run(bursts) }

    private fun report(title: String, results: Map<String, FlickerSim.Result>) {
        val dir = File(System.getProperty("desklink.terminals") ?: (System.getProperty("user.home") + "/.cache/android-ui/terminals")).also { it.mkdirs() }
        File(dir, "flicker-$title.md").writeText(
            "| stream | presented | partial | blank | redundant | presents/s |\n|---|---|---|---|---|---|\n" +
                results.entries.joinToString("\n") { (k, r) -> "| $k | ${r.presented} | ${r.partial} | ${r.blank} | ${r.redundant} | %.1f |".format(r.presentsPerSecond) } + "\n",
        )
        println("== $title\n" + results.entries.joinToString("\n") { (k, r) -> "$k: $r ${r.examples}" })
    }

    /**
     * Programs that probe for synchronized output (Claude Code, neovim, helix) draw each frame between
     * `?2026h` and `l` only if told it is supported. Termux answers "mode not recognised" (value 0), so they never do.
     */
    @Test
    fun theEmulatorSaysSynchronizedOutputIsNotSupported() {
        val replies = StringBuilder()
        TerminalScreen(10, 40, reply = { replies.append(String(it)) }).write("\u001b[?2026\$p".toByteArray())
        println("DECRQM 2026 reply from the emulator: [${replies.toString().replace("\u001b", "ESC")}]")
        assertEquals("\u001b[?2026;0\$y", replies.toString())
    }

    @Test
    fun legacyPacingShowsHalfDrawnScreens() {
        val legacy = measure { TerminalFramePacer.legacy() }
        report("legacy", legacy)
        assertTrue("ink: ${legacy.values.first()}", legacy.values.first().partial > 0)
        assertTrue("curses: ${legacy.values.drop(1).first()}", legacy.values.drop(1).first().partial > 0)
        assertEquals("a synchronized frame is never shown half done", 0, legacy.values.drop(2).sumOf { it.partial })
    }
}
