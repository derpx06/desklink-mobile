/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

import android.app.Notification
import org.desklink.mobile.plugins.notifications.NotificationPrivacy.Mirror
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPrivacyTest {
    @Test
    fun aSecretNotificationIsNeverMirrored() {
        for (secure in listOf(true, false)) for (opt in listOf(true, false)) {
            assertEquals(Mirror.SKIP, NotificationPrivacy.decide(Notification.VISIBILITY_SECRET, secure, opt))
        }
    }

    @Test
    fun aPrivateOneOnASecuredScreenGoesWithoutItsContentUnlessThePersonSaidSo() {
        assertEquals(Mirror.HIDDEN, NotificationPrivacy.decide(Notification.VISIBILITY_PRIVATE, keyguardSecure = true, showPrivateContent = false, systemAllowsPrivate = false))
        assertEquals(Mirror.FULL, NotificationPrivacy.decide(Notification.VISIBILITY_PRIVATE, keyguardSecure = true, showPrivateContent = true, systemAllowsPrivate = false))
        // No secure lock screen: Android itself shows it all.
        assertEquals(Mirror.FULL, NotificationPrivacy.decide(Notification.VISIBILITY_PRIVATE, keyguardSecure = false, showPrivateContent = false))
    }

    @Test
    fun publicAndUnsetAreFull() {
        assertEquals(Mirror.FULL, NotificationPrivacy.decide(Notification.VISIBILITY_PUBLIC, true, false))
        assertEquals(Mirror.FULL, NotificationPrivacy.decide(-1000, true, false)) // an undefined value reads as private, and the phone shows private content
    }

    @Test
    fun thePhonesOwnLockScreenSettingDecidesWhatIsHidden() {
        val p = Notification.VISIBILITY_PRIVATE
        // The default: an app that never chose is PRIVATE, and a phone that shows private content when locked shows it all.
        assertEquals(Mirror.FULL, NotificationPrivacy.decide(p, keyguardSecure = true, showPrivateContent = false, systemAllowsPrivate = true))
        assertEquals(Mirror.HIDDEN, NotificationPrivacy.decide(p, keyguardSecure = true, showPrivateContent = false, systemAllowsPrivate = false))
        assertEquals(Mirror.FULL, NotificationPrivacy.decide(p, keyguardSecure = true, showPrivateContent = true, systemAllowsPrivate = false))
        assertEquals(Mirror.FULL, NotificationPrivacy.decide(p, keyguardSecure = false, showPrivateContent = false, systemAllowsPrivate = false))
        for (secure in listOf(true, false)) for (allows in listOf(true, false)) {
            assertEquals(Mirror.FULL, NotificationPrivacy.decide(Notification.VISIBILITY_PUBLIC, secure, false, allows))
            assertEquals(Mirror.SKIP, NotificationPrivacy.decide(Notification.VISIBILITY_SECRET, secure, true, allows))
        }
    }

    @Test
    fun aChannelCanOnlyMakeItStricterAndNoOverrideChangesNothing() {
        val none = NotificationPrivacy.CHANNEL_NO_OVERRIDE
        assertEquals(Notification.VISIBILITY_PUBLIC, NotificationPrivacy.effective(Notification.VISIBILITY_PUBLIC, none))
        assertEquals(Notification.VISIBILITY_PRIVATE, NotificationPrivacy.effective(Notification.VISIBILITY_PUBLIC, Notification.VISIBILITY_PRIVATE))
        assertEquals(Notification.VISIBILITY_SECRET, NotificationPrivacy.effective(Notification.VISIBILITY_PUBLIC, Notification.VISIBILITY_SECRET))
        assertEquals("a channel never loosens", Notification.VISIBILITY_PRIVATE, NotificationPrivacy.effective(Notification.VISIBILITY_PRIVATE, Notification.VISIBILITY_PUBLIC))
        assertEquals(Mirror.SKIP, NotificationPrivacy.decide(Notification.VISIBILITY_PUBLIC, true, true, true, channelVisibility = Notification.VISIBILITY_SECRET))
        assertEquals("a value Android does not define reads as private", Notification.VISIBILITY_PRIVATE, NotificationPrivacy.effective(-1000, none))
    }
}
