/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.mpris

import android.support.v4.media.session.PlaybackStateCompat

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

    /** The media session's playback state: what Android draws in the shade, on the lock screen and in Quick Settings. */
    data class Transport(val state: Int, val positionMs: Long, val speed: Float, val actions: Long)

    /**
     * The desk's player as a media session. Playing advances by itself (speed 1 from [positionMs]);
     * stale (no feature link) is paused with no controls, as the Home card shows it. Seeking needs
     * the desk's canSeek and a known length.
     */
    fun transport(
        playing: Boolean, stale: Boolean, lastPositionMs: Long, lastPositionAt: Long, lengthMs: Long, now: Long,
        canPlay: Boolean, canPause: Boolean, canPrevious: Boolean, canNext: Boolean, canSeek: Boolean,
    ): Transport {
        val live = playing && !stale
        var actions = 0L
        if (!stale) {
            if (canPrevious) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            if (live && canPause) actions = actions or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE
            if (!live && canPlay) actions = actions or PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PLAY_PAUSE
            if (canNext) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            if (canSeek && lengthMs > 0) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        }
        return Transport(
            state = if (live) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
            positionMs = position(lastPositionMs, lastPositionAt, playing, lengthMs, now),
            speed = if (live) 1f else 0f,
            actions = actions,
        )
    }

    /** The notification's second line: "Artist — Album", whichever the desk sent, else [fallback]. */
    fun notificationText(artist: String, album: String, fallback: String): String =
        listOf(artist, album).filter(String::isNotBlank).joinToString(" — ").ifEmpty { fallback }
}
