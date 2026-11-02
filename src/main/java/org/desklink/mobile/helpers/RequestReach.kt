/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * A question from the computer ("take a photo", "may I hear your microphone") needs a person's tap. Android lets an activity open
 * from the background only through a notification the person sees; on some phones (ColorOS among them) a heads-up is never shown
 * when notifications are off or the channel was turned down. This says which of those the phone is in, so the computer is told
 * at once, in words, instead of waiting for a timeout nobody saw.
 */
object RequestReach {
    enum class Verdict { OK, NOTIFICATIONS_OFF, QUIET }

    /** Pure: the app in front can open its screen itself; otherwise the notification must be allowed and loud enough to pop up. */
    fun verdict(inFront: Boolean, notificationsEnabled: Boolean, channelImportance: Int?): Verdict = when {
        inFront -> Verdict.OK
        !notificationsEnabled -> Verdict.NOTIFICATIONS_OFF
        channelImportance != null && channelImportance < NotificationManagerCompat.IMPORTANCE_HIGH -> Verdict.QUIET
        else -> Verdict.OK
    }

    fun check(context: Context, channelId: String): Verdict {
        val manager = NotificationManagerCompat.from(context)
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val importance = runCatching { manager.getNotificationChannelCompat(channelId)?.importance }.getOrNull()
        return verdict(LifecycleHelper.isInForeground, permitted && manager.areNotificationsEnabled(), importance)
    }
}
