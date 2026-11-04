/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import java.io.File

/**
 * The conformance corpus (`src/test/resources/terminal-conformance`, identical to the desktop's
 * `pty/testdata/conformance`): byte streams, and the screen the desktop's `vt100` reference makes of each.
 * [grid] writes a Termux screen in the same text format, so a comparison is a string compare.
 */
object TerminalGrid {
    const val ROWS = 30

    class Case(val name: String, val cols: Int, val bytes: ByteArray, val resizes: List<Triple<Int, Int, Int>>, val grid: String, val snap: ByteArray) {
        override fun toString() = "$name.$cols"
    }

    private fun dir(): File {
        val url = TerminalGrid::class.java.classLoader!!.getResource("terminal-conformance")
        return File(requireNotNull(url) { "terminal-conformance resources are missing" }.toURI())
    }

    fun cases(): List<Case> = dir().listFiles { f -> f.name.endsWith(".bin") }!!.sortedBy { it.name }.map { bin ->
        val stem = bin.name.removeSuffix(".bin")
        val name = stem.substringBeforeLast('.')
        val cols = stem.substringAfterLast('.').toInt()
        val side = File(bin.parentFile, "$stem.resize")
        val resizes = if (side.exists()) {
            side.readLines().filter { it.isNotBlank() }.map { l -> l.trim().split(' ').map(String::toInt).let { Triple(it[0], it[1], it[2]) } }
        } else {
            emptyList()
        }
        Case(name, cols, bin.readBytes(), resizes, File(bin.parentFile, "$stem.grid").readText(), File(bin.parentFile, "$stem.snap").readBytes())
    }

    /** Feeds [bytes] in pieces of [piece] bytes (0 = all at once), applying the resizes at their offsets. */
    fun play(screen: TerminalScreen, bytes: ByteArray, resizes: List<Triple<Int, Int, Int>> = emptyList(), piece: Int = 0) {
        var at = 0
        fun upTo(end: Int) {
            while (at < end) {
                val n = if (piece <= 0) end - at else minOf(piece, end - at)
                screen.write(bytes.copyOfRange(at, at + n))
                at += n
            }
        }
        for ((offset, cols, rows) in resizes) {
            upTo(offset.coerceAtMost(bytes.size))
            screen.resize(rows, cols)
        }
        upTo(bytes.size)
    }

    fun grid(screen: TerminalScreen): String = screen.withEmulator { grid(it) }

    private fun key(style: Long, blank: Boolean): String {
        val fg = TextStyle.decodeForeColor(style)
        val bg = TextStyle.decodeBackColor(style)
        val effect = TextStyle.decodeEffect(style)
        fun c(v: Int, default: Int) = when {
            v == default -> "-"
            (v.toLong() and 0xff000000L) == 0xff000000L -> "#%06x".format(v and 0xffffff)
            else -> v.toString()
        }
        val bold = effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0
        val dim = effect and TextStyle.CHARACTER_ATTRIBUTE_DIM != 0
        val italic = effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0
        val underline = effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0
        val inverse = effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0
        val f = c(fg, TextStyle.COLOR_INDEX_FOREGROUND)
        val b = c(bg, TextStyle.COLOR_INDEX_BACKGROUND)
        if (blank && b == "-" && !inverse) return ""
        val sb = StringBuilder("fg=$f bg=$b")
        if (bold) sb.append(" bold")
        if (dim) sb.append(" dim")
        if (italic) sb.append(" italic")
        if (underline) sb.append(" underline")
        if (inverse) sb.append(" inverse")
        return if (sb.toString() == "fg=- bg=-") "" else sb.toString()
    }

    fun grid(e: TerminalEmulator): String {
        val rows = e.mRows
        val cols = e.mColumns
        val out = StringBuilder("size ${cols}x$rows\n")
        out.append("cursor ${e.cursorRow} ${e.cursorCol.coerceAtMost(cols - 1)} visible ${if (e.shouldCursorBeVisible()) 1 else 0} alt ${if (e.isAlternateBufferActive) 1 else 0}\n")
        val styles = StringBuilder()
        for (r in 0 until rows) {
            val row = e.screen.allocateFullLineIfNecessary(e.screen.externalToInternalRow(r))
            val text = StringBuilder()
            var run: Pair<Int, String>? = null
            var previous = ""
            var prevWidth = 1
            for (c in 0 until cols) {
                val start = row.findStartOfColumn(c)
                val end = if (c + 1 < cols) row.findStartOfColumn(c + 1) else row.spaceUsed
                val continuation = prevWidth == 2
                val ch = if (start < row.spaceUsed) row.mText[start] else ' '
                val blank = ch == ' ' || ch == '\u0000' || start >= row.spaceUsed
                var width = 1
                if (continuation) {
                    prevWidth = 1
                } else {
                    if (blank) {
                        text.append(' ')
                    } else {
                        text.append(String(row.mText, start, (end - start).coerceAtLeast(1)))
                        val cp = Character.codePointAt(row.mText, start)
                        width = WcWidth.width(cp).coerceAtLeast(1)
                    }
                    prevWidth = width
                }
                var k = if (continuation) previous else key(row.getStyle(c), blank)
                if (continuation) k = previous
                previous = k
                val current = run
                if (current != null && k == current.second && k.isNotEmpty()) continue
                if (current != null) styles.append("$r,${current.first}-${c - 1}: ${current.second}\n")
                run = if (k.isNotEmpty()) c to k else null
            }
            run?.let { styles.append("$r,${it.first}-${cols - 1}: ${it.second}\n") }
            out.append('|').append(text.toString().trimEnd()).append('\n')
        }
        out.append(styles)
        return out.toString()
    }

    /** First differing line of two grids, for a readable failure. */
    fun diff(want: String, got: String): String {
        val w = want.lines()
        val g = got.lines()
        for (i in 0 until maxOf(w.size, g.size)) {
            if (w.getOrNull(i) != g.getOrNull(i)) return "line $i\n  want: ${w.getOrNull(i)}\n  got:  ${g.getOrNull(i)}"
        }
        return "(equal)"
    }
}
