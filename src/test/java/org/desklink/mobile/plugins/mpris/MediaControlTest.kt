/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.mpris

import android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE
import android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY
import android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY_PAUSE
import android.support.v4.media.session.PlaybackStateCompat.ACTION_SEEK_TO
import android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_NEXT
import android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
import android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
import android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
import org.desklink.mobile.plugins.mpris.MediaControl.PENDING_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaControlTest {
    @Test
    fun theRememberedPlayerWinsOnlyWhileTheDeskListsIt() {
        val list = listOf("Firefox", "Spotify")
        assertEquals("Spotify", MediaControl.pickPlayer("Spotify", list, playing = "Firefox"))
        // Gone after a reconnect (renamed, closed): the playing one, then the first.
        assertEquals("Firefox", MediaControl.pickPlayer("vlc", list, playing = "Firefox"))
        assertEquals("Firefox", MediaControl.pickPlayer("vlc", list, playing = null))
        assertEquals("Firefox", MediaControl.pickPlayer(null, list, playing = "stale"))
        // No list yet: no name, and the desk picks.
        assertNull(MediaControl.pickPlayer("Spotify", emptyList(), playing = null))
    }

    @Test
    fun thePositionAdvancesOnlyWhilePlayingAndStaysInTheTrack() {
        assertEquals(12_000L, MediaControl.position(10_000, lastPositionAt = 1_000, playing = true, lengthMs = 200_000, now = 3_000))
        assertEquals(10_000L, MediaControl.position(10_000, lastPositionAt = 1_000, playing = false, lengthMs = 200_000, now = 3_000))
        assertEquals(200_000L, MediaControl.position(199_000, lastPositionAt = 0, playing = true, lengthMs = 200_000, now = 5_000))
        // A clock that went back never rewinds the track.
        assertEquals(10_000L, MediaControl.position(10_000, lastPositionAt = 5_000, playing = true, lengthMs = 200_000, now = 4_000))
        // Unknown length: no upper bound.
        assertEquals(15_000L, MediaControl.position(10_000, lastPositionAt = 0, playing = true, lengthMs = -1, now = 5_000))
    }

    @Test
    fun aTapShowsAtOnceAndIsConfirmedByTheDesk() {
        // Playing; tapped Pause at t=100.
        assertFalse(MediaControl.shownPlaying(reported = true, pending = false, pendingSince = 100, now = 150))
        assertTrue(MediaControl.isPending(reported = true, pending = false, pendingSince = 100, now = 150))
        // The desk reports paused: the plugin clears pending, nothing flips.
        assertFalse(MediaControl.shownPlaying(reported = false, pending = null, pendingSince = 100, now = 400))
        assertFalse(MediaControl.isPending(reported = false, pending = false, pendingSince = 100, now = 400))
    }

    @Test
    fun anUnconfirmedTapRevertsAfterTwoSeconds() {
        assertFalse(MediaControl.shownPlaying(reported = true, pending = false, pendingSince = 100, now = 100 + PENDING_MS - 1))
        assertTrue(MediaControl.shownPlaying(reported = true, pending = false, pendingSince = 100, now = 100 + PENDING_MS))
        assertFalse(MediaControl.isPending(reported = true, pending = false, pendingSince = 100, now = 100 + PENDING_MS))
    }

    @Test
    fun clocksReadLikeAPlayer() {
        assertEquals("0:00", MediaControl.clock(-5))
        assertEquals("3:07", MediaControl.clock(187_400))
        assertEquals("1:02:03", MediaControl.clock(3_723_000))
    }

    private fun transport(
        playing: Boolean = true, stale: Boolean = false, lengthMs: Long = 200_000,
        canPlay: Boolean = true, canPause: Boolean = true, canPrevious: Boolean = true, canNext: Boolean = true, canSeek: Boolean = true,
    ) = MediaControl.transport(playing, stale, lastPositionMs = 10_000, lastPositionAt = 1_000, lengthMs = lengthMs, now = 4_000,
        canPlay = canPlay, canPause = canPause, canPrevious = canPrevious, canNext = canNext, canSeek = canSeek)

    @Test
    fun aPlayingTrackAdvancesByItselfFromWhereItIsNow() {
        val t = transport()
        assertEquals(STATE_PLAYING, t.state)
        assertEquals(13_000L, t.positionMs)
        assertEquals(1f, t.speed)
        assertEquals(ACTION_SKIP_TO_PREVIOUS or ACTION_PAUSE or ACTION_PLAY_PAUSE or ACTION_SKIP_TO_NEXT or ACTION_SEEK_TO, t.actions)
    }

    @Test
    fun aPausedTrackStandsStillAndOffersPlay() {
        val t = transport(playing = false)
        assertEquals(STATE_PAUSED, t.state)
        assertEquals(10_000L, t.positionMs)
        assertEquals(0f, t.speed)
        assertEquals(ACTION_PLAY, t.actions and (ACTION_PLAY or ACTION_PAUSE))
    }

    @Test
    fun theDesksCanFlagsDecideTheControls() {
        assertEquals(0L, transport(canSeek = false).actions and ACTION_SEEK_TO)
        // No length, no seek bar to drag.
        assertEquals(0L, transport(lengthMs = -1).actions and ACTION_SEEK_TO)
        assertEquals(0L, transport(canNext = false, canPrevious = false).actions and (ACTION_SKIP_TO_NEXT or ACTION_SKIP_TO_PREVIOUS))
        assertEquals(0L, transport(canPause = false).actions and (ACTION_PAUSE or ACTION_PLAY_PAUSE))
    }

    @Test
    fun aStaleReportIsPausedWithNoControls() {
        val t = transport(stale = true)
        assertEquals(STATE_PAUSED, t.state)
        assertEquals(0f, t.speed)
        assertEquals(0L, t.actions)
        // Frozen where the desk's player would be now, not snapped back to its last report.
        assertEquals(13_000L, t.positionMs)
    }

    @Test
    fun theSecondLineIsArtistAndAlbumWhicheverTheDeskSent() {
        assertEquals("M83 — Hurry Up", MediaControl.notificationText("M83", "Hurry Up", "Spotify"))
        assertEquals("M83", MediaControl.notificationText("M83", " ", "Spotify"))
        assertEquals("Hurry Up", MediaControl.notificationText("", "Hurry Up", "Spotify"))
        assertEquals("Spotify", MediaControl.notificationText("", "", "Spotify"))
    }
}
