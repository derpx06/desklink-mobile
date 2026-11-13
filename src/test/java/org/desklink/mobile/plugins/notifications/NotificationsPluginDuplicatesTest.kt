/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

import android.app.Application
import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What reaches the desk once, and what never reaches it: group summaries, ongoing items, and a signature that ignores post times. */
@RunWith(RobolectricTestRunner::class)
class NotificationsPluginDuplicatesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun plugin(): NotificationsPlugin {
        val device = mockk<Device>(relaxed = true) { every { deviceId } returns "desk-dupes-1" }
        return NotificationsPlugin().apply { setContext(app, device) }
    }

    private fun sbn(pkg: String = "com.example", flags: Int = 0) = StatusBarNotification(
        pkg, pkg, 1, null, 1000, 1, 0, Notification().apply { this.flags = flags }, Process.myUserHandle(), 0L,
    )

    @Test
    fun groupSummariesOngoingForegroundAndOurOwnAreNeverMirrored() {
        val p = plugin()
        assertTrue("WhatsApp's \"3 new messages\" summary", p.neverMirrored(sbn(flags = NotificationCompat.FLAG_GROUP_SUMMARY)))
        assertTrue("a download or a player", p.neverMirrored(sbn(flags = Notification.FLAG_ONGOING_EVENT)))
        assertTrue("a foreground service", p.neverMirrored(sbn(flags = Notification.FLAG_FOREGROUND_SERVICE)))
        assertTrue(p.neverMirrored(sbn(pkg = "org.desklink.mobile")))
        assertTrue(p.neverMirrored(sbn(pkg = "org.desklink.mobile.debug")))
        assertFalse("a message itself", p.neverMirrored(sbn()))
    }

    private fun packet(text: String, time: String, silent: Boolean) = NetworkPacket("desklink.notification").apply {
        this["id"] = "k"
        this["title"] = "Mara"
        this["text"] = text
        this["time"] = time
        this["silent"] = silent
    }

    @Test
    fun theSignatureIgnoresPostTimeAndTheSilentFlagButNotTheWords() {
        val first = NotificationsPlugin.contentSignature(packet("hi", "1", false))
        assertEquals("a re-post a minute later, or sent again as a resync", first, NotificationsPlugin.contentSignature(packet("hi", "60000", true)))
        assertNotEquals(first, NotificationsPlugin.contentSignature(packet("hi again", "1", false)))
    }
}
