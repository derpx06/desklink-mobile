/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.desklink.mobile.plugins.share.TransferNotification.Action
import org.desklink.mobile.plugins.share.TransferNotification.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TransferNotificationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val size: (Long) -> String = { if (it >= 1_000_000) "${it / 100_000 / 10.0} MB" else "$it B" }
    private fun item(name: String, sending: Boolean, done: Long, total: Long, eta: Long? = null, bps: Long = 0, waiting: Boolean = false, bucket: Boolean = false, saving: Boolean = false) =
        Item(name, sending, done, total, eta, bps, waiting, saving, bucket)

    @Test
    fun oneFileSaysWhatHowFarHowFastAndHowLong() {
        val note = TransferNotification.note("Dell", listOf(item("dl-300m.bin", false, 135_000_000, 300_000_000, eta = 40, bps = 6_500_000)), 0, 0, false, false, size)!!
        assertEquals("Receiving dl-300m.bin · 45% · 6.5 MB/s · about 25 s left", note.text)
        assertEquals(0.45f, note.fraction, 0.001f)
        assertEquals(Action.CANCEL, note.action)
        assertFalse(note.indeterminate)
    }

    @Test
    fun theLaneIsNamedOnlyWhenEveryMovingFileIsOnTheSameOne() {
        val fast = item("a.bin", true, 10, 100, bps = 1_000_000).copy(lane = TransferNotification.LANE_FAST)
        val web = item("b.bin", true, 10, 100, bps = 1_000_000).copy(lane = TransferNotification.LANE_WEBRTC)
        assertTrue(TransferNotification.note("Dell", listOf(fast), 0, 0, false, false, size)!!.text.endsWith("fast lane"))
        assertTrue(TransferNotification.note("Dell", listOf(web), 0, 0, false, false, size)!!.text.endsWith("over WebRTC"))
        assertTrue(TransferNotification.note("Dell", listOf(item("c.bin", true, 10, 100).copy(lane = TransferNotification.LANE_RELAY)), 0, 0, false, false, size)!!.text.endsWith("through a relay"))
        val mixed = TransferNotification.note("Dell", listOf(fast, web), 0, 0, false, false, size)!!.text
        assertFalse(mixed, mixed.contains("lane") || mixed.contains("WebRTC"))
        assertFalse(TransferNotification.note("Dell", listOf(item("d.bin", true, 10, 100)), 0, 0, false, false, size)!!.text.contains("lane"))
    }

    @Test
    fun aSetSaysHowManyHowManyAreDoneAndTheWholeProgress() {
        val note = TransferNotification.note("Dell", listOf(item("c.bin", true, 20, 100)), finishedInSet = 2, finishedBytes = 200, userPaused = false, storageFull = false, size = size)!!
        assertEquals("Sending 3 files · 2 of 3 · 73%", note.text)
        val mixed = TransferNotification.note("Dell", listOf(item("a", true, 1, 10), item("b", false, 1, 10)), 0, 0, false, false, size)!!
        assertTrue(mixed.text.startsWith("Transferring 2 files · "))
    }

    @Test
    fun pausedStatesAreInWordsAndTheActionFitsWhatMoves() {
        val gone = TransferNotification.note("Dell", listOf(item("a", true, 1, 10, waiting = true)), 0, 0, false, false, size)!!
        assertEquals("Waiting for Dell", gone.text)
        val bucket = listOf(item("a", false, 1, 10, bucket = true))
        assertEquals(Action.PAUSE_SYNC, TransferNotification.note("Dell", bucket, 0, 0, false, false, size)!!.action)
        val paused = TransferNotification.note("Dell", bucket, 0, 0, userPaused = true, storageFull = false, size = size)!!
        assertEquals("Paused", paused.text); assertEquals(Action.RESUME_SYNC, paused.action)
        assertEquals("Storage is full", TransferNotification.note("Dell", bucket, 0, 0, false, storageFull = true, size = size)!!.text)
        assertEquals("a Bucket file beside a plain one is cancelled as a set", Action.CANCEL, TransferNotification.note("Dell", bucket + item("b", true, 1, 10), 0, 0, false, false, size)!!.action)
        val saving = TransferNotification.note("Dell", listOf(item("x.pdf", false, 10, 10, saving = true)), 0, 0, false, false, size)!!
        assertEquals("Saving x.pdf", saving.text); assertTrue(saving.indeterminate)
        assertNull(TransferNotification.note("Dell", emptyList(), 0, 0, false, false, size))
    }

    @org.junit.Before
    fun fresh() { TransferNotification.resetForTest(); androidx.core.app.NotificationManagerCompat.from(app).cancelAll() }

    private fun text(n: android.app.Notification) = n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()

    private fun later(ms: Long) = org.robolectric.shadows.ShadowLooper.idleMainLooper(ms, java.util.concurrent.TimeUnit.MILLISECONDS)

    private fun posted(): List<android.app.Notification> = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    @Test
    fun everythingMovingWithOneComputerIsOneNotificationTitledDeskLinkThatGoesWhenItIsDone() {
        val desk = "11111111-aaaa-bbbb-cccc-111111111111"
        TransferProgress.update(desk, 1L, false, "secret-plans.pdf", 10, 1_000_000)
        TransferProgress.update(desk, 2L, true, "holiday.jpg", 50, 2_000_000)
        val notes = posted()
        assertEquals("one for the computer, whatever is moving", 1, notes.size)
        val n = notes.single()
        assertEquals("DeskLink", n.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertTrue(n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("the lock screen sees no file name", "DeskLink · transferring files", n.publicVersion.extras.getString(android.app.Notification.EXTRA_TEXT))
        assertNotNull(n.contentIntent)
        assertEquals("Cancel", n.actions.single().title.toString())
        // Both end: the notification goes (the transfer's own finished notice takes over).
        TransferProgress.finish(1L, "secret-plans.pdf", false)
        TransferProgress.finish(2L, "holiday.jpg", false)
        assertEquals(0, posted().size)
    }

    @Test
    fun theOngoingNoticesOfTheOldJobsPostNothingSoThereIsOnlyTheOne() {
        val context = app
        val device = mockk<Device>(relaxed = true)
        every { device.context } returns context
        every { device.deviceId } returns "33333333-aaaa-bbbb-cccc-333333333333"
        val upload = UploadNotification(device, 5L)
        upload.setTitle("Sending a.bin"); upload.setProgress(40, "40%"); upload.show()
        assertEquals("ongoing: nothing of its own", 0, posted().size)
        upload.setFinished("Sent a.bin"); upload.show()
        assertEquals("finished: its notice", 1, posted().size)
        val receive = ReceiveNotification(device, 6L)
        receive.setTitle("Saving b.bin"); receive.show()
        assertEquals(1, posted().size)
    }

    @Test
    fun theUpdateHeldWithinTheSecondIsPostedWhenTheSecondIsOverAndNothingKeepsTickingAfter() {
        val desk = "44444444-aaaa-bbbb-cccc-444444444444"
        TransferProgress.update(desk, 1L, false, "a.bin", 10, 1_000_000)
        assertTrue(text(posted().single()).contains("10%"))
        TransferProgress.update(desk, 1L, false, "a.bin", 60, 1_000_000)
        assertTrue("held: the second is not over", text(posted().single()).contains("10%"))
        later(1_100)
        assertTrue("posted when it is: ${text(posted().single())}", text(posted().single()).contains("60%"))
        later(10_000)
        assertTrue("and no timer re-arms itself", shadowOf(android.os.Looper.getMainLooper()).nextScheduledTaskTime == java.time.Duration.ZERO)
    }

    @Test
    fun aCutLinkReadsWaitingForTheComputerCanBeSwipedAndIsReplacedByTheResumedSend() {
        val desk = "55555555-aaaa-bbbb-cccc-555555555555"
        TransferProgress.update(desk, 7L, true, "a.bin", 30, 1_000_000)
        TransferProgress.resuming(7L)
        later(1_100)
        val waiting = posted().single()
        assertTrue(text(waiting).startsWith("Waiting for "))
        assertEquals("not stuck on the shade", 0, waiting.flags and android.app.Notification.FLAG_ONGOING_EVENT)
        // The send resumes under another job id: the paused entry is replaced, not left behind.
        TransferProgress.update(desk, 99L, true, "a.bin", 40, 1_000_000)
        later(1_100)
        assertEquals(listOf(99L), TransferNotification.jobIds(desk))
        assertTrue(text(posted().single()).startsWith("Sending a.bin"))
        // A send that will never resume goes too.
        TransferProgress.resuming(99L)
        TransferProgress.dropResuming(desk)
        assertEquals(0, posted().size)
    }

    @Test
    fun cancelEndsTheSetItWasShownForAndAStaleNoticeDoesNothing() {
        val desk = "66666666-aaaa-bbbb-cccc-666666666666"
        TransferProgress.update(desk, 1L, false, "a.bin", 10, 1_000_000)
        val shown = shadowOf(posted().single().actions.single().actionIntent).savedIntent
        val set = shown.getIntExtra("set", -1)
        val receiver = TransferNotification.Receiver()
        receiver.onReceive(app, android.content.Intent(shown).putExtra("set", set + 5))
        assertEquals("another set's Cancel does nothing", listOf(1L), TransferNotification.jobIds(desk))
        receiver.onReceive(app, shown)
        assertEquals(emptyList<Long>(), TransferNotification.jobIds(desk))
        assertEquals(0, posted().size)
        // A late progress call of the cancelled transfer does not bring it back.
        TransferProgress.update(desk, 1L, false, "a.bin", 20, 1_000_000)
        assertEquals(0, posted().size)
    }

    @Test
    fun aReceiveCutByALostLinkComesBackUnderTheSameIdBecauseOnlyACancelTombstones() {
        val desk = "77777777-aaaa-bbbb-cccc-777777777777"
        TransferProgress.update(desk, 5L, false, "r.bin", 30, 1_000_000)
        assertEquals(listOf(5L), TransferNotification.jobIds(desk))
        TransferProgress.suspended(5L) // the link dropped; the same transfer id returns on resume
        assertEquals(emptyList<Long>(), TransferNotification.jobIds(desk))
        TransferProgress.update(desk, 5L, false, "r.bin", 40, 1_000_000)
        later(1_100)
        assertEquals("the resumed receive shows again", listOf(5L), TransferNotification.jobIds(desk))
        assertTrue(text(posted().single()).startsWith("Receiving r.bin"))
        TransferProgress.cancel(5L) // a real cancel is final
        TransferProgress.update(desk, 5L, false, "r.bin", 50, 1_000_000)
        assertEquals(emptyList<Long>(), TransferNotification.jobIds(desk))
    }

    @Test
    fun theLaneWordsComeFromTheResourcesAndAreForgottenWhenTheFileIsDone() {
        val words = TransferNotification.words(app)
        assertEquals("fast lane", words.laneFast); assertEquals("over WebRTC", words.laneWebRtc); assertEquals("through a relay", words.laneRelay)
        val desk = "88888888-aaaa-bbbb-cccc-888888888888"
        TransferNotification.lane(desk, "a.bin", true, TransferNotification.LANE_FAST)
        assertEquals("fast lane", TransferNotification.laneWord(app, desk, true, "a.bin"))
        assertNull("another direction or name is not that file", TransferNotification.laneWord(app, desk, false, "a.bin"))
        TransferNotification.lane(desk, "a.bin", true, null)
        assertNull(TransferNotification.laneWord(app, desk, true, "a.bin"))
    }

    @Test
    fun aPausedBucketOrAFullDiskIsTheBucketsNotAPlainFilesAndSavingHasNoButton() {
        val plain = listOf(item("a.bin", true, 1, 10))
        assertTrue(TransferNotification.note("Dell", plain, 0, 0, userPaused = true, storageFull = true, size = size)!!.text.startsWith("Sending a.bin"))
        val saving = TransferNotification.note("Dell", listOf(item("x.pdf", false, 10, 10, saving = true)), 0, 0, false, false, size)!!
        assertEquals(Action.NONE, saving.action)
    }

    @Test
    fun theTapOpensTheFilesTabOfThatComputerAndEachComputerHasItsOwnIntents() {
        val a = "77777777-aaaa-bbbb-cccc-777777777777"
        val b = "88888888-aaaa-bbbb-cccc-888888888888"
        TransferProgress.update(a, 1L, false, "a.bin", 10, 1_000_000)
        TransferProgress.update(b, 2L, true, "b.bin", 10, 1_000_000)
        val notes = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        assertEquals(2, notes.size)
        val opens = notes.map { shadowOf(it.contentIntent).savedIntent }
        assertTrue(opens.all { it.getBooleanExtra("openFiles", false) })
        assertEquals(setOf(a, b), opens.map { it.getStringExtra("deviceId") }.toSet())
        assertTrue("different intents even if the ids hash alike", opens[0].data != opens[1].data)
    }

    @Test
    fun whatAnEarlierProcessLeftOnTheShadeIsSweptAtStart() {
        val nm = app.getSystemService(NotificationManager::class.java)
        val left = NotificationCompat.Builder(app, "x").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("DeskLink").setOngoing(true).build()
        androidx.core.app.NotificationManagerCompat.from(app).notify("transfer:old", 7490, left)
        androidx.core.app.NotificationManagerCompat.from(app).notify("bucket-sync:old", 7490, left)
        androidx.core.app.NotificationManagerCompat.from(app).notify("other", 7490, left)
        TransferNotification.sweep(app)
        assertEquals(listOf("other"), shadowOf(nm).allNotifications.size.let { listOf("other").take(it) })
        assertNull(nm.activeNotifications.firstOrNull { it.tag == "transfer:old" })
        assertNull(nm.activeNotifications.firstOrNull { it.tag == "bucket-sync:old" })
    }
}
