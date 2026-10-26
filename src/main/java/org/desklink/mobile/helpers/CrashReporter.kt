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
    // A notification's PendingIntent travels through binder; keep its extras small.
    private const val MAX_CHARS = 32 * 1024
    private const val NOTIFICATION_ID = 0x0C4A54

    const val MAX_REPORTS = 5

    @Volatile private var dir: File? = null

    /**
     * Installs the global handler. It only reaches threads the app does not own (the main thread, libraries): those are recorded and
     * then crash as usual. Threads the app owns have their own handler (see [Guarded]) and the app carries on after them.
     */
    fun install(context: Context) {
        dir = context.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { keep(context.filesDir, report(thread, error)) }
            CrashLoop.recordCrash(context)
            previous?.uncaughtException(thread, error)
        }
    }

    /** A failure caught at a task boundary: kept like a crash (redacted), the process stays up. */
    fun recordGuarded(name: String, error: Throwable) {
        val where = dir ?: return
        keep(where, LogExport.redact(report(Thread.currentThread(), error) + "\n(caught in $name; the app carried on)"))
    }

    /** One file per crash (the next one no longer overwrites the last), the newest [MAX_REPORTS] kept. */
    internal fun keep(dir: File, text: String, now: Long = System.currentTimeMillis()) {
        File(dir, "crash-report-$now.txt").writeText(text)
        reports(dir).drop(MAX_REPORTS).forEach { it.delete() }
    }

    /** The kept reports, newest first. */
    internal fun reports(dir: File): List<File> =
        dir.listFiles { f -> f.name.startsWith("crash-report-") && f.name.endsWith(".txt") }.orEmpty().sortedByDescending { it.name.removePrefix("crash-report-").removeSuffix(".txt").toLongOrNull() ?: 0L }

    internal fun report(thread: Thread, error: Throwable): String =
        "DeskLink ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "${Date()}, thread ${thread.name}\n\n" +
            error.stackTraceToString()

    /** Posts the share offer for a crash recorded by an earlier process, if any. */
    @SuppressLint("MissingPermission") // areNotificationsEnabled() covers POST_NOTIFICATIONS
    fun offerPendingReport(context: Context) {
        val all = reports(context.filesDir)
        if (all.isEmpty()) return
        val notifications = NotificationManagerCompat.from(context)
        // Kept until it can be offered: notifications may be off for now.
        if (!notifications.areNotificationsEnabled()) return
        val text = runCatching { all.first().readText().take(MAX_CHARS) }.getOrNull()
        all.forEach { it.delete() }
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
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.DIAGNOSTICS)
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
