/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.helpers.DeskNames
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.helpers.Sizes
import org.desklink.mobile.plugins.activity.ActivityItems
import org.desklink.mobile.plugins.folders.BucketEngine
import org.desklink.mobile.plugins.folders.BucketProgress
import org.desklink.mobile.plugins.folders.Progress
import org.desklink.mobile.plugins.folders.SpeedMeter

/**
 * The one notification for everything moving between this phone and a computer: titled "DeskLink", the computer small under it. Files
 * sent or received, shares from other apps and the Bucket's sync all feed it, so there is never more than one per computer: what is
 * moving, how far, how fast, the time left, a determinate bar, "Waiting for <computer>" when the link is gone, and Cancel (Pause sync
 * when it is only the Bucket). Silent, low importance, at most one update a second, gone when the set is done (the finished or failed
 * notice of the transfer takes over). File names show on the lock screen only through its public version.
 */
object TransferNotification {
    const val UPDATE_EVERY_MS = 1_000L
    const val DONE_TIMEOUT_MS = 10_000L
    private const val ID = 7490
    private const val ACTION_CANCEL = "org.desklink.mobile.plugins.share.CANCEL_TRANSFERS"
    private const val ACTION_PAUSE = "org.desklink.mobile.plugins.folders.PAUSE_SYNC"
    private const val ACTION_RESUME = "org.desklink.mobile.plugins.folders.RESUME_SYNC"
    private const val EXTRA_DEVICE = "deviceId"

    /** One file moving: its numbers, as the notification needs them. */
    data class Item(
        val name: String, val sending: Boolean, val done: Long, val total: Long, val etaSeconds: Long?, val bytesPerSecond: Long,
        /** The link is gone (it resumes by itself), or the sync is paused. */
        val waiting: Boolean, val saving: Boolean = false, val bucket: Boolean = false,
    )

    enum class Action { CANCEL, PAUSE_SYNC, RESUME_SYNC }

    data class Note(val text: String, val fraction: Float, val indeterminate: Boolean, val action: Action)

    /** The notification's words (English defaults keep the builder testable without resources). */
    data class Words(
        val sending: String = "Sending", val receiving: String = "Receiving", val transferring: String = "Transferring",
        val saving: String = "Saving %s", val files: String = "%1\$s %2\$d files", val ofCount: String = "%1\$d of %2\$d",
        val percent: String = "%d%%", val about: String = "about %s", val perSecond: String = "%s/s", val join: String = " · ",
        val waitingFor: String = "Waiting for %s", val paused: String = "Paused", val storageFull: String = "Storage is full",
        val publicText: String = "DeskLink · transferring files",
        val eta: BucketProgress.Words = BucketProgress.Words(),
    )

    /**
     * "Receiving dl-300m.bin · 45% · 6.5 MB/s · about 40 s left", "Sending 3 files · 2 of 3 · 40%", "Waiting for <computer>",
     * "Paused", "Storage is full". [finishedInSet]/[finishedBytes]: files of this set that already arrived. Null with nothing moving.
     */
    fun note(
        desk: String, items: List<Item>, finishedInSet: Int, finishedBytes: Long, userPaused: Boolean, storageFull: Boolean,
        size: (Long) -> String, w: Words = Words(),
    ): Note? {
        if (items.isEmpty()) return null
        val sum = BucketProgress.summary(items.map { Progress(it.done, it.total, it.etaSeconds, it.bytesPerSecond, it.waiting) }) ?: return null
        val total = sum.total + finishedBytes
        val fraction = if (total <= 0) 0f else ((sum.done + finishedBytes).toFloat() / total).coerceIn(0f, 1f)
        val action = when {
            items.all { it.bucket } && userPaused -> Action.RESUME_SYNC
            items.all { it.bucket } -> Action.PAUSE_SYNC
            else -> Action.CANCEL
        }
        if (items.size == 1 && items[0].saving) return Note(w.saving.format(items[0].name), 0f, true, action)
        val verb = when {
            items.all { it.sending } -> w.sending
            items.none { it.sending } -> w.receiving
            else -> w.transferring
        }
        val files = items.size + finishedInSet
        val lead = if (files == 1) "$verb ${items[0].name}" else w.files.format(verb, files)
        val text = when {
            storageFull -> w.storageFull
            userPaused -> w.paused
            sum.paused -> w.waitingFor.format(desk)
            else -> listOfNotNull(
                lead,
                if (finishedInSet > 0) w.ofCount.format(finishedInSet, files) else null,
                w.percent.format((fraction * 100).toInt()),
                sum.bytesPerSecond.takeIf { it > 0 }?.let { w.perSecond.format(size(it)) },
                sum.etaSeconds?.let { w.about.format(BucketProgress.eta(it, w.eta)) },
            ).joinToString(w.join)
        }
        return Note(text, fraction, indeterminate = false, action = action)
    }

