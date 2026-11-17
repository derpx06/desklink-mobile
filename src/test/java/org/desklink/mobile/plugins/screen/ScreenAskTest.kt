/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.screen

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A desk asks to see this phone's screen (its Phone Screen window): the phone prompts whatever app is in front, with a
 * heads-up that says who asks, Allow (Android's own consent next) and Not now; the owner saw no prompt at all (11-14).
 */
@RunWith(RobolectricTestRunner::class)
class ScreenAskTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun plugin(): ScreenControlPlugin {
        val device = mockk<Device>(relaxed = true)
        every { device.deviceId } returns "desk-screen"
        every { device.name } returns "Workbench"
        return ScreenControlPlugin().also { it.setContext(app, device) }
    }

    private fun notices() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    @Test
    fun anAskIsAHeadsUpWithAllowAndNotNowThatTimesOutWithTheDesk() {
        plugin().askForCapture(offered = false, capturing = false)
        val notice = notices().single()
        assertEquals("Workbench wants to see your screen", notice.extras.getString("android.title"))
        assertEquals(org.desklink.mobile.helpers.NotificationHelper.Channels.REQUESTS, notice.channelId)
        @Suppress("DEPRECATION")
        assertEquals(android.app.Notification.PRIORITY_HIGH, notice.priority)
        assertEquals(listOf("Allow", "Not now"), notice.actions.map { it.title.toString() })
        assertEquals("the desk waits as long (remote_control::ASK_WAIT)", 60_000L, notice.timeoutAfter)
    }

    /**
     * The share was stopped without the plugin hearing it (the phone's own Desk screen, a reconnect): the next ask must still
     * prompt. A flag of the plugin's own said "already sharing" and every later ask was ignored.
     */
    @Test
    fun anAskAfterAShareEndedPromptsAgainAndOneThatIsLiveDoesNot() {
        val plugin = plugin()
        plugin.onPhoneCapturePermissionFinished(granted = true)
        plugin.askForCapture(offered = false, capturing = false)
        assertEquals(1, notices().size)
        app.getSystemService(NotificationManager::class.java).cancelAll()
        plugin.askForCapture(offered = false, capturing = true)
        assertTrue("sharing already: nothing to ask", notices().isEmpty())
    }

    /** Asked again by the desk: one prompt per desk, posted again, never a pile. */
    @Test
    fun askingAgainKeepsOnePrompt() {
        val plugin = plugin()
        plugin.askForCapture(offered = false, capturing = false)
        plugin.askForCapture(offered = false, capturing = false)
        assertEquals(1, notices().size)
        ScreenControlPlugin.cancelAsk(app, "desk-screen")
        assertTrue(notices().isEmpty())
    }

    /** Offered from this phone's own Share screen: the person is in the app, so no notification. */
    @Test
    fun anOfferFromThisPhoneHasNoNotification() {
        plugin().askForCapture(offered = true, capturing = false)
        assertTrue(notices().isEmpty())
    }

    /** Owner (11-15): the ask must be a pop-up over the open app whenever Android lets one start; a notification otherwise. */
    @Test
    fun theAskIsAPopUpWheneverAndroidLetsOneStart() {
        fun route(front: Boolean = false, overlay: Boolean = false, companion: Boolean = false) =
            ScreenControlPlugin.askRoute(front, overlay, companion)
        assertEquals(ScreenControlPlugin.AskRoute.POPUP, route(front = true))
        assertEquals(ScreenControlPlugin.AskRoute.POPUP, route(overlay = true))
        assertEquals(ScreenControlPlugin.AskRoute.POPUP, route(companion = true))
        // Never a full-screen intent (Play grants it to calling and alarm apps only): the heads-up notification, which the
        // high-importance channel shows over whatever is open, and Allow in it opens the consent.
        assertEquals(ScreenControlPlugin.AskRoute.NOTICE, route())
    }

    /** Allowed to draw over other apps: the pop-up opens, and the heads-up follows only if it never came up. */
    @Test
    fun aPopUpThatNeverCameUpFallsBackToTheNotification() {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        plugin().askForCapture(offered = false, capturing = false)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(ScreenAskActivity::class.java.name, started.component?.className)
        assertEquals("desk-screen", started.getStringExtra(ScreenAskActivity.EXTRA_DEVICE_ID))
        assertTrue("no notification over the pop-up", notices().isEmpty())
        org.robolectric.shadows.ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("Workbench wants to see your screen", notices().single().extras.getString("android.title"))
    }

    /** Only a paired desk's ask comes up (the activity is not exported either). */
    @Test
    fun anAskFromAnUnknownDeskOpensNoPopUp() {
        val activity = org.robolectric.Robolectric.buildActivity(
            ScreenAskActivity::class.java,
            ScreenControlPlugin.askIntent(app, "desk-screen"),
        ).create().get()
        assertTrue("an unknown desk gets no pop-up", activity.isFinishing)
    }
}
