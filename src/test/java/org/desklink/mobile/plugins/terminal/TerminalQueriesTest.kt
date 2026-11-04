/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What full-screen programs send at start-up (Claude Code, Codex, vim, fzf, htop): none of it may reach the
 * screen as text, and the queries a program waits for must be answered. Sequences are taken from the
 * recorded Claude Code and Codex streams.
 */
class TerminalQueriesTest {
    private val replies = StringBuilder()

    private fun screen(rows: Int = 6, cols: Int = 30) = TerminalScreen(rows, cols, reply = { replies.append(String(it)) })

    private fun TerminalScreen.show(): String = tailText(rows).trimEnd()

    private val silent = listOf(
        "decrqm sync" to "\u001b[?2026\$p",
        "decrqm 1016" to "\u001b[?1016\$p",
        "window size query" to "\u001b[16t",
        "push/pop title" to "\u001b[22;0;0t\u001b[23;0;0t",
        "xtversion" to "\u001b[>0q",
        "kitty keyboard query" to "\u001b[?u",
        "kitty keyboard push/pop/set" to "\u001b[>7u\u001b[<u\u001b[=5;1u",
        "xterm modifyOtherKeys" to "\u001b[>4;0m\u001b[>4;2m",
        "kitty graphics query (APC)" to "\u001b_Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA\u001b\\",
        "PM string" to "\u001b^private message\u001b\\",
        "SOS string" to "\u001bXstart of string\u001b\\",
        "XTGETTCAP (DCS)" to "\u001bP+q544e\u001b\\",
        "unknown OSC with BEL" to "\u001b]7501;?\u0007",
        "OSC colour queries" to "\u001b]10;?\u001b\\\u001b]11;?\u0007",
        "focus, colour scheme, 1007 modes" to "\u001b[?1004h\u001b[?2031h\u001b[?1007l\u001b[?2004h\u001b[?2026h\u001b[?2026l",
        "cursor style" to "\u001b[0 q\u001b[5 q",
        "soft reset" to "\u001b[!p",
        "keypad modes" to "\u001b=\u001b>",
        "charset designation" to "\u001b(B\u001b)0",
        "mouse modes" to "\u001b[?1000h\u001b[?1002h\u001b[?1003h\u001b[?1006h\u001b[?1015l",
        "title" to "\u001b]0;a title\u0007\u001b]2;another\u001b\\",
        "clipboard OSC 52" to "\u001b]52;c;aGVsbG8=\u0007",
    )

    @Test
    fun startupSequencesNeverReachTheScreenAsText() {
        val leaked = silent.mapNotNull { (name, bytes) ->
            val s = screen()
            s.write("\u001b[2J\u001b[H$bytes".toByteArray())
            s.show().takeIf { it.isNotEmpty() }?.let { "$name: \"$it\"" }
        }
        assertTrue("left text on the screen: $leaked", leaked.isEmpty())
    }

    @Test
    fun aStringSequenceCutAnywhereByTheChunkEndIsStillDropped() {
        val bytes = "ab\u001b_Gi=31,s=1,v=1,a=q;AAAA\u001b\\cd".toByteArray()
        for (cut in 1 until bytes.size) {
            val s = screen()
            s.write(bytes.copyOfRange(0, cut))
            s.write(bytes.copyOfRange(cut, bytes.size))
            assertEquals("cut at $cut", "abcd", s.show())
        }
    }

    @Test
    fun anUnterminatedStringDoesNotSwallowTheScreenForever() {
        val s = screen()
        s.write("\u001b_Gi=1;".toByteArray())
        s.write(("x".repeat(5000)).toByteArray())
        s.write("\u001b\\visible".toByteArray())
        assertEquals("visible", s.show())
    }

    @Test
    fun theQueriesAProgramWaitsForAreAnswered() {
        val s = screen()
        s.write("\u001b[3;4H\u001b[6n".toByteArray())
        assertTrue(replies.toString(), replies.toString().contains("\u001b[3;4R"))
        replies.clear()
        s.write("\u001b[c".toByteArray())
        assertTrue("DA1: $replies", replies.startsWith("\u001b[?"))
        assertTrue(replies.endsWith("c"))
        replies.clear()
        s.write("\u001b[>c".toByteArray())
        assertTrue("DA2: $replies", replies.startsWith("\u001b[>"))
        replies.clear()
        s.write("\u001b[5n".toByteArray())
        assertEquals("\u001b[0n", replies.toString())
    }

    @Test
    fun colourQueriesGetAnAnswerSoCodexDoesNotWaitForOne() {
        val s = screen()
        s.write("\u001b]11;?\u001b\\".toByteArray())
        assertTrue("OSC 11: $replies", replies.startsWith("\u001b]11;rgb:"))
        replies.clear()
        s.write("\u001b]10;?\u0007".toByteArray())
        assertTrue("OSC 10: $replies", replies.startsWith("\u001b]10;rgb:"))
    }

    @Test
    fun synchronizedOutputIsAppliedAsOneFrame() {
        val s = screen()
        s.write("old".toByteArray())
        s.write("\u001b[?2026h\u001b[2J\u001b[Hnew frame".toByteArray())
        assertEquals("a frame being drawn is not shown", "old", s.show())
        s.write(" done\u001b[?2026l".toByteArray())
        assertEquals("new frame done", s.show())
    }

    @Test
    fun aFrameThatNeverEndsIsShownAfterAMoment() {
        val s = screen()
        s.write("\u001b[?2026hhalf".toByteArray())
        assertEquals("", s.show())
        Thread.sleep(400)
        assertEquals("half", s.show())
    }

    @Test
    fun theSyncMarkersCutByTheChunkEndStillWork() {
        val bytes = "\u001b[?2026hframe\u001b[?2026l after".toByteArray()
        for (cut in 1 until bytes.size) {
            val s = screen()
            s.write(bytes.copyOfRange(0, cut))
            s.write(bytes.copyOfRange(cut, bytes.size))
            assertEquals("cut at $cut", "frame after", s.show())
        }
    }
}