    // ---- what is moving, per computer -----------------------------------------------------------------------------

    private class Job(val upload: Boolean, var name: String, var total: Long, val startedAt: Long) {
        var done = 0L
        var waiting = false
        var saving = false
        val meter = SpeedMeter(startedAt)
    }

    private class Desk {
        val jobs = LinkedHashMap<Long, Job>()
        var bucket: List<Item> = emptyList()
        var bucketLinkUp = true
        var bucketPaused = false
        var storageFull = false
        var finishedInSet = 0
        var finishedBytes = 0L
        var showing = false
        val throttle = ActivityItems.Throttle(UPDATE_EVERY_MS)
        var trailing: Runnable? = null
    }

    private val desks = HashMap<String, Desk>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private fun desk(id: String) = synchronized(desks) { desks.getOrPut(id) { Desk() } }
    private fun deskOfJob(jobId: Long) = synchronized(desks) { desks.entries.firstOrNull { jobId in it.value.jobs }?.toPair() }

    /** A transfer moved (sent or received): [percent] of [totalBytes], by [TransferProgress]. */
    fun progress(deviceId: String, jobId: Long, upload: Boolean, title: String, percent: Int?, totalBytes: Long) {
        val now = SystemClock.elapsedRealtime()
        val d = desk(deviceId)
        synchronized(d) {
            val job = d.jobs.getOrPut(jobId) { Job(upload, title, totalBytes, now) }
            if (title.isNotEmpty()) job.name = title
            if (totalBytes > 0) job.total = totalBytes
            job.done = if (percent == null) 0 else job.total * percent.coerceIn(0, 100) / 100
            job.waiting = false
            job.saving = false
            job.meter.update(job.done, now)
        }
        refresh(deviceId)
    }

    fun saving(deviceId: String, jobId: Long, title: String, totalBytes: Long) {
        val d = desk(deviceId)
        synchronized(d) {
            val job = d.jobs.getOrPut(jobId) { Job(false, title, totalBytes, SystemClock.elapsedRealtime()) }
            job.saving = true; job.done = job.total
        }
        refresh(deviceId)
    }

    fun resuming(jobId: Long) {
        val (id, d) = deskOfJob(jobId) ?: return
        synchronized(d) { d.jobs[jobId]?.waiting = true }
        refresh(id)
    }

    /** Finished or failed: the transfer's own notice takes over; the rest of the set carries on. */
    fun ended(jobId: Long, failed: Boolean) {
        val (id, d) = deskOfJob(jobId) ?: return
        synchronized(d) {
            val job = d.jobs.remove(jobId) ?: return
            if (!failed) { d.finishedInSet++; d.finishedBytes += job.total }
        }
        refresh(id)
    }

    fun cancelled(jobId: Long) {
        val (id, d) = deskOfJob(jobId) ?: return
        synchronized(d) { d.jobs.remove(jobId) }
        refresh(id)
    }

