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
    fun aCutLinkReadsWaitingForTheComputerAndCancelledOnesGo() {
        val desk = "22222222-aaaa-bbbb-cccc-222222222222"
        TransferProgress.update(desk, 7L, true, "a.bin", 30, 1_000_000)
        TransferProgress.resuming(7L)
        // (The same second: the throttle holds the post; the state is what the next one carries.)
        TransferProgress.cancel(7L)
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
    fun theTapOpensTheFilesTabAndUpdatesAreAtMostOneASecond() {
        val source = java.io.File("src/main/java/org/desklink/mobile/plugins/share/TransferNotification.kt").readText()
        assertTrue(source.contains("EXTRA_OPEN_FILES, true"))
        assertEquals(1_000L, TransferNotification.UPDATE_EVERY_MS)
        assertTrue(java.io.File("src/main/java/org/desklink/mobile/ui/MainActivity.kt").readText().contains("intent.getBooleanExtra(EXTRA_OPEN_FILES, false)"))
        assertFalse("the Bucket posts through the one notification", java.io.File("src/main/java/org/desklink/mobile/plugins/folders/BucketSyncNotification.kt").readText().contains("notify("))
    }
}

/** Every live thing the phone does for a computer has one ongoing notification titled DeskLink, with a way to stop it. */
class LiveNotificationsTest {
    private fun source(path: String) = java.io.File("src/main/java/org/desklink/mobile/$path").readText()

    @Test
    fun theDeskScreenTheMicrophoneAndSoundTheScreenShareAndGlideAreAllOngoingDeskLinkNoticesWithAStop() {
        for ((file, stop) in listOf(
            "plugins/screen/SessionLiveNotification.kt" to "active_session_end", "plugins/audio/PhoneAudioService.kt" to "audio_stop",
            "plugins/screen/PhoneScreenCaptureService.kt" to "screen_share_stop",
        )) {
            val text = source(file)
            assertTrue("$file: titled DeskLink", text.contains("setContentTitle(") && text.contains("R.string.transfer_note_title"))
            assertTrue("$file: ongoing", text.contains("setOngoing(true)"))
            assertTrue("$file: a stop action", text.contains(stop))
        }
        assertTrue("glide is a line of the desk-screen notice", source("plugins/screen/SessionLiveNotification.kt").contains("session_live_glide"))
    }
}
