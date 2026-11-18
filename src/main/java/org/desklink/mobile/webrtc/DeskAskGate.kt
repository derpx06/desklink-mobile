/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

/**
 * When a RESPONDER phone (it waits for the desktop's offer because its id sorts higher) with a live LAN bootstrap
 * link may ask a silent desktop to offer, by sending the existing signed `restart_request` (QA4-17: the desktop's own
 * retries had run out and nobody retried). A state ask, never a command; the desktop, as initiator, may ignore it.
 *
 * Bounded, never a reconnect loop: nothing is asked while ready or negotiating, nothing before [SILENCE_MS] without a
 * signed message from the desktop, and the gap between asks starts at [BASE_GAP_MS] and doubles per ask that did not
 * end in a feature transport, up to [MAX_GAP_MS]. A feature transport, a fresh link and a network change reset it.
 * Phone-only numbers (no wire fact). Not thread-safe: the coordinator holds its lock around every call; the clock is
 * passed in.
 */
internal class DeskAskGate(now: Long) {
    private var lastHeardAt = now
    private var lastAskAt = Long.MIN_VALUE / 2
    private var streak = 0

    /** A verified signed message arrived from the desktop. */
    fun heard(now: Long) {
        lastHeardAt = now
    }

    /** Ready, a new link or a moved network: the silence counts from [now] and the back-off starts over. */
    fun reset(now: Long) {
        lastHeardAt = now
        lastAskAt = Long.MIN_VALUE / 2
        streak = 0
    }

    /** The gap the next ask must wait for. */
    fun gapMs(): Long = minOf(MAX_GAP_MS, BASE_GAP_MS shl (streak - 1).coerceIn(0, 4))

    /** Whether to ask now; true also records the ask (the next one waits [gapMs] longer). */
    fun tryAsk(responder: Boolean, hasActiveFeatureTransport: Boolean, negotiationInFlight: Boolean, now: Long): Boolean {
        if (!responder || hasActiveFeatureTransport || negotiationInFlight) return false
        if (now - lastHeardAt < SILENCE_MS || now - lastAskAt < gapMs()) return false
        lastAskAt = now
        streak++
        return true
    }

    companion object {
        /** Silence from the desktop's WebRTC side before the first ask. */
        const val SILENCE_MS = 20_000L
        const val BASE_GAP_MS = 30_000L
        const val MAX_GAP_MS = 300_000L
    }
}