    /** The jobs of [deviceId] now moving (for Cancel). */
    fun jobIds(deviceId: String): List<Long> = desk(deviceId).let { d -> synchronized(d) { d.jobs.keys.toList() } }

    /** The Bucket's files in flight, from its worker on each emit. True when it said "up to date" (the burst is over). */
    @SuppressLint("MissingPermission") // areNotificationsEnabled() covers POST_NOTIFICATIONS; a refusal is caught
    fun bucket(context: Context, deviceId: String, items: List<Item>, linkUp: Boolean, userPaused: Boolean, storageFull: Boolean, burst: Int): Boolean {
        val d = desk(deviceId)
        val wasMoving = synchronized(d) { d.bucket.isNotEmpty() }
        synchronized(d) { d.bucket = items; d.bucketLinkUp = linkUp; d.bucketPaused = userPaused; d.storageFull = storageFull }
        if (items.isEmpty() && wasMoving && synchronized(d) { d.jobs.isEmpty() } && burst > 0 && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            // The sync is over: one short notice in the same place, then it goes by itself.
            runCatching {
                NotificationManagerCompat.from(context).notify(tag(deviceId), ID, base(context, deviceId)
                    .setContentTitle(context.getString(R.string.transfer_note_title))
                    .setContentText(BucketProgress.doneText(burst, org.desklink.mobile.plugins.folders.BucketSyncNotification.noteWords(context)))
                    .setSubText(DeskNames.of(deviceId))
                    .setOngoing(false).setTimeoutAfter(DONE_TIMEOUT_MS).build())
            }
            synchronized(d) { d.showing = false }
            return true
        }
        refresh(deviceId)
        return items.isEmpty() && wasMoving
    }

    private fun tag(deviceId: String) = "transfer:$deviceId"

    /** Posts the current state, at most once a second (one trailing post catches the last change). */
    private fun refresh(deviceId: String) {
        val context = runCatching { DeskLinkApplication.getInstance() as Context }.getOrNull() ?: return
        val d = desk(deviceId)
        val moving = synchronized(d) { d.jobs.isNotEmpty() || d.bucket.isNotEmpty() }
        if (!moving) { clear(context, deviceId, d); return }
        val now = SystemClock.elapsedRealtime()
        val wait = synchronized(d) { d.throttle.onChange(now) } ?: return
        if (wait == 0L) post(context, deviceId, d)
        else handler.postDelayed(Runnable { synchronized(d) { d.throttle.onFired(SystemClock.elapsedRealtime()) }; refresh(deviceId) }.also { synchronized(d) { d.trailing = it } }, wait)
    }

