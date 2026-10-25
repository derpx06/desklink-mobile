/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

import android.app.Notification
import android.content.Context

/**
 * What Android's own privacy marks on a notification mean for the copy that goes to the computer: a SECRET one is never
 * mirrored (one-time codes, banking), a PRIVATE one shows its public version only (or no content) unless the person said
 * the computer may see private contents, and only matters when the lock screen is secured.
 */
object NotificationPrivacy {
    enum class Mirror { SKIP, HIDDEN, FULL }

    fun decide(visibility: Int, keyguardSecure: Boolean, showPrivateContent: Boolean): Mirror = when {
        visibility == Notification.VISIBILITY_SECRET -> Mirror.SKIP
        visibility == Notification.VISIBILITY_PRIVATE && keyguardSecure && !showPrivateContent -> Mirror.HIDDEN
        else -> Mirror.FULL
    }

    /** Per computer, off unless the person turned it on in Settings. */
    fun key(deviceId: String) = "private_notifications_$deviceId"

    fun showPrivate(context: Context, deviceId: String): Boolean =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).getBoolean(key(deviceId), false)
}
