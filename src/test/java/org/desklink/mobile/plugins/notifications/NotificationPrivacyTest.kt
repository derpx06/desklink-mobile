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
        assertEquals(Mirror.HIDDEN, NotificationPrivacy.decide(Notification.VISIBILITY_PRIVATE, keyguardSecure = true, showPrivateContent = false))
        assertEquals(Mirror.FULL, NotificationPrivacy.decide(Notification.VISIBILITY_PRIVATE, keyguardSecure = true, showPrivateContent = true))
        // No secure lock screen: Android itself shows it all.
        assertEquals(Mirror.FULL, NotificationPrivacy.decide(Notification.VISIBILITY_PRIVATE, keyguardSecure = false, showPrivateContent = false))
    }

    @Test
    fun publicAndUnsetAreFull() {
        assertEquals(Mirror.FULL, NotificationPrivacy.decide(Notification.VISIBILITY_PUBLIC, true, false))
        assertEquals(Mirror.FULL, NotificationPrivacy.decide(-1000, true, false))
    }
}
