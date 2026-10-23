/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import android.os.SystemClock
import android.util.Log

/**
 * A packet an authenticated peer may send, which this phone does not take right now (no lease, the glide switch is off,
 * the service is gone...). It is dropped: only a packet that proves a bad peer (a bad signature, a replayed envelope, the
 * wrong channel) may end the link.
 */
class InboundNotAllowed(message: String) : IllegalArgumentException(message)

/** One log line every few seconds with a count, the reason's class and key only (never a name or content). */
class DropLog(
    private val tag: String,
    private val everyMs: Long = 5_000,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val sink: (String) -> Unit = { Log.i(tag, it) },
) {
    private var count = 0
    private var lastLogAt = Long.MIN_VALUE

    @Synchronized
    fun dropped(reason: String) {
        count++
        val t = now()
        if (lastLogAt == Long.MIN_VALUE || t - lastLogAt >= everyMs) {
            sink("dropped $count inbound packet(s) not allowed ($reason)")
            count = 0
            lastLogAt = t
        }
    }
}
