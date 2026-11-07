/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

/**
 * Decides which vsync may show the screen. Pure and clock-free (times are passed in), so a test can
 * replay a recorded burst timeline.
 *
 * Why: a program repaints in one `write(2)` (erase the old rows, draw the new ones), but the desktop reads the
 * PTY 4 KiB at a time and sends what has accumulated every 8 ms, so one repaint can reach the phone as
 * two chunks several milliseconds apart. A vsync between them shows the erased rows with nothing drawn
 * over them: that is the flicker. Programs that wrap a frame in synchronized output (`?2026`) are
 * handled by [TerminalScreen]; this covers everything else, the way `st` does: after a change wait
 * [settleMs] for more, but never longer than [maxHoldMs] since the first change.
 *
 * Not thread-safe: the owner calls it from one thread (the UI thread).
 */
class TerminalFramePacer(val settleMs: Long = SETTLE_MS, val maxHoldMs: Long = MAX_HOLD_MS) {
    private var first = NONE
    private var last = NONE
    private var complete = false

    /** Whether a change is waiting to be shown. */
    val pending: Boolean get() = first != NONE

    /** Output changed the screen at [nowMs]. [settled]: a whole frame is known to be in (e.g. the end of a synchronized update). */
    fun changed(nowMs: Long, settled: Boolean = false) {
        if (first == NONE) first = nowMs
        last = nowMs
        if (settled) complete = true
    }

    /** At a vsync at [nowMs]: whether to draw now. Drawing consumes the pending change. */
    fun due(nowMs: Long): Boolean {
        if (first == NONE) return false
        if (!complete && nowMs - last < settleMs && nowMs - first < maxHoldMs) return false
        first = NONE
        last = NONE
        complete = false
        return true
    }

    companion object {
        /** Longer than the desktop's 8 ms pump interval, so a repaint split by one pump tick is waited for. */
        const val SETTLE_MS = 12L

        /** A stream that never pauses (a build log) still gets a frame about every 3 vsyncs. */
        const val MAX_HOLD_MS = 50L

        /** What the view did before: draw at the first vsync after any change. */
        fun legacy() = TerminalFramePacer(0, 0)

        private const val NONE = -1L
    }
}
