/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.os.SystemClock
import android.util.Log

/**
 * At most [max] events per [windowMs] for each key (a packet type, a desk…). Beyond that events are
 * dropped and counted: one log line when dropping starts and one with the count when the window
 * ends, so a flooding peer cannot flood the log either. Keys come from code, never from a peer, so
 * the map stays small.
 */
class RateLimiter(
    private val max: Int,
    private val windowMs: Long,
    private val tag: String,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Window(var start: Long, var count: Int = 0, var dropped: Int = 0)

    private val windows = HashMap<String, Window>()

    @Synchronized
    fun allow(key: String): Boolean {
        val now = clock()
        val window = windows.getOrPut(key) { Window(now) }
        if (now - window.start >= windowMs) {
            if (window.dropped > 0) runCatching { Log.w(tag, "$key: dropped ${window.dropped} over $max per $windowMs ms") }
            window.start = now
            window.count = 0
            window.dropped = 0
        }
        if (window.count < max) {
            window.count++
            return true
        }
        if (window.dropped++ == 0) runCatching { Log.w(tag, "$key: over $max per $windowMs ms, dropping") }
        return false
    }
}
