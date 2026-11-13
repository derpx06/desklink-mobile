package org.desklink.mobile.plugins.mprisreceiver

import org.junit.Assert.assertEquals
import org.junit.Test

class MprisReceiverPlayerTest {
    /** The phone's reported position was the app's last update, not now: a playing track moves on from it. */
    @Test
    fun aPlayingTracksPositionMovesOnFromItsLastUpdate() {
        assertEquals(13_000L, MprisReceiverPlayer.positionNow(10_000, 1_000, 1f, true, 4_000, 200_000))
        assertEquals(10_000L, MprisReceiverPlayer.positionNow(10_000, 1_000, 1f, false, 4_000, 200_000))
        assertEquals(16_000L, MprisReceiverPlayer.positionNow(10_000, 1_000, 2f, true, 4_000, 200_000))
        assertEquals(200_000L, MprisReceiverPlayer.positionNow(199_000, 1_000, 1f, true, 9_000, 200_000))
        assertEquals(0L, MprisReceiverPlayer.positionNow(-1, 0, 1f, true, 9_000, 200_000)) // unknown
    }

    @Test
    fun aSeekStaysInsideTheTrack() {
        assertEquals(0L, MprisReceiverPlayer.clampSeek(-4_000, 200_000))
        assertEquals(200_000L, MprisReceiverPlayer.clampSeek(900_000, 200_000))
        assertEquals(900_000L, MprisReceiverPlayer.clampSeek(900_000, 0)) // length unknown
    }
}
