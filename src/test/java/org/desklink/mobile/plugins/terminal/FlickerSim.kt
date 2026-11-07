/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import java.util.Random

/**
 * Replays a timeline of program "frames" (one burst = one repaint the program wrote in one go)
 * through a [TerminalScreen] and a vsync loop, and counts what a person would see:
 *
 *  * **partial**: a presented screen that is not the screen after any whole burst (half-erased,
 *    half-drawn: the flicker);
 *  * **blank**: a presented screen with no text at all while whole frames always have some;
 *  * **redundant**: a presented screen identical to the one before it (a wasted full-view draw);
 *  * presents per second.
 *
 * The delivery model is the desktop's: the PTY is read 4 KiB at a time and the pump sends what has
 * accumulated every 8 ms, so a burst's pieces land in the same pump tick (back to back) or are
 * spread over the next ones, plus a little network jitter per tick. Vsync is 60 Hz. All of it is
 * seeded, so the numbers are repeatable.
 */
class FlickerSim(
    private val rows: Int = 40,
    private val cols: Int = 120,
    private val pacerFactory: () -> TerminalFramePacer,
    private val seed: Long = 42,
    private val readBytes: Int = 4096,
    private val pumpMs: Double = 8.0,
    private val sameTickChance: Double = 0.65,
    private val jitterMs: Double = 4.0,
    private val burstGapMs: Double = 100.0,
) {
    data class Result(val presented: Int, val partial: Int, val blank: Int, val redundant: Int, val seconds: Double, val bytes: Int, val examples: List<String>, val latencies: List<Double> = emptyList()) {
        val meanLatencyMs get() = if (latencies.isEmpty()) 0.0 else latencies.average()
        val maxLatencyMs get() = latencies.maxOrNull() ?: 0.0
        val presentsPerSecond get() = presented / seconds
        override fun toString() = "presented=$presented partial=$partial blank=$blank redundant=$redundant presents/s=%.1f latency(mean/max ms)=%.1f/%.1f bytes=$bytes".format(presentsPerSecond, meanLatencyMs, maxLatencyMs)
    }

    private class Write(val at: Double, val bytes: ByteArray)

    fun run(bursts: List<ByteArray>, prefix: ByteArray = ByteArray(0)): Result {
        val rng = Random(seed)

        // The whole frames, as a reference screen fed each burst in one piece.
        val reference = TerminalScreen(rows, cols, reply = {})
        val complete = HashSet<String>()
        val afterBurst = ArrayList<String>()
        if (prefix.isNotEmpty()) reference.write(prefix)
        complete += TerminalGrid.grid(reference)
        for (b in bursts) {
            reference.write(b)
            afterBurst += TerminalGrid.grid(reference)
            complete += afterBurst.last()
        }
        val burstEnd = DoubleArray(bursts.size)

        // The delivery timeline.
        val writes = ArrayList<Write>()
        if (prefix.isNotEmpty()) writes += Write(0.0, prefix)
        var burstAt = 20.0
        var bytes = prefix.size
        var burstIndex = 0
        for (b in bursts) {
            val pieces = b.toList().chunked(readBytes).map { it.toByteArray() }
            var tick = Math.floor(burstAt / pumpMs) * pumpMs + pumpMs
            var jitter = rng.nextDouble() * jitterMs
            var n = 0
            for ((i, piece) in pieces.withIndex()) {
                if (i > 0 && rng.nextDouble() > sameTickChance) {
                    tick += pumpMs
                    jitter = rng.nextDouble() * jitterMs
                }
                writes += Write(tick + jitter + 0.05 * n++, piece)
                burstEnd[burstIndex] = writes.last().at
            }
            burstIndex++
            bytes += b.size
            burstAt += burstGapMs * (0.6 + 0.8 * rng.nextDouble())
        }
        writes.sortBy { it.at }

        val screen = TerminalScreen(rows, cols, reply = {})
        val pacer = pacerFactory()
        var clock = 0.0
        screen.listener = object : TerminalScreen.Listener {
            override fun onScreenChanged(settled: Boolean) = pacer.changed(clock.toLong(), settled)
            override fun onSnapshot() = Unit
            override fun onBell() = Unit
        }
        if (prefix.isNotEmpty()) writes.firstOrNull()?.let { /* the prefix is the first write */ }

        var presented = 0
        var partial = 0
        var blank = 0
        var redundant = 0
        var previous: String? = null
        val examples = ArrayList<String>()
        val latencies = ArrayList<Double>()
        val shown = BooleanArray(bursts.size)
        var next = 0
        val end = (writes.lastOrNull()?.at ?: 0.0) + 200.0
        var v = VSYNC_MS
        while (v < end) {
            while (next < writes.size && writes[next].at <= v) {
                clock = writes[next].at
                screen.write(writes[next].bytes)
                next++
            }
            if (pacer.due(v.toLong())) {
                val grid = TerminalGrid.grid(screen)
                presented++
                if (grid == previous) redundant++
                if (grid !in complete) {
                    partial++
                    if (examples.size < 2) examples += screen.tailText(rows).lines().count { it.isNotBlank() }.toString() + " non-blank rows at t=${v.toInt()}"
                }
                if (screen.tailText(rows).isBlank() && complete.any { it.contains("|") }) blank++
                for (i in bursts.indices) if (!shown[i] && burstEnd[i] <= v && afterBurst[i] == grid) { shown[i] = true; latencies += v - burstEnd[i] }
                previous = grid
            }
            v += VSYNC_MS
        }
        return Result(presented, partial, blank, redundant, end / 1000.0, bytes, examples, latencies)
    }

    companion object {
        const val VSYNC_MS = 1000.0 / 60

        private const val ESC = "\u001b"

        private fun line(rng: Random, width: Int, tag: Int): String {
            val sb = StringBuilder()
            var used = 0
            while (used < width - 12) {
                val word = ("word$tag-" + rng.nextInt(100000)).take(6 + rng.nextInt(6))
                sb.append(ESC).append("[38;5;").append(30 + rng.nextInt(200)).append('m').append(word).append(' ')
                used += word.length + 1
            }
            sb.append(ESC).append("[0m")
            return sb.toString()
        }

        /**
         * Claude Code / Ink without synchronized output: erase the previous live region line by line
         * (`EL 2`, `CUU 1`), then write the new one. [lines] rows, [width] columns.
         */
        fun inkErase(frames: Int, lines: Int = 34, width: Int = 120, seed: Long = 1): List<ByteArray> {
            val rng = Random(seed)
            return (0 until frames).map { k ->
                val sb = StringBuilder()
                if (k > 0) {
                    repeat(lines) { i -> sb.append(ESC).append("[2K"); if (i < lines - 1) sb.append(ESC).append("[1A") }
                    sb.append(ESC).append("[G")
                }
                for (i in 0 until lines) {
                    sb.append("│ ").append(line(rng, width - 4, k)).append(" │")
                    if (i < lines - 1) sb.append("\r\n")
                }
                sb.toString().toByteArray()
            }
        }

        /** A curses-style refresh without sync: home, clear the whole screen, draw every row at its place. */
        fun clearRedraw(frames: Int, rows: Int = 40, width: Int = 120, seed: Long = 2): List<ByteArray> {
            val rng = Random(seed)
            return (0 until frames).map { k ->
                val sb = StringBuilder(ESC + "[H" + ESC + "[2J")
                for (r in 1..rows) sb.append(ESC).append('[').append(r).append(";1H").append(line(rng, width, k))
                sb.toString().toByteArray()
            }
        }

        /** The same repaint wrapped in synchronized output, as Codex (ratatui) and a probing Claude Code send it. */
        fun synced(frames: List<ByteArray>): List<ByteArray> =
            frames.map { (ESC + "[?2026h").toByteArray() + it + (ESC + "[?2026l").toByteArray() }
    }
}