    private fun clear(context: Context, deviceId: String, d: Desk) {
        val wasShowing = synchronized(d) {
            d.trailing?.let(handler::removeCallbacks); d.trailing = null
            d.throttle.onFired(Long.MIN_VALUE / 4)
            d.finishedInSet = 0; d.finishedBytes = 0
            d.showing.also { d.showing = false }
        }
        if (wasShowing) runCatching { NotificationManagerCompat.from(context).cancel(tag(deviceId), ID) }
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, deviceId: String, d: Desk) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val desk = DeskNames.of(deviceId)
        val now = SystemClock.elapsedRealtime()
        val (items, set) = synchronized(d) {
            val running = d.jobs.values.map { j ->
                val remaining = (j.total - j.done).coerceAtLeast(0)
                Item(j.name, j.upload, j.done, j.total, j.meter.etaSeconds(remaining, now), j.meter.bytesPerSecond(now), j.waiting, j.saving)
            } + d.bucket.map { it.copy(waiting = it.waiting || !d.bucketLinkUp || d.bucketPaused) }
            running to listOf(d.finishedInSet.toLong(), d.finishedBytes, if (d.bucketPaused) 1L else 0L, if (d.storageFull) 1L else 0L)
        }
        val w = words(context)
        val note = note(desk, items, set[0].toInt(), set[1], set[2] == 1L, set[3] == 1L, { Sizes.iec(it) }, w) ?: return
        val builder = base(context, deviceId)
            .setContentTitle(context.getString(R.string.transfer_note_title))
            .setContentText(note.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note.text))
            .setSubText(desk)
            .setProgress(1000, (note.fraction * 1000).toInt(), note.indeterminate)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, NotificationHelper.Channels.FILETRANSFER_DOWNLOAD)
                    .setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(R.string.transfer_note_title))
                    .setContentText(w.publicText).setProgress(1000, (note.fraction * 1000).toInt(), note.indeterminate).build(),
            )
        val (label, action) = when (note.action) {
            Action.CANCEL -> context.getString(R.string.cancel) to ACTION_CANCEL
            Action.PAUSE_SYNC -> context.getString(R.string.bucket_pause) to ACTION_PAUSE
            Action.RESUME_SYNC -> context.getString(R.string.bucket_resume) to ACTION_RESUME
        }
        val intent = Intent(context, Receiver::class.java).setAction(action).putExtra(EXTRA_DEVICE, deviceId)
        runCatching {
            manager.notify(
                tag(deviceId), ID,
                builder.addAction(R.drawable.ic_reject_pairing_24dp, label, PendingIntent.getBroadcast(context, deviceId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build(),
            )
            synchronized(d) { d.showing = true }
        }
    }

    private fun base(context: Context, deviceId: String) = NotificationCompat.Builder(context, NotificationHelper.Channels.FILETRANSFER_DOWNLOAD)
        .setSmallIcon(R.drawable.ic_notification)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setContentIntent(openFiles(context, deviceId))

    private fun openFiles(context: Context, deviceId: String) = PendingIntent.getActivity(
        context, ID + (deviceId.hashCode() and 0xFF),
        Intent(context, org.desklink.mobile.ui.MainActivity::class.java)
            .putExtra(org.desklink.mobile.ui.MainActivity.EXTRA_DEVICE_ID, deviceId)
            .putExtra(org.desklink.mobile.ui.MainActivity.EXTRA_OPEN_FILES, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun words(context: Context) = Words(
        sending = context.getString(R.string.transfer_note_sending), receiving = context.getString(R.string.transfer_note_receiving),
        transferring = context.getString(R.string.transfer_note_transferring), saving = context.getString(R.string.transfer_note_saving),
        files = context.getString(R.string.transfer_note_files), ofCount = context.getString(R.string.transfer_note_of),
        about = context.getString(R.string.bucket_about), perSecond = context.getString(R.string.bucket_per_second),
        waitingFor = context.getString(R.string.transfer_note_waiting), paused = context.getString(R.string.transfer_note_paused),
        storageFull = context.getString(R.string.transfer_note_storage_full), publicText = context.getString(R.string.transfer_note_public),
        eta = org.desklink.mobile.plugins.folders.BucketSyncNotification.words(context),
    )

    /** Cancel (the whole set), Pause sync and Resume from the notification. Not exported: only our PendingIntent reaches it. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val deviceId = intent.getStringExtra(EXTRA_DEVICE) ?: return
            when (intent.action) {
                ACTION_CANCEL -> cancelAll(context, deviceId)
                ACTION_PAUSE -> BucketEngine.setPaused(deviceId, true)
                ACTION_RESUME -> BucketEngine.setPaused(deviceId, false)
            }
        }
    }

    /** Everything of this computer that is moving, except the Bucket's own sync: sends by their jobs, receives and cut sends by the transfer layer. */
    fun cancelAll(context: Context, deviceId: String) {
        val app = DeskLinkApplication.getInstance()
        val share = app.getDevicePlugin(deviceId, SharePlugin::class.java)
        jobIds(deviceId).forEach { runCatching { share?.cancelJob(it) } }
        runCatching { app.webRtcCoordinator(deviceId)?.cancelFileTransfers() }
    }
}
