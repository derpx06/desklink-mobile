/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            "| stream | presented | partial | blank | redundant | presents/s | latency mean/max ms |\n|---|---|---|---|---|---|---|\n" +
                results.entries.joinToString("\n") { (k, r) ->
                    "| $k | ${r.presented} | ${r.partial} | ${r.blank} | ${r.redundant} | %.1f | %.1f / %.1f |".format(r.presentsPerSecond, r.meanLatencyMs, r.maxLatencyMs)
                } + "\n",
        )
        println("== $title\n" + results.entries.joinToString("\n") { (k, r) -> "$k: $r" })
    }

    // ---- the cause: nothing told programs synchronized output is available ---------------------------

    /**
     * Programs that probe for synchronized output (Claude Code, neovim, helix) draw each frame between
     * `?2026h` and `l` only if told it is supported. Termux answered "mode not recognised" (value 0), so
     * they never did, and every repaint was shown as it arrived.
     */
    @Test
    fun theEmulatorSaysSynchronizedOutputIsSupported() {
        val replies = StringBuilder()
        val s = TerminalScreen(10, 40, reply = { replies.append(String(it)) })
        s.write("\u001b[?2026\$p".toByteArray())
        assertEquals("\u001b[?2026;2\$y", replies.toString())
        assertEquals("the query is not text", "", s.tailText(10).trim())
        replies.clear()
        s.write("\u001b[?2026h\u001b[?2026\$p".toByteArray())
        assertEquals("while a frame is open it reads as set", "\u001b[?2026;1\$y", replies.toString())
        replies.clear()
        s.write("\u001b[?2026l\u001b[?2026\$p".toByteArray())
        assertEquals("\u001b[?2026;2\$y", replies.toString())
    }

    @Test
    fun theProbeCutByTheChunkEndIsStillAnswered() {
        val bytes = "ab\u001b[?2026\$pcd".toByteArray()
        for (cut in 1 until bytes.size) {
            val replies = StringBuilder()
            val s = TerminalScreen(5, 20, reply = { replies.append(String(it)) })
            s.write(bytes.copyOfRange(0, cut))
            s.write(bytes.copyOfRange(cut, bytes.size))
            assertEquals("cut at $cut", "\u001b[?2026;2\$y", replies.toString())
            assertEquals("cut at $cut", "abcd", s.tailText(5).trim())
        }
    }

    @Test
    fun replayedHistoryIsNotAnsweredAgain() {
        val replies = StringBuilder()
        val s = TerminalScreen(5, 20, reply = { replies.append(String(it)) })
        s.setReplaying(true)
        s.write("\u001b[?2026\$p".toByteArray())
        assertEquals("", replies.toString())
    }

    // ---- the cause: a repaint split by the pump is shown half done ---------------------------------

    @Test
    fun legacyPacingShowsHalfDrawnScreens() {
        val legacy = measure { TerminalFramePacer.legacy() }
        report("legacy", legacy)
        assertTrue("ink: ${legacy.values.first()}", legacy.values.first().partial > 0)
        assertTrue("curses: ${legacy.values.drop(1).first()}", legacy.values.drop(1).first().partial > 0)
        assertEquals("a synchronized frame is never shown half done", 0, legacy.values.drop(2).sumOf { it.partial })
    }

    @Test
    fun settledPacingNeverShowsAHalfDrawnScreenAndPresentsOncePerFrame() {
        val legacy = measure { TerminalFramePacer.legacy() }
        val now = measure { TerminalFramePacer() }
        report("settled", now)
        for ((name, r) in now) {
            assertEquals("$name: $r", 0, r.partial)
            assertEquals("$name: $r", 0, r.redundant)
            assertEquals("$name: one draw per program frame, not per chunk: $r", frames, r.presented)
            assertTrue("$name: the wait stays small: $r", r.maxLatencyMs <= 50.0 + 2 * FlickerSim.VSYNC_MS)
            assertTrue("$name: no more draws than before", r.presented <= legacy.getValue(name).presented)
        }
    }

    // ---- the pacer ---------------------------------------------------------------------------------------

    @Test
    fun aLoneChunkIsShownAfterTheSettleTimeAndNotBefore() {
        val p = TerminalFramePacer()
        assertFalse(p.due(0))
        p.changed(100)
        assertFalse("too soon: more of the repaint may follow", p.due(105))
        assertTrue(p.due(100 + TerminalFramePacer.SETTLE_MS))
        assertFalse("consumed", p.due(200))
        assertFalse(p.pending)
    }

    @Test
    fun moreOutputExtendsTheWaitButNeverPastTheMaxHold() {
        val p = TerminalFramePacer()
        p.changed(0)
        var t = 5L
        while (t < TerminalFramePacer.MAX_HOLD_MS) {
            p.changed(t)
            assertFalse("still arriving at $t", p.due(t + 1))
            t += 5
        }
        assertTrue("a stream that never pauses still gets frames", p.due(TerminalFramePacer.MAX_HOLD_MS))
    }

    @Test
    fun aFinishedSynchronizedFrameIsShownAtOnce() {
        val p = TerminalFramePacer()
        p.changed(100, settled = true)
        assertTrue(p.due(101))
    }

    @Test
    fun theLegacyPacerDrawsAtTheFirstVsync() {
        val p = TerminalFramePacer.legacy()
        p.changed(100)
        assertTrue(p.due(100))
    }

    // ---- what the screen tells the view -------------------------------------------------------------

    private class Counting : TerminalScreen.Listener {
        val calls = ArrayList<Boolean>()
        override fun onScreenChanged(settled: Boolean) { calls += settled }
        override fun onSnapshot() = Unit
        override fun onBell() = Unit
    }

    @Test
    fun aHeldFrameDoesNotAskForADrawUntilItIsComplete() {
        val s = TerminalScreen(5, 20, reply = {})
        val l = Counting().also { s.listener = it }
        s.write("\u001b[?2026hhello".toByteArray())
        s.write(" world".toByteArray())
        assertEquals("nothing on screen changed, nothing to draw", emptyList<Boolean>(), l.calls)
        s.write("\u001b[?2026l".toByteArray())
        assertEquals("the end of the frame is settled", listOf(true), l.calls)
        assertEquals("hello world", s.tailText(5).trim())
    }

    @Test
    fun plainOutputIsNotSettledAndTheSafetyFlushIs() {
        val s = TerminalScreen(5, 20, reply = {})
        val l = Counting().also { s.listener = it }
        s.write("abc".toByteArray())
        assertEquals(listOf(false), l.calls)
        s.write("\u001b[?2026hhalf".toByteArray())
        val deadline = System.currentTimeMillis() + 3000
        while (l.calls.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("a frame that never ends is shown after a moment", listOf(false, true), l.calls)
        assertEquals("abchalf", s.tailText(5).trim())
    }
}
