/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

/**
 * Data-channel liveness (mirrors the desktop's PEER_PROBE_AFTER / PEER_DEAD_AFTER), ticked once a
 * second. Times are monotonic milliseconds.
 *
 * A frozen process (ColorOS freezes DeskLink with the screen off) resumes with the tick firing
 * before the data that arrived meanwhile is delivered; measured from before the freeze, that
 * silence would declare a healthy desk dead. So a tick that comes [FROZEN_GAP_MS] after the last
 * one restarts the silence clock instead.
 */
class PeerLiveness(private val probeAfterMs: Long, private val deadAfterMs: Long) {
    enum class Verdict { OK, PROBE, DEAD }

    private var lastInboundAt = 0L
    private var lastProbeAt = 0L
    private var lastTickAt = 0L
    /** Only real inbound data; the freeze reset never moves it. */
    private var lastHeardAt = 0L

    @Synchronized
    fun start(now: Long) {
        lastInboundAt = now
        lastProbeAt = now
        lastTickAt = now
    }

    @Synchronized
    fun onInbound(now: Long) {
        lastInboundAt = now
        lastHeardAt = now
    }

    /** Whether anything really arrived at or after [since] (the screen-on probe's answer). */
    @Synchronized
    fun heardSince(since: Long): Boolean = lastHeardAt >= since

    @Synchronized
    fun tick(now: Long): Verdict {
        if (now - lastTickAt > FROZEN_GAP_MS) {
            lastInboundAt = now
            lastProbeAt = now
        }
        lastTickAt = now
        val silence = now - lastInboundAt
        return when {
            silence >= deadAfterMs -> Verdict.DEAD
            silence >= probeAfterMs && now - lastProbeAt >= probeAfterMs -> {
                lastProbeAt = now
                Verdict.PROBE
            }
            else -> Verdict.OK
        }
    }

    companion object {
        const val FROZEN_GAP_MS = 3_000L
    }
}
