/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

/** Coalesces native WebRTC failure callbacks and applies bounded recovery. */
class WebRtcRecoveryPolicy {
    private var nextAttempt = 0
    private var scheduled = false

    @Synchronized
    fun claimDelayMillis(): Long? {
        if (scheduled) return null
        val delay = DELAYS_MILLIS[nextAttempt.coerceAtMost(DELAYS_MILLIS.lastIndex)]
        nextAttempt++
        scheduled = true
        return delay
    }

    @Synchronized
    fun release() {
        scheduled = false
    }

    @Synchronized
    fun reset() {
        nextAttempt = 0
        scheduled = false
    }

    @Synchronized
    fun attempts(): Int = nextAttempt

    companion object {
        private val DELAYS_MILLIS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
    }
}
