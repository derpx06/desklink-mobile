/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the real Termux emulator headlessly, with no view and no PTY: this is the proof that the
 * engine choice works without a local process. The SNAPSHOT_* constants are the bytes the desktop's
 * own `Output::snapshot()` produced (vt100 `state_formatted` behind `ESC c`, plus `ESC [ ? 1049 h`
 * on the alternate screen), so they also prove the phone reproduces what the desktop sends.
 */
class TerminalScreenTest {
    private val replies = mutableListOf<String>()

    private fun screen(rows: Int = 6, cols: Int = 20) =
        TerminalScreen(rows, cols, reply = { replies += String(it) })

    private fun TerminalScreen.feed(text: String) = write(text.toByteArray())

    private fun TerminalScreen.text(): String = tailText(rows).trimEnd()

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun TerminalScreen.style(row: Int, col: Int): Long = withEmulator { it.screen.getStyleAt(row, col) }

    // ---- text, colour, attributes -----------------------------------------------------------------

    @Test
    fun plainTextAndLineBreaksLandOnTheScreen() {
        val s = screen()
        s.feed("hello\r\nworld")
        assertEquals("hello\nworld", s.text())
    }

    @Test
    fun sixteenColoursAreIndexed() {
        val s = screen()
        s.feed("\u001b[31mred\u001b[0m x")
        assertEquals("red x", s.text())
        assertEquals(1, TextStyle.decodeForeColor(s.style(0, 0)))
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.decodeForeColor(s.style(0, 4)))
    }

    @Test
    fun the256ColourPaletteIsAddressed() {
        val s = screen()
        s.feed("\u001b[38;5;196mA\u001b[48;5;21mB")
        assertEquals(196, TextStyle.decodeForeColor(s.style(0, 0)))
        assertEquals(21, TextStyle.decodeBackColor(s.style(0, 1)))
    }

    @Test
    fun trueColourIsCarriedAsRgb() {
        val s = screen()
        s.feed("\u001b[38;2;10;20;30mA\u001b[48;2;200;100;50mB")
        assertEquals(0xff0a141e.toInt(), TextStyle.decodeForeColor(s.style(0, 0)))
        assertEquals(0xffc86432.toInt(), TextStyle.decodeBackColor(s.style(0, 1)))
    }

    @Test
    fun boldUnderlineAndInverseAreRecorded() {
        val s = screen()
        s.feed("\u001b[1mB\u001b[0m\u001b[4mU\u001b[0m\u001b[7mI")
        fun effect(col: Int) = TextStyle.decodeEffect(s.style(0, col))
        assertTrue(effect(0) and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0)
        assertTrue(effect(1) and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0)
        assertTrue(effect(2) and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0)
        assertEquals(0, effect(0) and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE)
    }

    @Test
    fun wideCharactersTakeTwoCells() {
        val s = screen()
        s.feed("日本ab")
        assertEquals("日本ab", s.text())
        assertEquals(6, s.withEmulator { it.cursorCol })
    }

    // ---- cursor and screen control ---------------------------------------------------------------------

    @Test
    fun cursorMovesAreHonoured() {
        val s = screen()
        s.feed("abc\u001b[2;5Hx")
        assertEquals("abc\n    x", s.text())
        assertEquals(1, s.withEmulator { it.cursorRow })
        assertEquals(5, s.withEmulator { it.cursorCol })

        s.feed("\u001b[H\u001b[2J")
        assertEquals("", s.text())
        assertEquals(0, s.withEmulator { it.cursorRow })
    }

    @Test
    fun aSequenceSplitAcrossFramesStillParses() {
        val s = screen()
        s.write("\u001b[3".toByteArray())
        s.write("1mX".toByteArray())
        assertEquals(1, TextStyle.decodeForeColor(s.style(0, 0)))
        assertEquals("X", s.text())
    }

    @Test
    fun aMultiByteCharacterSplitAcrossFramesStillDecodes() {
        val s = screen()
        val bytes = "é日".toByteArray()
        assertEquals(5, bytes.size)
        s.write(bytes.copyOfRange(0, 1))
        s.write(bytes.copyOfRange(1, 3))
        s.write(bytes.copyOfRange(3, 5))
        assertEquals("é日", s.text())
    }

    @Test
    fun theAlternateScreenIsEnteredAndLeftWithoutLosingTheShell() {
        val s = screen()
        s.feed("shell\$ vim")
        assertFalse(s.withEmulator { it.isAlternateBufferActive })

        s.feed("\u001b[?1049h\u001b[2J\u001b[Hvim body")
        assertTrue(s.withEmulator { it.isAlternateBufferActive })
        assertEquals("vim body", s.text())

        s.feed("\u001b[?1049l")
        assertFalse(s.withEmulator { it.isAlternateBufferActive })
        assertEquals("shell\$ vim", s.text())
    }

    @Test
    fun scrollbackKeepsLinesThatLeftTheScreen() {
        val s = screen()
        repeat(100) { s.feed("line $it\r\n") }
        assertTrue(s.withEmulator { it.screen.activeTranscriptRows } > 90)
        val all = s.transcript()
        assertTrue(all.startsWith("line 0\n"))
        assertTrue(all.contains("line 99"))
    }

    @Test
    fun resizingKeepsTheContentAndTakesTheNewGrid() {
        val s = screen(6, 20)
        s.feed("hello")
        s.resize(10, 40)
        assertEquals(10, s.rows)
        assertEquals(40, s.columns)
        assertEquals("hello", s.text())
        s.resize(1, 1) // a degenerate grid is clamped, not thrown
        assertEquals(4, s.rows)
    }

    @Test
    fun theTitleAndBellAreReported() {
        val s = screen()
        var bells = 0
        s.listener = object : TerminalScreen.Listener {
            override fun onScreenChanged() = Unit
            override fun onSnapshot() = Unit
            override fun onBell() { bells++ }
        }
        s.feed("\u001b]0;my title\u0007")
        assertEquals("my title", s.title)
        s.feed("\u0007")
        assertEquals(1, bells)
    }

    @Test
    fun keyboardAndMouseModesAreTracked() {
        val s = screen()
        s.feed("\u001b[?1h")
        assertTrue(s.withEmulator { it.isCursorKeysApplicationMode })
        assertFalse(s.withEmulator { it.isMouseTrackingActive })
        s.feed("\u001b[?1000h")
        assertTrue(s.withEmulator { it.isMouseTrackingActive })
    }

    // ---- replies to the program's queries ------------------------------------------------------------------

    @Test
    fun aCursorPositionQueryIsAnsweredWithTheCursorPosition() {
        val s = screen()
        s.feed("abc\u001b[6n")
        assertEquals(listOf("\u001b[1;4R"), replies)
    }

    @Test
    fun deviceAttributeQueriesAreAnswered() {
        val s = screen()
        s.feed("\u001b[c\u001b[>c")
        assertEquals(2, replies.size)
        assertTrue(replies[0].startsWith("\u001b[?64;"))
        assertEquals("\u001b[>41;320;0c", replies[1])
    }

    @Test
    fun repliesAreDroppedWhileReplayingHistoryAndResumeAfter() {
        val s = screen()
        s.setReplaying(true)
        s.feed("\u001b[6n\u001b[c") // queries inside replayed history were answered long ago
        assertTrue("a stale reply typed into a shell is garbage", replies.isEmpty())
        s.setReplaying(false)
        s.feed("\u001b[6n")
        assertEquals(1, replies.size)
    }

    @Test
    fun aTouchScrollBecomesWheelEventsWhenTheProgramTracksTheMouse() {
        val s = screen()
        s.feed("\u001b[?1000h")
        s.withEmulator { it.sendMouseEvent(TerminalEmulator.MOUSE_WHEELUP_BUTTON, 1, 1, true) }
        assertEquals(1, replies.size)
        assertEquals("\u001b[M" + (32 + 64).toChar() + (32 + 1).toChar() + (32 + 1).toChar(), replies[0])

        replies.clear()
        s.feed("\u001b[?1006h")
        s.withEmulator { it.sendMouseEvent(TerminalEmulator.MOUSE_WHEELDOWN_BUTTON, 5, 3, true) }
        assertEquals(listOf("\u001b[<65;5;3M"), replies)
    }

    @Test
    fun pastingHonoursBracketedPasteMode() {
        val s = screen()
        s.withEmulator { it.paste("a\nb") }
        assertEquals("a\rb", replies.joinToString(""))

        replies.clear()
        s.feed("\u001b[?2004h")
        s.withEmulator { it.paste("x\u001b[31my") } // an embedded ESC must not reach the shell
        assertEquals("\u001b[200~x[31my\u001b[201~", replies.joinToString(""))
    }

    // ---- desktop snapshots ---------------------------------------------------------------------------

    @Test
    fun aNormalScreenSnapshotFromTheDesktopIsReproduced() {
        val s = screen(6, 20)
        s.feed("stale content from before the reattach")
        s.beginSnapshot()
        s.write(hex(SNAPSHOT_NORMAL))
        assertEquals("user@host:~\$ ls\nfile_a  file_b\nred256 true\n\$", s.text())
        assertEquals(3, s.withEmulator { it.cursorRow })
        assertEquals(2, s.withEmulator { it.cursorCol })
        // Colours survive: `user@host` is bold green, `red256` is palette 196, `true` is true colour.
        assertEquals(2, TextStyle.decodeForeColor(s.style(0, 0)))
        assertEquals(196, TextStyle.decodeForeColor(s.style(2, 0)))
        assertEquals(0xff0a141e.toInt(), TextStyle.decodeForeColor(s.style(2, 7)))
        assertTrue(TextStyle.decodeEffect(s.style(1, 8)) and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0)
    }

    @Test
    fun anAlternateScreenSnapshotLandsInTheAlternateBuffer() {
        val s = screen(6, 20)
        s.beginSnapshot()
        s.write(hex(SNAPSHOT_ALT))
        assertTrue("vim's screen must not be painted onto the shell buffer", s.withEmulator { it.isAlternateBufferActive })
        assertEquals("hello vim\n~\n~\n\n\n-- INSERT --", s.text())
        assertEquals(0, s.withEmulator { it.cursorRow })
        assertEquals(5, s.withEmulator { it.cursorCol })
        assertTrue(TextStyle.decodeEffect(s.style(5, 0)) and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0)
    }

    @Test
    fun aSnapshotAfterTheProgramLeftTheAlternateScreenReturnsToTheMainBuffer() {
        val s = screen(6, 20)
        s.feed("\u001b[?1049h\u001b[Hvim junk")
        assertTrue(s.withEmulator { it.isAlternateBufferActive })
        // The phone fell behind while vim was running and vim has since exited.
        s.beginSnapshot()
        s.write(hex(SNAPSHOT_NORMAL))
        assertFalse(s.withEmulator { it.isAlternateBufferActive })
        assertTrue(s.text().startsWith("user@host"))
    }

    @Test
    fun wideCharactersSurviveASnapshot() {
        val s = screen(3, 10)
        s.beginSnapshot()
        s.write(hex(SNAPSHOT_WIDE))
        assertEquals("日本語 ok", s.text())
    }

    @Test
    fun aSnapshotSplitAcrossFramesIsReproducedIdentically() {
        val whole = screen(6, 20).also { it.beginSnapshot(); it.write(hex(SNAPSHOT_ALT)) }
        val split = screen(6, 20).also { s ->
            s.beginSnapshot()
            hex(SNAPSHOT_ALT).toList().chunked(7).forEach { s.write(it.toByteArray()) }
        }
        assertEquals(whole.text(), split.text())
        assertEquals(whole.withEmulator { it.cursorCol }, split.withEmulator { it.cursorCol })
    }

    @Test
    fun theOutputObserverSeesEveryWrite() {
        var seen = 0
        val s = TerminalScreen(
            6, 20, reply = {},
            observer = object : TerminalScreen.Observer {
                override fun onOutput(screen: TerminalScreen) { seen++ }
                override fun onEnded(screen: TerminalScreen, exitStatus: Long?) = Unit
            },
        )
        s.write("a".toByteArray())
        s.write(ByteArray(0)) // nothing to draw, nothing to notify
        s.write("b".toByteArray())
        assertEquals(2, seen)
    }

    private companion object {
        // Produced by the desktop's `Output::snapshot()` (see the class comment).
        const val SNAPSHOT_NORMAL =
            "1b631b5b3f3235681b5b6d1b5b481b5b4a1b5b33323b316d7573657240686f73741b5b6d3a1b5b33346d7e1b5b6d24206c730d0a" +
                "66696c655f6120201b5b346d66696c655f620d0a1b5b33383b353b3139363b32346d7265643235361b5b6d201b5b33383b323b" +
                "31303b32303b33306d747275650d0a1b5b6d24201b3e1b5b3f316c1b5b3f323030346c"
        const val SNAPSHOT_ALT =
            "1b631b5b3f31303439681b5b3f3235681b5b6d1b5b481b5b4a68656c6c6f2076696d0d0a7e0d0a7e1b5b363b31481b5b376d2d2d" +
                "20494e53455254202d2d1b5b313b36481b5b6d1b3e1b5b3f316c1b5b3f323030346c"
        const val SNAPSHOT_WIDE = "1b631b5b3f3235681b5b6d1b5b481b5b4ae697a5e69cace8aa9e206f6b0d0a1b3e1b5b3f316c1b5b3f323030346c"
    }
}
