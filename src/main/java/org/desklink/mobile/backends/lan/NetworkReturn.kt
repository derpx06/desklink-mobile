/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.backends.lan

import java.io.IOException

/**
 * The phone back on a network (F20). Android reports a network ~250 ms before its route works, so
 * the first dial fails at once with ENETUNREACH: that says nothing about the desk, so it is retried
 * over ~2 s instead of backing the address off. And the announce burst for the new network is never
 * eaten by the throttle that coalesces repeated refreshes.
 */
object NetworkReturn {
    /** Gaps between dials while there is no route yet: 2 s in all. */
    @JvmField
    val NO_ROUTE_RETRY_MS = longArrayOf(250, 250, 500, 1_000)

    /** Repeated discovery refreshes closer than this are one burst. */
    const val BURST_GAP_MS = 200L

    fun interface Attempt<T> {
        @Throws(IOException::class)
        fun run(): T
    }

    fun interface Sleep {
        fun sleep(millis: Long)
    }

    @JvmStatic
    fun burstAllowed(now: Long, last: Long, force: Boolean): Boolean = force || now >= last + BURST_GAP_MS

    @JvmStatic
    fun isNoRoute(error: Throwable): Boolean = generateSequence(error) { it.cause }.any { e ->
        e.message.orEmpty().let { "ENETUNREACH" in it || "Network is unreachable" in it }
    }

    /** Runs [attempt], again after each gap while it fails for want of a route. */
    @JvmStatic
    @Throws(IOException::class)
    fun <T> connect(sleep: Sleep, attempt: Attempt<T>): T {
        for (gap in NO_ROUTE_RETRY_MS) {
            try {
                return attempt.run()
            } catch (e: IOException) {
                if (!isNoRoute(e)) throw e
            }
            sleep.sleep(gap)
        }
        return attempt.run()
    }
}
