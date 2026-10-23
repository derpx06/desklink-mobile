/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

import android.annotation.SuppressLint
import org.desklink.mobile.helpers.DeskNames
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.helpers.NotificationHelper

/**
 * One "Syncing Bucket" notification per desk: the totals, the speed and the time left, a
 * determinate bar, a line per file, Open Bucket and Pause sync / Resume. Silent, at most one
 * update a second, ongoing only while files move; then a short "Bucket up to date".
 */
object BucketSyncNotification {
    /** Android rate-limits a notification's updates; this keeps well inside it. */
    const val UPDATE_EVERY_MS = 1_000L
    /** "Bucket up to date" goes away by itself after this. */
    const val DONE_TIMEOUT_MS = 10_000L

    private const val ID = 7490
    private const val ACTION_PAUSE = "org.desklink.mobile.plugins.folders.PAUSE_SYNC"
    private const val ACTION_RESUME = "org.desklink.mobile.plugins.folders.RESUME_SYNC"
    private const val EXTRA_DEVICE = "deviceId"
    private val lastAt = HashMap<String, Long>()
    private val showing = HashSet<String>()

    /** Called from the Bucket's worker on each emit. True when it said "up to date" (the burst is over). */
    @SuppressLint("MissingPermission") // areNotificationsEnabled() covers POST_NOTIFICATIONS; a refusal is caught
    fun update(context: Context, view: BucketView, linkUp: Boolean, burst: Int): Boolean {
        val manager = NotificationManagerCompat.from(context)
        val deviceId = view.deviceId
        val tag = "bucket-sync:$deviceId"
        val desk = DeskNames.of(deviceId)
        val flights = view.items.mapNotNull { item -> item.progress?.let { BucketProgress.Flight(item.name, it, item.state == BucketItem.State.SENDING) } }
        if (flights.isEmpty()) {
            if (deviceId !in showing) return false
            showing -= deviceId
            lastAt -= deviceId
            if (burst == 0 || !manager.areNotificationsEnabled()) {
                manager.cancel(tag, ID)
                return true
            }
            runCatching {
                manager.notify(tag, ID, base(context, deviceId)
                    .setContentTitle(BucketProgress.doneText(burst, noteWords(context)))
                    .setSubText(desk)
                    .setOngoing(false)
                    .setTimeoutAfter(DONE_TIMEOUT_MS)
                    .build())
            }
            return true
        }
        val now = SystemClock.elapsedRealtime()
        if (now - (lastAt[deviceId] ?: 0) < UPDATE_EVERY_MS) return false
        lastAt[deviceId] = now
        if (!manager.areNotificationsEnabled()) return false
        val note = BucketProgress.note(desk, flights, linkDown = !linkUp, size = { org.desklink.mobile.helpers.Sizes.iec(it) }, w = words(context), n = noteWords(context)) ?: return false
        val style = NotificationCompat.InboxStyle().also { inbox -> note.lines.forEach(inbox::addLine) }.setSummaryText(note.subText)
        val pause = Intent(context, Receiver::class.java).setAction(if (view.userPaused) ACTION_RESUME else ACTION_PAUSE).putExtra(EXTRA_DEVICE, deviceId)
        runCatching {
            manager.notify(tag, ID, base(context, deviceId)
                .setContentTitle(note.title)
                .setContentText(note.text)
                .setSubText(note.subText)
                .setStyle(style)
                .setProgress(1000, (note.fraction * 1000).toInt(), false)
                .setOngoing(linkUp && !view.userPaused)
                .addAction(R.drawable.sym_inventory, context.getString(R.string.bucket_open_action), openIntent(context, deviceId))
                .addAction(
                    R.drawable.sym_pause,
                    context.getString(if (view.userPaused) R.string.bucket_resume else R.string.bucket_pause),
                    PendingIntent.getBroadcast(context, deviceId.hashCode(), pause, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                )
                .build())
            showing += deviceId
        }
        return false
    }

    private fun base(context: Context, deviceId: String) = NotificationCompat.Builder(context, NotificationHelper.Channels.FILETRANSFER_DOWNLOAD)
        .setSmallIcon(R.drawable.ic_notification)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setContentIntent(openIntent(context, deviceId))

    private fun openIntent(context: Context, deviceId: String) = PendingIntent.getActivity(
        context, ID + (deviceId.hashCode() and 0xFF),
        BucketActivity.intent(context, deviceId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun words(context: Context) = BucketProgress.Words(
        ofTotal = context.getString(R.string.bucket_of_total),
        joined = context.getString(R.string.bucket_joined),
        seconds = context.getString(R.string.bucket_eta_seconds),
        minutes = context.getString(R.string.bucket_eta_minutes),
        hours = context.getString(R.string.bucket_eta_hours),
        waiting = context.getString(R.string.bucket_waiting),
        paused = context.getString(R.string.bucket_paused),
        perSecond = context.getString(R.string.bucket_per_second),
    )

    fun noteWords(context: Context) = BucketProgress.NoteWords(
        titleOne = context.getString(R.string.bucket_note_title_one),
        titleMany = context.getString(R.string.bucket_note_title_many),
        about = context.getString(R.string.bucket_about),
        line = context.getString(R.string.bucket_note_line),
        percent = context.getString(R.string.bucket_note_percent),
        more = context.getString(R.string.bucket_note_more),
        paused = context.getString(R.string.bucket_note_paused),
        sending = context.getString(R.string.bucket_note_sending),
        receiving = context.getString(R.string.bucket_note_receiving),
        both = context.getString(R.string.bucket_note_both),
        doneOne = context.getString(R.string.bucket_done_one),
        doneMany = context.getString(R.string.bucket_done_many),
    )

    /** Pause sync / Resume from the notification. Not exported: only our PendingIntent reaches it. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val deviceId = intent.getStringExtra(EXTRA_DEVICE) ?: return
            when (intent.action) {
                ACTION_PAUSE -> BucketEngine.setPaused(deviceId, true)
                ACTION_RESUME -> BucketEngine.setPaused(deviceId, false)
            }
        }
    }
}
