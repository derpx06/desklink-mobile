/*
 * SPDX-FileCopyrightText: 2023 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.desklink.mobile.helpers

import android.content.Context
import android.os.Build
import android.preference.PreferenceManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import org.desklink.mobile.R
import androidx.core.content.edit

object NotificationHelper {
    fun initializeChannels(context: Context) {
        fun channel(id: String, importance: Int, name: Int, quiet: Boolean = false) =
            NotificationChannelCompat.Builder(id, importance).setName(context.getString(name)).apply {
                if (quiet) setVibrationEnabled(false).setLightsEnabled(false).setSound(null, null)
            }.build()
        // One channel per purpose, each with a name that says what it is: the person can silence "Files
        // being sent" without silencing "Calls".
        val channels = listOf(
            channel(Channels.PERSISTENT, NotificationManagerCompat.IMPORTANCE_MIN, R.string.connection_service_name),
            channel(Channels.PAIRING, NotificationManagerCompat.IMPORTANCE_HIGH, R.string.channel_pairing),
            channel(Channels.REQUESTS, NotificationManagerCompat.IMPORTANCE_HIGH, R.string.channel_requests),
            channel(Channels.CALLS, NotificationManagerCompat.IMPORTANCE_HIGH, R.string.channel_calls),
            channel(Channels.FIND_PHONE, NotificationManagerCompat.IMPORTANCE_HIGH, R.string.channel_find_phone),
            channel(Channels.MEDIA_CONTROL, NotificationManagerCompat.IMPORTANCE_LOW, R.string.notification_channel_media_control),
            channel(Channels.FILETRANSFER_DOWNLOAD, NotificationManagerCompat.IMPORTANCE_LOW, R.string.channel_files_receiving, quiet = true),
            channel(Channels.FILETRANSFER_COMPLETE, NotificationManagerCompat.IMPORTANCE_DEFAULT, R.string.channel_files_received, quiet = true),
            channel(Channels.FILETRANSFER_UPLOAD, NotificationManagerCompat.IMPORTANCE_LOW, R.string.channel_files_sending, quiet = true),
            channel(Channels.FILETRANSFER_ERROR, NotificationManagerCompat.IMPORTANCE_DEFAULT, R.string.channel_files_problems, quiet = true),
            channel(Channels.BUCKET, NotificationManagerCompat.IMPORTANCE_DEFAULT, R.string.channel_bucket, quiet = true),
            // This notification should be highly visible *only* if the user looks at their phone. It
            // should not be a distraction: a convenient button to press.
            channel(Channels.CONTINUEWATCHING, NotificationManagerCompat.IMPORTANCE_HIGH, R.string.notification_channel_keepwatching, quiet = true),
            // Registered here, not lazily by the terminal code: the delete below would remove it at every start.
            channel(Channels.TERMINAL_ATTENTION, NotificationManagerCompat.IMPORTANCE_HIGH, R.string.terminal_attention_channel),
            channel(Channels.DIAGNOSTICS, NotificationManagerCompat.IMPORTANCE_LOW, R.string.channel_diagnostics),
        )

        val nm = NotificationManagerCompat.from(context)

        nm.createNotificationChannelsCompat(channels)

        // Delete channels nothing creates any more (deprecated ones). Channels made elsewhere stay:
        // the live-session one (a running foreground service may hold it, and deleting it then throws)
        // and each desk's "From <desk>". One refusal never stops the app from starting.
        val keep = channels.map { it.id }.toSet()
        nm.notificationChannelsCompat.map { it.id }
            .filter { id -> deprecated(id, keep) }
            .forEach { id -> runCatching { nm.deleteNotificationChannel(id) } }
    }

    /** Every channel [initializeChannels] creates, for the test that pins one honest name per purpose. */
    internal val ALL_CHANNEL_IDS = listOf(
        Channels.PERSISTENT, Channels.PAIRING, Channels.REQUESTS, Channels.CALLS, Channels.FIND_PHONE, Channels.MEDIA_CONTROL,
        Channels.FILETRANSFER_DOWNLOAD, Channels.FILETRANSFER_COMPLETE, Channels.FILETRANSFER_UPLOAD, Channels.FILETRANSFER_ERROR,
        Channels.BUCKET, Channels.CONTINUEWATCHING, Channels.TERMINAL_ATTENTION, Channels.DIAGNOSTICS,
    )

    /** A channel no current code creates: safe to delete. */
    internal fun deprecated(id: String, keep: Set<String>): Boolean =
        id !in keep && id != NotificationChannelCompat.DEFAULT_CHANNEL_ID &&
            id != org.desklink.mobile.plugins.screen.SessionLiveNotification.CHANNEL &&
            !id.startsWith("desk_notifications_")

    fun setPersistentNotificationEnabled(context: Context?, enabled: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit { putBoolean("persistentNotification", enabled) }
    }

    fun isPersistentNotificationEnabled(context: Context?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return true
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean("persistentNotification", false)
    }

    object Channels {
        const val PERSISTENT: String = "persistent"
        const val PAIRING: String = "pairing"

        /** A question or a prompt from the computer that needs a tap: a link, an unlock, a screen request. */
        const val REQUESTS: String = "requests"
        const val CALLS: String = "calls"
        const val FIND_PHONE: String = "find_phone"
        const val BUCKET: String = "bucket"
        const val DIAGNOSTICS: String = "diagnostics"
        const val MEDIA_CONTROL: String = "media_control"

        const val FILETRANSFER_DOWNLOAD: String = "filetransfer"
        const val FILETRANSFER_UPLOAD: String = "filetransfer_upload"
        const val FILETRANSFER_ERROR: String = "filetransfer_error"
        const val FILETRANSFER_COMPLETE: String = "filetransfer_complete"

        const val CONTINUEWATCHING: String = "continuewatching"
        const val TERMINAL_ATTENTION: String = "terminal_attention"
    }
}
