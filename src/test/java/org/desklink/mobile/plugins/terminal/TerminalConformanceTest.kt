/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Replays the shared conformance corpus (recorded and synthetic streams, 40/60/80/120 columns) through
 * the phone's emulator and compares the screen cell by cell - text, cursor, colours, attributes - with the
 * one the desktop's `vt100` reference produced. A divergence is a place where the phone would draw
 * what the program did not mean. Also: the same bytes cut into odd pieces (UTF-8 and escape sequences
 * split at every kind of boundary), and the snapshot the desktop sends an attaching phone.
 *
 * Writes `conformance.md` (case x width) next to the screenshots, see [ShotDir]-style path below.
 */
class TerminalConformanceTest {
    private val cases = TerminalGrid.cases()

    private fun replay(case: TerminalGrid.Case, piece: Int): String {
        val screen = TerminalScreen(TerminalGrid.ROWS, case.cols, reply = {})
        TerminalGrid.play(screen, case.bytes, case.resizes, piece)
        return TerminalGrid.grid(screen)
    }

    private fun fromSnapshot(case: TerminalGrid.Case): String {
        val screen = TerminalScreen(TerminalGrid.ROWS, case.cols, reply = {})
        screen.beginSnapshot()
        screen.write(case.snap)
        return TerminalGrid.grid(screen)
    }

    @Test
    fun theCorpusIsThere() {
        assertTrue("expected the 60+ conformance cases, got ${cases.size}", cases.size >= 60)
    }

    @Test
    fun everyStreamMakesTheReferenceScreen() {
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val byName = cases.groupBy { it.name }
        for ((name, group) in byName) {
            val cells = group.sortedBy { it.cols }.map { case ->
                val modes = linkedMapOf<String, String>()
                modes["whole"] = replay(case, 0)
                modes["7-byte pieces"] = replay(case, 7)
                modes["1-byte pieces"] = replay(case, 1)
                var ok = true
                for ((mode, got) in modes) {
                    if (got != case.grid) {
                        ok = false
                        failures += "$case ($mode): ${TerminalGrid.diff(case.grid, got)}"
                    }
                }
                if (!ok) "FAIL" else "pass"
            }
            rows += "| $name | ${cells.joinToString(" | ")} |"
        }
        report("Stream replay (whole, 7-byte and 1-byte pieces; Termux vs the vt100 reference)", rows)
        assertTrue("${failures.size} divergences:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun theAttachSnapshotReproducesTheScreen() {
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for ((name, group) in cases.groupBy { it.name }) {
            val cells = group.sortedBy { it.cols }.map { case ->
                val got = fromSnapshot(case)
                if (got == case.grid) {
                    "pass"
                } else {
                    failures += "$case (snapshot): ${TerminalGrid.diff(case.grid, got)}"
                    "FAIL"
                }
            }
            rows += "| $name | ${cells.joinToString(" | ")} |"
        }
        report("Attach snapshot (what the desktop sends a phone that attaches mid-session)", rows)
        assertTrue("${failures.size} divergences:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun report(title: String, rows: List<String>) {
        val dir = File(System.getProperty("desklink.terminals") ?: (System.getProperty("user.home") + "/.cache/android-ui/terminals")).also { it.mkdirs() }
        val file = File(dir, "conformance.md")
        val existing = if (file.exists()) file.readText().substringAfter("<!--sections-->\n", "") else ""
        val kept = existing.split("\n## ").filter { it.isNotBlank() && !it.trimStart('#', ' ').startsWith(title) }
        val section = "## $title\n\n| case | 40 | 60 | 80 | 120 |\n|---|---|---|---|---|\n${rows.joinToString("\n")}\n"
        file.writeText("<!--sections-->\n" + (kept.map { it.removePrefix("## ") }.map { "## $it" } + section).joinToString("\n"))
    }
}
