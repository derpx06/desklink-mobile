/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.mpris

/** The pure rules behind the media card and player sheet; unit-tested. */
object MediaControl {
    /** An optimistic play/pause shows this long at most without the desk confirming it. */
    const val PENDING_MS = 2_000L

    /**
     * Which of the desk's players a tap goes to: the one the person picked while it still exists,
     * else the playing one, else the first. Null with no list yet: the request then names no
     * player and the desk picks its playing (or first) one.
     */
    fun pickPlayer(remembered: String?, playerList: List<String>, playing: String?): String? = when {
        remembered != null && remembered in playerList -> remembered
        playing != null && playing in playerList -> playing
        else -> playerList.firstOrNull()
    }

    /** Where the track is now: the last reported position, advanced while playing, within the track. */
    fun position(lastPositionMs: Long, lastPositionAt: Long, playing: Boolean, lengthMs: Long, now: Long): Long {
        val advanced = if (playing) lastPositionMs + (now - lastPositionAt).coerceAtLeast(0) else lastPositionMs
        return if (lengthMs > 0) advanced.coerceIn(0, lengthMs) else advanced.coerceAtLeast(0)
    }

    /** What the play button shows: the tapped state until the desk answers or [PENDING_MS] passes. */
    fun shownPlaying(reported: Boolean, pending: Boolean?, pendingSince: Long, now: Long): Boolean =
        if (pending != null && now - pendingSince < PENDING_MS) pending else reported

    /** A tap the desk has not confirmed yet: the button shows it, dimmed. */
    fun isPending(reported: Boolean, pending: Boolean?, pendingSince: Long, now: Long): Boolean =
        pending != null && pending != reported && now - pendingSince < PENDING_MS

    /** "3:07", or "1:02:03" for an hour and more. */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
