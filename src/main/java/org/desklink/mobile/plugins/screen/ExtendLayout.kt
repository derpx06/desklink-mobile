/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.screen

/**
 * Pure decisions for the desk screen as a second display (Extend). The computer adds a virtual monitor the size of
 * what this phone shows, so the request waits for a view size that has settled: past the gate, the rotation, and the
 * system bars and cutout insets (all of which change the first measurement). Landscape is requested, but Android 16
 * ignores that on screens of 600 dp and more (tablets, unfolded foldables, multi-window), so a view that stays portrait
 * is asked for as it is: the monitor then matches what is visible.
 */
object ExtendLayout {
    /** How long to wait for a landscape, settled, bars-hidden view before asking with what there is. */
    const val AREA_WAIT_MS = 2_000L

    /** The size must have been unchanged this long (a few frames) to be taken as the visible area. */
    const val AREA_STABLE_MS = 250L

    private const val POLL_MS = 50L

    /** A measured view size and when (uptime ms) it last changed. */
    data class Measured(val area: Pair<Int, Int>, val atMs: Long)

    /** What to ask the computer for: [area] null means "no size known, let it pick". */
    data class Ask(val area: Pair<Int, Int>?)

    /** Ask now, or null to keep waiting. */
    fun decide(measured: Measured?, nowMs: Long, waitedMs: Long, barsHidden: Boolean): Ask? {
        val timedOut = waitedMs >= AREA_WAIT_MS
        if (measured == null) return if (timedOut) Ask(null) else null
        val (w, h) = measured.area
        val settled = nowMs - measured.atMs >= AREA_STABLE_MS
        return when {
            settled && barsHidden && w > h -> Ask(measured.area)
            // Still portrait (the lock was ignored) or the bars never reported hidden: what is visible is what is asked for.
            timedOut && settled -> Ask(measured.area)
            // Never settles (something keeps resizing): do not wait for ever.
            waitedMs >= 2 * AREA_WAIT_MS -> Ask(measured.area)
            else -> null
        }
    }

    /** [decide] in a loop on an injected clock and pause, so the wait is testable without real time. */
    suspend fun awaitAsk(
        startMs: Long,
        now: () -> Long,
        measured: () -> Measured?,
        barsHidden: () -> Boolean,
        pause: suspend (Long) -> Unit,
    ): Ask {
        while (true) {
            val t = now()
            decide(measured(), t, t - startMs, barsHidden())?.let { return it }
            pause(POLL_MS)
        }
    }

    /** The visible view changed from what the computer was last asked for: ask again (a fold, multi-window, a late inset). */
    fun needsReask(measured: Pair<Int, Int>?, sent: Pair<Int, Int>?): Boolean = measured != null && measured != sent
}
