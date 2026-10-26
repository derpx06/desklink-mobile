/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.clipboard

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.desklink.mobile.R
import org.desklink.mobile.helpers.NotificationHelper

/**
 * Android 10+ lets an app read the clipboard only while one of its windows has focus, so a copy made in another app cannot be
 * read in the background. What an app can see is THAT something was copied (the change listener), not what. This is the
 * release-safe answer, needing no special permission: one quiet notification, "Copied something: Send to <computer>", whose
 * button starts [ClipboardFloatingActivity] (a notification action may start an activity, and the activity has focus, so the read
 * works). Nothing is read or sent without the tap. The same notification is updated, never stacked, and goes by itself.
 */
object ClipboardOffer {
    const val NOTIFICATION_ID = 0x0C1B
    const val TIMEOUT_MS = 30_000L

    fun prefKey(deviceId: String) = "clipboard_offer_$deviceId"

    /** "Offer to send copied text to <computer>": on unless the person turned it off. */
    fun enabled(context: Context, deviceId: String): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(prefKey(deviceId), true)

    fun setEnabled(context: Context, deviceId: String, on: Boolean) =
        PreferenceManager.getDefaultSharedPreferences(context).edit { putBoolean(prefKey(deviceId), on) }

    /**
     * Whether a copy made now deserves the offer: nobody is looking at DeskLink (in front, the copy is read and synced at once), it
     * is not the computer's own copy arriving, not marked sensitive (a password manager's), and a computer wants it.
     */
    fun shouldOffer(appInFront: Boolean, ownCopy: Boolean, sensitive: Boolean, computers: Int, automaticWatchRunning: Boolean): Boolean =
        !appInFront && !ownCopy && !sensitive && computers > 0 && !automaticWatchRunning

    /** A computer the offer is for: paired, sharing the clipboard, connected and feature-ready, and the person wants the offer. */
    fun eligible(paired: Boolean, sharing: Boolean, ready: Boolean, offerOn: Boolean) = paired && sharing && ready && offerOn

    /** "Dell", "Dell and Arch", "your computers": no clipboard content, ever, in the offer. */
    fun target(names: List<String>, many: String): String = when (names.size) {
        0 -> many
        1 -> names[0]
        2 -> "${names[0]} / ${names[1]}"
        else -> many
    }

    fun build(context: Context, computer: String, intent: android.content.Intent): Notification {
        val send = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, NotificationHelper.Channels.CLIPBOARD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.clipboard_offer_title))
            .setContentText(context.getString(R.string.clipboard_offer_action, computer))
            .addAction(0, context.getString(R.string.clipboard_offer_action, computer), send)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
    }

    fun show(context: Context, computer: String) {
        val notification = build(context, computer, ClipboardFloatingActivity.getIntent(context, true))
        runCatching {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU
            ) {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            }
        }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }
}
