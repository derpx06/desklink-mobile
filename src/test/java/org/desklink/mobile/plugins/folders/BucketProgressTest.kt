/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BucketProgressTest {
    private val mb = 1_000_000L
    private fun size(b: Long) = when {
        b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
        else -> "${b / mb} MB"
    }

    @Test
    fun theLineSaysHowMuchAndHowLong() {
        assertEquals("340 MB of 1.2 GB · 2 min left", BucketProgress.line(Progress(340 * mb, 1_200 * mb, 125, 7 * mb), ::size))
        assertEquals("340 MB of 1.2 GB", BucketProgress.line(Progress(340 * mb, 1_200 * mb, null, 0), ::size))
        assertEquals("Waiting…", BucketProgress.line(Progress(0, 1_200 * mb, null, 0), ::size))
        assertEquals("Paused · 340 MB of 1.2 GB", BucketProgress.line(Progress(340 * mb, 1_200 * mb, 125, 0, paused = true), ::size))
        assertEquals("45 s left", BucketProgress.eta(45))
        assertEquals("4 min left", BucketProgress.eta(230))
        assertEquals("1 h 5 min left", BucketProgress.eta(3_900))
    }

    @Test
    fun theTimeLeftComesFromASmoothedSpeedAndHidesWhenUnsure() {
        val m = SpeedMeter(startedAt = 0)
        // 10 MB/s, steady.
        for (t in 1..10) m.update(t * 10 * mb / 4, t * 250L)
        assertNull("not in the first 2 s", SpeedMeter(0).apply { update(10 * mb, 1_000) }.etaSeconds(100 * mb, 1_000))
        val eta = m.etaSeconds(100 * mb, 2_500)!!
        assertTrue("about 10 s, got $eta", eta in 9..11)
        assertEquals(10 * mb, m.bytesPerSecond(2_500).let { (it + mb / 2) / mb * mb })
        // A stall: no bytes for more than 5 s hides it and reads 0 B/s.
        assertNull(m.etaSeconds(100 * mb, 2_500 + SpeedMeter.STALL_MS + 1))
        assertEquals(0L, m.bytesPerSecond(2_500 + SpeedMeter.STALL_MS + 1))
        // A sudden jump (unsteady) hides it until the average catches up.
        m.update(25 * mb + 200 * mb, 2_750)
        assertNull(m.etaSeconds(100 * mb, 2_750))
    }

    @Test
    fun theHeaderAddsUpEveryFileInFlight() {
        val s = BucketProgress.summary(
            listOf(
                Progress(400 * mb, 1_000 * mb, 60, 10 * mb),
                Progress(700 * mb, 1_000 * mb, 30, 10 * mb),
                Progress(0, 400 * mb, null, 0),
            ),
        )!!
        assertEquals(3, s.files)
        assertEquals(1_100 * mb, s.done)
        assertEquals(2_400 * mb, s.total)
        assertEquals(20 * mb, s.bytesPerSecond)
        assertEquals(65L, s.etaSeconds) // 1.3 GB left at 20 MB/s
        assertNull(BucketProgress.summary(emptyList()))
        assertNull("unknown while one moving file has no estimate", BucketProgress.summary(listOf(Progress(1, 10, null, 1)))!!.etaSeconds)
        assertTrue(BucketProgress.summary(listOf(Progress(1, 10, 5, 0, paused = true)))!!.paused)
    }

    @Test
    fun theNotificationSaysEverything() {
        val flights = listOf(
            BucketProgress.Flight("video.mp4", Progress(340 * mb, 1_200 * mb, 70, 12 * mb), sending = true),
            BucketProgress.Flight("b.pdf", Progress(0, 2 * mb, null, 0), sending = false),
        ) + (1..6).map { BucketProgress.Flight("w$it.jpg", Progress(0, mb, null, 0), sending = false) }
        val note = BucketProgress.note("desk", flights, linkDown = false, size = ::size)!!
        assertEquals("Syncing Bucket · 8 files", note.title)
        assertEquals("340 MB of 1.2 GB · 12 MB/s · about 1 min left", note.text)
        assertEquals("video.mp4 — 340 MB of 1.2 GB · 28%", note.lines.first())
        assertEquals("b.pdf — Waiting…", note.lines[1])
        assertEquals(BucketProgress.MAX_NOTE_LINES + 1, note.lines.size)
        assertEquals("+3 more", note.lines.last())
        assertEquals("Sending to and receiving from desk", note.subText)
        assertEquals("Paused · waiting for desk", BucketProgress.note("desk", flights, linkDown = true, size = ::size)!!.text)
        assertEquals("Receiving from desk", BucketProgress.note("desk", flights.drop(1), false, ::size)!!.subText)
        assertEquals("Bucket up to date · 3 files synced", BucketProgress.doneText(3))
        assertEquals("Bucket up to date · 1 file synced", BucketProgress.doneText(1))
        assertNull(BucketProgress.note("desk", emptyList(), false, ::size))
    }
}
