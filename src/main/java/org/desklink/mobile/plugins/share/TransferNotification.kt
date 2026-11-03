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
    private const val EXTRA_SET = "set"

    /** One file moving: its numbers, as the notification needs them. */
    data class Item(
        val name: String, val sending: Boolean, val done: Long, val total: Long, val etaSeconds: Long?, val bytesPerSecond: Long,
        /** The link is gone (it resumes by itself), or the sync is paused. */
        val waiting: Boolean, val saving: Boolean = false, val bucket: Boolean = false,
        /** How it moves: [LANE_FAST], [LANE_WEBRTC], [LANE_RELAY] or null (not known). */
        val lane: String? = null,
    )

    const val LANE_FAST = "fast"
    const val LANE_WEBRTC = "webrtc"
    const val LANE_RELAY = "relay"

    /** By `device|direction|name` (all the notification knows of a file), then by transfer id: two files of one name each keep their own. */
    private val laneKinds = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, String>>()

    /** Which way a file is moving ([LANE_FAST], [LANE_WEBRTC] or [LANE_RELAY]), for the detail line; null [kind] forgets [transferId]. */
    fun lane(deviceId: String, transferId: String, name: String, upload: Boolean, kind: String?) {
        val key = "$deviceId|$upload|$name"
        if (kind == null) {
            laneKinds[key]?.let { kinds -> kinds.remove(transferId); if (kinds.isEmpty()) laneKinds.remove(key, kinds) }
        } else {
            laneKinds.computeIfAbsent(key) { java.util.concurrent.ConcurrentHashMap() }[transferId] = kind
        }
    }

    /** The lane every file of that name agrees on; null when none is known or two of them move differently (no word beats a wrong one). */
    private fun laneKindOf(deviceId: String, upload: Boolean, name: String): String? =
        laneKinds["$deviceId|$upload|$name"]?.values?.toSet()?.singleOrNull()

    enum class Action { CANCEL, PAUSE_SYNC, RESUME_SYNC, NONE }

    data class Note(val text: String, val fraction: Float, val indeterminate: Boolean, val action: Action)

    /** The notification's words (English defaults keep the builder testable without resources). */
    data class Words(
        val sending: String = "Sending", val receiving: String = "Receiving", val transferring: String = "Transferring",
        val saving: String = "Saving %s", val files: String = "%1\$s %2\$d files", val ofCount: String = "%1\$d of %2\$d",
        val percent: String = "%d%%", val about: String = "about %s", val perSecond: String = "%s/s", val join: String = " · ",
        val waitingFor: String = "Waiting for %s", val paused: String = "Paused", val storageFull: String = "Storage is full",
        val publicText: String = "DeskLink · transferring files",
        val laneFast: String = "fast, over Wi-Fi", val laneWebRtc: String = "over the secure link", val laneRelay: String = "through the internet (slower)",
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
        // "Paused" and "Storage is full" are the Bucket's: they say nothing about a plain file moving beside it.
        val bucketOnly = items.all { it.bucket }
        val userPaused = userPaused && bucketOnly
        val storageFull = storageFull && bucketOnly
        val total = sum.total + finishedBytes
        val fraction = if (total <= 0) 0f else ((sum.done + finishedBytes).toFloat() / total).coerceIn(0f, 1f)
        val action = when {
            items.all { it.bucket } && userPaused -> Action.RESUME_SYNC
            items.all { it.bucket } -> Action.PAUSE_SYNC
            else -> Action.CANCEL
        }
        // A file being put in place cannot be stopped half way: no button, the notice of the finished file follows.
        if (items.size == 1 && items[0].saving) return Note(w.saving.format(items[0].name), 0f, true, Action.NONE)
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
                // The lane, when every file in the set moves the same way.
                items.map { it.lane }.distinct().singleOrNull()?.let { when (it) { LANE_FAST -> w.laneFast; LANE_RELAY -> w.laneRelay; LANE_WEBRTC -> w.laneWebRtc; else -> null } },
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
        /** Changes whenever a new set starts: the Cancel of a notification shown for an earlier set does nothing. */
        var setId = 0
        /** Jobs that ended or were cancelled: a late progress call for one does not bring it back. */
        val ended = LinkedHashSet<Long>()
        val throttle = ActivityItems.Throttle(UPDATE_EVERY_MS)
        var trailing: Runnable? = null
    }

    private val desks = HashMap<String, Desk>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private fun desk(id: String) = synchronized(desks) { desks.getOrPut(id) { Desk() } }

    /** The computer a job belongs to; each desk's jobs are read under its own lock. */
    private fun deskOfJob(jobId: Long): Pair<String, Desk>? {
        val all = synchronized(desks) { desks.entries.map { it.key to it.value } }
        return all.firstOrNull { (_, d) -> synchronized(d) { jobId in d.jobs } }
    }

    private fun tombstone(d: Desk, jobId: Long) {
        d.ended.add(jobId)
        while (d.ended.size > 64) d.ended.remove(d.ended.first())
    }

    /** A transfer moved (sent or received): [percent] of [totalBytes], by [TransferProgress]. */
    fun progress(deviceId: String, jobId: Long, upload: Boolean, title: String, percent: Int?, totalBytes: Long) {
        val now = SystemClock.elapsedRealtime()
        val d = desk(deviceId)
        synchronized(d) {
            if (jobId in d.ended) return
            val existing = d.jobs[jobId]
            if (existing == null) {
                // A send that resumes after a lost link runs under a new id: the paused one it replaces goes.
                d.jobs.entries.removeAll { (_, j) -> j.waiting && j.upload == upload && j.name == title }
                if (d.jobs.isEmpty()) d.setId++
            }
            val job = existing ?: Job(upload, title, totalBytes, now).also { d.jobs[jobId] = it }
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
            if (jobId in d.ended) return
            if (d.jobs.isEmpty()) d.setId++
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
            tombstone(d, jobId)
            if (!failed) { d.finishedInSet++; d.finishedBytes += job.total }
        }
        refresh(id)
    }

    /** A receive cut by a lost link: its row goes, but the same transfer comes back on resume, so nothing is tombstoned. */
    fun suspended(jobId: Long) {
        val (id, d) = deskOfJob(jobId) ?: return
        synchronized(d) { d.jobs.remove(jobId) }
        refresh(id)
    }

    fun cancelled(jobId: Long) {
        val (id, d) = deskOfJob(jobId) ?: return
        synchronized(d) { d.jobs.remove(jobId); tombstone(d, jobId) }
        refresh(id)
    }

    /** Sends paused by a lost link of [deviceId] will never resume (discarded, unpaired): their "Waiting" goes. */
    fun dropWaiting(deviceId: String) {
        val d = desk(deviceId)
        synchronized(d) { d.jobs.entries.removeAll { (_, j) -> j.waiting } }
        refresh(deviceId)
    }

    /** The computer is unpaired: nothing of it stays on the shade. */
    fun forget(context: Context, deviceId: String) {
        val d = synchronized(desks) { desks.remove(deviceId) } ?: return
        clear(context, deviceId, d)
    }

    /**
     * At start nothing is moving: a transfer notification still on the shade belongs to a process that died (or to the Bucket's
     * earlier "bucket-sync" tag) and its buttons would do nothing. They go.
     */
    fun sweep(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val leftovers = runCatching {
            context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.filter {
                it.id == ID && (it.tag?.startsWith("transfer:") == true || it.tag?.startsWith("bucket-sync:") == true)
            }
        }.getOrDefault(emptyList())
        leftovers.forEach { runCatching { manager.cancel(it.tag, ID) } }
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTest() { synchronized(desks) { desks.values.forEach { it.trailing?.let(handler::removeCallbacks) }; desks.clear() } }

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

    /** Posts the current state, at most once a second: the first change at once, one trailing post for the rest. */
    private fun refresh(deviceId: String) {
        val context = runCatching { DeskLinkApplication.getInstance() as Context }.getOrNull() ?: return
        val d = desk(deviceId)
        val moving = synchronized(d) { d.jobs.isNotEmpty() || d.bucket.isNotEmpty() }
        if (!moving) { clear(context, deviceId, d); return }
        val wait = synchronized(d) { d.throttle.onChange(SystemClock.elapsedRealtime()) } ?: return
        if (wait == 0L) { post(context, deviceId, d); return }
        val trailing = Runnable {
            synchronized(d) { d.throttle.onFired(SystemClock.elapsedRealtime()); d.trailing = null }
            // Posted now, not asked again: asking would only schedule another wait for the same second.
            if (synchronized(d) { d.jobs.isNotEmpty() || d.bucket.isNotEmpty() }) post(context, deviceId, d) else clear(context, deviceId, d)
        }
        synchronized(d) { d.trailing = trailing }
        handler.postDelayed(trailing, wait)
    }

    private fun clear(context: Context, deviceId: String, d: Desk) {
        synchronized(d) {
            d.trailing?.let(handler::removeCallbacks); d.trailing = null
            d.throttle.onFired(Long.MIN_VALUE / 4)
            d.finishedInSet = 0; d.finishedBytes = 0
            // Under the same lock as post: a post in flight cannot land after this cancel.
            if (d.showing) runCatching { NotificationManagerCompat.from(context).cancel(tag(deviceId), ID) }
            d.showing = false
        }
    }

    /** Ongoing only while bytes move: waiting or paused can be swiped, and goes by itself after ten minutes. */
    const val IDLE_TIMEOUT_MS = 10 * 60_000L

    @SuppressLint("MissingPermission")
    private fun post(context: Context, deviceId: String, d: Desk) = synchronized(d) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return@synchronized
        val desk = DeskNames.of(deviceId)
        val now = SystemClock.elapsedRealtime()
        val items = d.jobs.values.map { j ->
            val remaining = (j.total - j.done).coerceAtLeast(0)
            Item(j.name, j.upload, j.done, j.total, j.meter.etaSeconds(remaining, now), j.meter.bytesPerSecond(now), j.waiting, j.saving, lane = laneKindOf(deviceId, j.upload, j.name))
        } + d.bucket.map { it.copy(waiting = it.waiting || !d.bucketLinkUp || d.bucketPaused) }
        val w = words(context)
        val note = note(desk, items, d.finishedInSet, d.finishedBytes, d.bucketPaused, d.storageFull, { Sizes.iec(it) }, w) ?: return@synchronized
        val standing = items.all { it.waiting } || note.text == w.paused || note.text == w.storageFull
        val builder = base(context, deviceId)
            .setContentTitle(context.getString(R.string.transfer_note_title))
            .setContentText(note.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note.text))
            .setSubText(desk)
            .setProgress(1000, (note.fraction * 1000).toInt(), note.indeterminate)
            .setOngoing(!standing)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, NotificationHelper.Channels.FILETRANSFER_DOWNLOAD)
                    .setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(R.string.transfer_note_title))
                    .setContentText(w.publicText).setProgress(1000, (note.fraction * 1000).toInt(), note.indeterminate).build(),
            )
        if (standing) builder.setTimeoutAfter(IDLE_TIMEOUT_MS)
        val set = d.setId
        when (note.action) {
            Action.NONE -> Unit
            Action.CANCEL -> builder.addAction(R.drawable.ic_reject_pairing_24dp, context.getString(R.string.cancel), action(context, deviceId, ACTION_CANCEL, set))
            Action.PAUSE_SYNC -> builder.addAction(R.drawable.ic_reject_pairing_24dp, context.getString(R.string.bucket_pause), action(context, deviceId, ACTION_PAUSE, set))
            Action.RESUME_SYNC -> builder.addAction(R.drawable.ic_reject_pairing_24dp, context.getString(R.string.bucket_resume), action(context, deviceId, ACTION_RESUME, set))
        }
        runCatching {
            manager.notify(tag(deviceId), ID, builder.build())
            d.showing = true
        }
    }

    /** Each computer and action has its own PendingIntent: the data differs, so no two are the same to Android. */
    private fun action(context: Context, deviceId: String, action: String, set: Int): PendingIntent {
        val intent = Intent(context, Receiver::class.java).setAction(action).setData(android.net.Uri.parse("desklink://transfer/$deviceId/$action"))
            .putExtra(EXTRA_DEVICE, deviceId).putExtra(EXTRA_SET, set)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun base(context: Context, deviceId: String) = NotificationCompat.Builder(context, NotificationHelper.Channels.FILETRANSFER_DOWNLOAD)
        .setSmallIcon(R.drawable.ic_notification)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setContentIntent(openFiles(context, deviceId))

    private fun openFiles(context: Context, deviceId: String) = PendingIntent.getActivity(
        context, 0,
        Intent(context, org.desklink.mobile.ui.MainActivity::class.java)
            .setData(android.net.Uri.parse("desklink://transfer/$deviceId/open"))
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
        laneFast = context.getString(R.string.transfer_lane_fast), laneWebRtc = context.getString(R.string.transfer_lane_webrtc),
        laneRelay = context.getString(R.string.transfer_lane_relay),
    )

    /** The lane a moving file is on, in words, for Home's row; null when not known. */
    fun laneWord(context: Context, deviceId: String, upload: Boolean, name: String): String? = when (laneKindOf(deviceId, upload, name)) {
        LANE_FAST -> context.getString(R.string.transfer_lane_fast)
        LANE_WEBRTC -> context.getString(R.string.transfer_lane_webrtc)
        LANE_RELAY -> context.getString(R.string.transfer_lane_relay)
        else -> null
    }

    /** Cancel (the whole set), Pause sync and Resume from the notification. Not exported: only our PendingIntent reaches it. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val deviceId = intent.getStringExtra(EXTRA_DEVICE) ?: return
            when (intent.action) {
                ACTION_CANCEL -> cancelAll(context, deviceId, intent.getIntExtra(EXTRA_SET, -1))
                ACTION_PAUSE -> BucketEngine.setPaused(deviceId, true)
                ACTION_RESUME -> BucketEngine.setPaused(deviceId, false)
            }
        }
    }

    /**
     * Ends the set the notification was shown for (and only that set): its sends by their jobs, its receives and resumed sends by the
     * transfer layer. The Bucket's own sync and anything not in the notification are left alone. Its entries go at once, whether or
     * not their owners report.
     */
    fun cancelAll(context: Context, deviceId: String, set: Int) {
        val d = desk(deviceId)
        val ids = synchronized(d) { if (set != d.setId) return; d.jobs.keys.toList() }
        val app = DeskLinkApplication.getInstance()
        val share = app.getDevicePlugin(deviceId, SharePlugin::class.java)
        ids.forEach { runCatching { share?.cancelJob(it) } }
        runCatching { app.webRtcCoordinator(deviceId)?.cancelFileTransfers(ids.toSet()) }
        ids.forEach(::cancelled)
    }
}
