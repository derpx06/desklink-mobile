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

    /** A channel's `lockscreenVisibility` when the person never set one (`NotificationManager.VISIBILITY_NO_OVERRIDE`). */
    const val CHANNEL_NO_OVERRIDE = -1000

    /**
     * What the lock screen would show: the notification's own visibility (PUBLIC 1, PRIVATE 0 which is also what an app gets by
     * not choosing, SECRET -1), made stricter by its channel's setting when there is one ([CHANNEL_NO_OVERRIDE] means none).
     */
    fun effective(notificationVisibility: Int, channelVisibility: Int = CHANNEL_NO_OVERRIDE): Int {
        val own = when (notificationVisibility) {
            Notification.VISIBILITY_PUBLIC, Notification.VISIBILITY_PRIVATE, Notification.VISIBILITY_SECRET -> notificationVisibility
            else -> Notification.VISIBILITY_PRIVATE // not a value Android defines: the careful reading
        }
        return when (channelVisibility) {
            Notification.VISIBILITY_PUBLIC, Notification.VISIBILITY_PRIVATE, Notification.VISIBILITY_SECRET -> minOf(own, channelVisibility)
            else -> own // no override
        }
    }

    /**
     * [systemAllowsPrivate]: the phone's own "show sensitive content when locked" (default on). A PRIVATE notification is hidden
     * only where the lock screen itself would hide it: a secured screen, the system setting off, and the person not having said
     * the computer may see private contents.
     */
    fun decide(
        visibility: Int, keyguardSecure: Boolean, showPrivateContent: Boolean,
        systemAllowsPrivate: Boolean = true, channelVisibility: Int = CHANNEL_NO_OVERRIDE,
    ): Mirror {
        val effective = effective(visibility, channelVisibility)
        return when {
            effective == Notification.VISIBILITY_SECRET -> Mirror.SKIP
            effective == Notification.VISIBILITY_PRIVATE && keyguardSecure && !systemAllowsPrivate && !showPrivateContent -> Mirror.HIDDEN
            else -> Mirror.FULL
        }
    }

    /** `lock_screen_allow_private_notifications` (hidden constant; absent means allowed; unreadable means NOT allowed). */
    fun systemAllowsPrivate(context: Context): Boolean = systemAllowsPrivate {
        android.provider.Settings.Secure.getInt(context.contentResolver, "lock_screen_allow_private_notifications", 1)
    }

    /** The reading of that setting, pure for the test: a reader that throws means "not allowed". */
    fun systemAllowsPrivate(read: () -> Int): Boolean = runCatching { read() != 0 }.getOrDefault(false) // unreadable: the careful answer (a PRIVATE notification stays hidden), never the open one

    /** Per computer, off unless the person turned it on in Settings. */
    fun key(deviceId: String) = "private_notifications_$deviceId"

    fun showPrivate(context: Context, deviceId: String): Boolean =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).getBoolean(key(deviceId), false)
}
