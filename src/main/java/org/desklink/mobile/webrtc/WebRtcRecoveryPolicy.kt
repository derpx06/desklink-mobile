/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

/**
 * Coalesces native WebRTC failure callbacks and applies bounded recovery (W2).
 *
 * Reaching feature-ready does not reset the ladder: a link that flaps every few seconds would
 * otherwise redial at 250 ms forever. It resets only after [HEALTHY_RESET_MS] of healthy
 * feature-ready ([markReady], checked at the next failure), or by an explicit [reset] for the events
 * that mean "start over": a network change, the phone waking, a new or adopted bootstrap link, an
 * inbound offer, the peer restarting. The phone never gives up on attempts; after the last rung it
 * reports [unavailable] so the UI can say so, and keeps trying.
 */
class WebRtcRecoveryPolicy(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var nextAttempt = 0
    private var scheduled = false
    private var readySince: Long? = null

    @Synchronized
    fun claimDelayMillis(): Long? {
        if (scheduled) return null
        val now = clock()
        if (readySince?.let { now - it >= HEALTHY_RESET_MS } == true) nextAttempt = 0
        readySince = null
        val delay = DELAYS_MILLIS[nextAttempt.coerceAtMost(DELAYS_MILLIS.lastIndex)]
        nextAttempt++
        scheduled = true
        return delay
    }

    /** Feature-ready was reached now; 60 s later, with no failure in between, the next failure starts at the first rung. */
    @Synchronized
    fun markReady() {
        readySince = clock()
    }

    @Synchronized
    fun release() {
        scheduled = false
    }

    @Synchronized
    fun reset() {
        nextAttempt = 0
        scheduled = false
        readySince = null
    }

    @Synchronized
    fun attempts(): Int = nextAttempt

    /** Every rung has been tried and a further attempt is under way or due: the explicit "unavailable" state. */
    @Synchronized
    fun unavailable(): Boolean = nextAttempt > DELAYS_MILLIS.size

    companion object {
        /** Six rungs, in lockstep with the desk (contract B3). The phone never gives up on attempts. */
        internal val DELAYS_MILLIS = longArrayOf(250, 1_000, 2_000, 4_000, 8_000, 16_000)

        /** Healthy feature-ready time after which the ladder starts over. */
        const val HEALTHY_RESET_MS = 60_000L
    }
}
