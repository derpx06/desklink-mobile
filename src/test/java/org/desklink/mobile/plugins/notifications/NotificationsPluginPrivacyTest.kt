/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.os.Process
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.desklink.mobile.plugins.notifications.NotificationPrivacy.Mirror
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The decision through the plugin, with a channel that says "don't show on the lock screen" and a setting that cannot be read. */
@RunWith(RobolectricTestRunner::class)
class NotificationsPluginPrivacyTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun plugin(secure: Boolean): NotificationsPlugin {
        shadowOf(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setIsKeyguardSecure(secure)
        val device = mockk<Device>(relaxed = true) { every { deviceId } returns "desk-privacy-1" }
        return NotificationsPlugin().apply { setContext(app, device) }
    }

    private fun sbn(visibility: Int) = StatusBarNotification(
        "com.example", "com.example", 1, null, 1000, 1, 0,
        Notification().apply { this.visibility = visibility }, Process.myUserHandle(), 0L,
    )

    @Test
    fun aChannelSetToHideFromTheLockScreenIsHiddenOrSkippedEvenForAPublicNotification() {
        val p = plugin(secure = true)
        Settings.Secure.putInt(app.contentResolver, "lock_screen_allow_private_notifications", 1)
        p.channelVisibilityOf = { NotificationPrivacy.CHANNEL_NO_OVERRIDE }
        assertEquals("public, no channel setting: full", Mirror.FULL, p.privacyOf(sbn(Notification.VISIBILITY_PUBLIC)))
        p.channelVisibilityOf = { Notification.VISIBILITY_SECRET }
        assertEquals("the channel says secret: never mirrored", Mirror.SKIP, p.privacyOf(sbn(Notification.VISIBILITY_PUBLIC)))
        Settings.Secure.putInt(app.contentResolver, "lock_screen_allow_private_notifications", 0)
        p.channelVisibilityOf = { Notification.VISIBILITY_PRIVATE }
        assertEquals("the channel says private and the phone hides private content", Mirror.HIDDEN, p.privacyOf(sbn(Notification.VISIBILITY_PUBLIC)))
    }

    @Test
    fun withoutASecureLockScreenNothingIsHiddenButSecretStillIsNot() {
        val p = plugin(secure = false)
        Settings.Secure.putInt(app.contentResolver, "lock_screen_allow_private_notifications", 0)
        p.channelVisibilityOf = { Notification.VISIBILITY_PRIVATE }
        assertEquals(Mirror.FULL, p.privacyOf(sbn(Notification.VISIBILITY_PRIVATE)))
        assertEquals(Mirror.SKIP, p.privacyOf(sbn(Notification.VISIBILITY_SECRET)))
    }

    @Test
    fun aSettingThatCannotBeReadFailsClosed() {
        assertEquals(false, NotificationPrivacy.systemAllowsPrivate { throw SecurityException("no") })
        assertEquals(true, NotificationPrivacy.systemAllowsPrivate { 1 })
        assertEquals(false, NotificationPrivacy.systemAllowsPrivate { 0 })
        assertEquals(Mirror.HIDDEN, NotificationPrivacy.decide(Notification.VISIBILITY_PRIVATE, true, false, NotificationPrivacy.systemAllowsPrivate { throw SecurityException("no") }))
    }
}
