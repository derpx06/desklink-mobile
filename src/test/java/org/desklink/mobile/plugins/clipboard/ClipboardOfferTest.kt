/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.clipboard

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.helpers.NotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ClipboardOfferTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun theOfferIsOnlyMadeWhenNobodyIsLookingAndItIsNotTheComputersOwnOrASecretCopy() {
        assertTrue(ClipboardOffer.shouldOffer(appInFront = false, ownCopy = false, sensitive = false, computers = 1, automaticWatchRunning = false))
        assertFalse("in front it is read and synced at once", ClipboardOffer.shouldOffer(true, false, false, 1, false))
        assertFalse("the computer's own copy arriving", ClipboardOffer.shouldOffer(false, true, false, 1, false))
        assertFalse("a password manager's copy", ClipboardOffer.shouldOffer(false, false, true, 1, false))
        assertFalse("no computer wants it", ClipboardOffer.shouldOffer(false, false, false, 0, false))
        assertFalse("the debug watch already does it", ClipboardOffer.shouldOffer(false, false, false, 1, true))
    }

    @Test
    fun theNotificationIsQuietAutoTimedPrivateAndCarriesNoClipboardContent() {
        val intent = ClipboardFloatingActivity.getIntent(app, true)
        val n = ClipboardOffer.build(app, "Dell", intent)
        assertEquals(NotificationHelper.Channels.CLIPBOARD, n.channelId)
        assertEquals(ClipboardOffer.TIMEOUT_MS, n.timeoutAfter)
        assertEquals(android.app.Notification.VISIBILITY_SECRET, n.visibility)
        assertEquals("one action: Send to Dell", 1, n.actions.size)
        assertEquals("Send to Dell", n.actions[0].title.toString())
        val component = shadowOf(n.actions[0].actionIntent).savedIntent.component
        assertEquals(ClipboardFloatingActivity::class.java.name, component?.className)
    }

    @Test
    fun aComputerThatIsNotConnectedOrNotSharingGetsNoOffer() {
        assertTrue(ClipboardOffer.eligible(paired = true, sharing = true, ready = true, offerOn = true))
        assertFalse("not connected", ClipboardOffer.eligible(true, true, false, true))
        assertFalse("sharing off", ClipboardOffer.eligible(true, false, true, true))
        assertFalse("offer off", ClipboardOffer.eligible(true, true, true, false))
        assertFalse("not paired", ClipboardOffer.eligible(false, true, true, true))
    }

    @Test
    fun theOfferNamesTheComputers() {
        assertEquals("Dell", ClipboardOffer.target(listOf("Dell"), "your computers"))
        assertEquals("Dell / Arch", ClipboardOffer.target(listOf("Dell", "Arch"), "your computers"))
        assertEquals("your computers", ClipboardOffer.target(listOf("a", "b", "c"), "your computers"))
    }

    @Test
    fun aBurstOfCopiesIsPacedToOneEveryHalfSecond() {
        assertEquals(0L, ClipboardListener.SendPacing.waitMs(0, 10_000))
        assertEquals(400L, ClipboardListener.SendPacing.waitMs(10_000, 10_100))
        assertEquals(0L, ClipboardListener.SendPacing.waitMs(10_000, 10_600))
    }

    @Test
    fun theStatusLineSaysWhyNothingWasSent() {
        assertEquals(ClipboardStatus.Why.SHARING_OFF, ClipboardStatus.why(sharingOn = false, connected = true))
        assertEquals(ClipboardStatus.Why.NOT_CONNECTED, ClipboardStatus.why(true, false))
        assertEquals(ClipboardStatus.Why.OK, ClipboardStatus.why(true, true))
        ClipboardStatus.sent(app, "d", now = 1234L)
        assertEquals(1234L, ClipboardStatus.lastSent(app, "d"))
    }

    @Test
    fun theOfferCanBeTurnedOffPerComputerAndStartsOn() {
        assertTrue(ClipboardOffer.enabled(app, "fresh-desk"))
        ClipboardOffer.setEnabled(app, "fresh-desk", false)
        assertFalse(ClipboardOffer.enabled(app, "fresh-desk"))
    }

    @Test
    fun tappingSendReadsTheClipboardInTheFocusedActivityAndSendsIt() {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("t", "hello from the phone"))
        val got = CountDownLatch(1)
        var text: String? = null
        val observer = object : ClipboardListener.ClipboardObserver {
            override fun clipboardChanged(content: String, copiedAt: Long) {
                text = content
                got.countDown()
            }
        }
        val listener = ClipboardListener.instance(app)
        listener.registerObserver(observer)
        try {
            val controller = Robolectric.buildActivity(ClipboardFloatingActivity::class.java, ClipboardFloatingActivity.getIntent(app, true)).setup()
            controller.get().onWindowFocusChanged(true) // the activity has focus: Android lets it read
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("sent within a few seconds", got.await(5, TimeUnit.SECONDS))
            assertEquals("hello from the phone", text)
            assertNotNull(listener.currentContent)
        } finally {
            listener.removeObserver(observer)
        }
    }
}
