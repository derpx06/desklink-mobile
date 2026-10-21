/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.mpris

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
}
