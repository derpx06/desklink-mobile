/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.desklink.mobile.BuildConfig
import org.desklink.mobile.R
import java.io.File
import java.util.Date

/**
 * Keeps the last crash in app-private storage and, on the next start, offers it
 * through a share sheet. Nothing is uploaded; the person decides where it goes.
 */
object CrashReporter {
    private const val FILE_NAME = "crash-report.txt"
    // A notification's PendingIntent travels through binder; keep its extras small.
    private const val MAX_CHARS = 32 * 1024
    private const val NOTIFICATION_ID = 0x0C4A54

    fun install(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(report(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    internal fun report(thread: Thread, error: Throwable): String =
        "DeskLink ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "${Date()}, thread ${thread.name}\n\n" +
            error.stackTraceToString()

    /** Posts the share offer for a crash recorded by an earlier process, if any. */
    @SuppressLint("MissingPermission") // areNotificationsEnabled() covers POST_NOTIFICATIONS
    fun offerPendingReport(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return
        val notifications = NotificationManagerCompat.from(context)
        // Kept until it can be offered: notifications may be off for now.
        if (!notifications.areNotificationsEnabled()) return
        val text = runCatching { file.readText().take(MAX_CHARS) }.getOrNull()
        file.delete()
        if (text.isNullOrEmpty()) return

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DeskLink crash report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.crash_report_share))
        val pending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, chooser,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.DEFAULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.crash_report_title))
            .setContentText(context.getString(R.string.crash_report_text))
            .setContentIntent(pending)
            .addAction(0, context.getString(R.string.crash_report_share), pending)
            .setAutoCancel(true)
            .build()
        runCatching { notifications.notify(NOTIFICATION_ID, notification) }
    }
}
