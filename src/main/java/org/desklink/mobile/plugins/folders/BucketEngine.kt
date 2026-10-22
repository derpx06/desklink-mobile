/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.folders

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.helpers.DeviceHelper
import org.desklink.mobile.helpers.RateLimiter
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** F8 P4: which pair a file belongs to and where (an offer's `folder`/`relpath`/`mtime`). */
data class FolderRef(val folder: String, val relpath: String, val mtime: Long?)

/** What the Bucket screen shows for one item. */
data class BucketItem(
    val name: String,
    val size: Long,
    val mtime: Long,
    /** Where it was put in: this phone, or the desk; null when not known. */
    val from: Side?,
    val state: State,
    /** The document, once it is on this phone. */
    val uri: Uri?,
    /** While it is sending or receiving: how far, and the time left. */
    val progress: Progress? = null,
) {
    enum class State { SYNCED, SENDING, RECEIVING, WAITING, CONFLICT }
}

/** The Bucket with one desk, as the screens show it. */
data class BucketView(
    val deviceId: String,
    /** Null until the Bucket is on (or while asking the desk). */
    val treeUri: String?,
    val phase: Phase,
    val items: List<BucketItem>,
    /** Subfolders someone put in the folder: not synced (F8b scope). */
    val notSynced: List<String>,
    val lastSync: Long?,
    val bytes: Long,
    /** The desk wants to turn the Bucket on: the phone picks its folder. */
    val offered: Boolean,
    /** An incoming file was refused for lack of space. */
    val storageFull: Boolean,
    /** Files being copied in: done / total, or null. */
    val adding: Pair<Int, Int>?,
    /** Everything in flight together (header, Home card, notification); null when nothing moves. */
    val summary: ProgressSummary? = null,
    /** "Pause sync" from the notification or the menu. */
    val userPaused: Boolean = false,
)

enum class Phase { OFF, ASKING, WAITING, NOT_ON_DESK, SCANNING, SYNCING, UP_TO_DATE, TOO_MANY, NO_ACCESS, HELD_EMPTY }

/**
 * F8b, the Bucket (contract "DeskLink 1.2 — F8b the Bucket", files only for now): one shared folder
 * per desk, the F8 pair with id `bucket`. Everything put in on either side shows up on the other;
 * removing an item removes it on both. The F8 rules underneath are [FolderSync]'s. One worker thread
 * does all the work, and only while that desk's link is feature-ready.
 */
object BucketEngine {
    private const val TAG = "DeskLink/Bucket"
    private const val STATE_FILE = "bucket/state.json"
    const val STORED_VERSION = 1

    /** P1: an offer not answered in this long lapses. */
    const val OFFER_LAPSES_MS = 10 * 60_000L
    /** A change is synced once the folder has been quiet this long. */
    const val SETTLE_MS = 2_000L
    /** Rescans while the screen is on, and while it is off (SAF providers rarely report changes). */
    const val RESCAN_SCREEN_ON_MS = 60_000L
    const val RESCAN_SCREEN_OFF_MS = 15 * 60_000L
    /** Packets a minute from one desk (the desk's own bound). */
    const val MAX_PACKETS_PER_MINUTE = 1200

    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val limiter = RateLimiter(MAX_PACKETS_PER_MINUTE, 60_000, TAG)
    private lateinit var app: Context

    private class Live(val deviceId: String, var treeUri: String) {
        var manifest = HashMap<String, Entry>()
        /** Who put each item in (local knowledge, for "From <device>"). */
        val from = HashMap<String, Side>()
        /** The desk's last manifest: what it holds (path → sha256). */
        var remote = HashMap<String, String>()
        val conflicts = LinkedHashSet<String>()
        var lastSync: Long? = null
        var phase = Phase.WAITING
        var dirtyAt: Long? = null
        var scannedAt: Long? = null
        var onDesk = true
        var confirmedEmpty = false
        var notSynced = emptyList<String>()
        var storageFull = false
        val incoming = sortedMapOf<Int, List<Pair<String, Entry>>>()
        val toRequest = ArrayDeque<String>()
        val outstanding = LinkedHashSet<String>()
        val sending = HashMap<String, String>()
        var observer: ContentObserver? = null
        var adding: Pair<Int, Int>? = null
        /** From the last scan: each item's document id, for opening and thumbnails. */
        var documents = emptyMap<String, String>()
        /** Files moving now (path → how far), kept across a dropped link (they resume). */
        val tracks = HashMap<String, Track>()
        /** Files moved since the Bucket was last up to date, for "3 files synced". */
        val burst = HashSet<String>()
        var userPaused = false
    }

    private class Track(val total: Long, val sending: Boolean, startedAt: Long) {
        var done = 0L
        var paused = false
        val meter = SpeedMeter(startedAt)

        fun progress(now: Long) = Progress(done, total, meter.etaSeconds(total - done, now), meter.bytesPerSecond(now), paused)
    }

    private val buckets = LinkedHashMap<String, Live>()
    private val ready = HashSet<String>()
    /** The phone asked the desk to turn the Bucket on: device → (tree, at). */
    private val asked = HashMap<String, Pair<String, Long>>()
    /** The desk asked: device → at. */
    private val offers = HashMap<String, Long>()
    /** P4: files this phone asked for: (device, path) → entry. */
    private val wanted = HashMap<Pair<String, String>, Entry>()
    private val cancelAdding = AtomicBoolean(false)

    private val _views = MutableStateFlow<Map<String, BucketView>>(emptyMap())
    val views: StateFlow<Map<String, BucketView>> = _views

    // ---- lifecycle -------------------------------------------------------------------------------------------

    @Synchronized
    fun start(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        worker.execute {
            load()
            publish()
            buckets.values.forEach { live -> runCatching { tree(live)?.cleanTemps() } }
            File(app.cacheDir, "bucket-stash").listFiles()?.forEach { it.delete() }
        }
        worker.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { Log.w(TAG, "Bucket tick failed", it) } }, 5, 5, TimeUnit.SECONDS)
    }

    fun link(deviceId: String, up: Boolean) = worker.execute {
        if (up) {
            ready += deviceId
            val ids = if (deviceId in buckets) listOf(FolderSync.BUCKET_ID) else emptyList()
            send(deviceId, packet("pairs").apply { this["ids"] = ids })
            buckets[deviceId]?.let { it.dirtyAt = 0; it.scannedAt = null; it.onDesk = true }
        } else {
            down(deviceId)
        }
        publish()
    }

    private fun down(deviceId: String) {
        ready -= deviceId
        buckets[deviceId]?.let {
            it.phase = Phase.WAITING
            // Shown "Paused": each resumes from its checkpoint on the next connection.
            it.tracks.values.forEach { track -> track.paused = true }
            it.incoming.clear(); it.toRequest.clear(); it.outstanding.clear(); it.sending.clear()
        }
        synchronized(wanted) { wanted.keys.removeAll { it.first == deviceId } }
    }

    // ---- the person's actions -----------------------------------------------------------------------------------

    /** "Turn on": keeps the grant; answers the desk's offer, or asks the desk (P1, `bucket: true`). */
    fun turnOn(context: Context, deviceId: String, tree: Uri): Boolean {
        if (!keepGrant(context, tree)) return false
        worker.execute {
            if (offers.remove(deviceId) != null) {
                buckets[deviceId] = Live(deviceId, tree.toString()).also { it.dirtyAt = 0; watch(it) }
                save()
                send(deviceId, packet("accept").apply { this["id"] = FolderSync.BUCKET_ID })
            } else {
                asked[deviceId] = tree.toString() to SystemClock.elapsedRealtime()
                send(deviceId, packet("offer").apply {
                    this["id"] = FolderSync.BUCKET_ID
                    this["name"] = "DeskLink Bucket"
                    this["bucket"] = true
                })
            }
            publish()
        }
        return true
    }

    /** "Turn off": the Bucket stops on both sides; its files stay where they are. */
    fun turnOff(deviceId: String) = worker.execute {
        val live = buckets.remove(deviceId)
        val tree = live?.treeUri ?: asked.remove(deviceId)?.first ?: return@execute
        send(deviceId, packet("remove").apply { this["id"] = FolderSync.BUCKET_ID })
        live?.let(::unwatch)
        release(tree)
        save()
        publish()
    }

    fun decline(deviceId: String) = worker.execute {
        offers.remove(deviceId) ?: return@execute
        send(deviceId, packet("decline").apply { this["id"] = FolderSync.BUCKET_ID })
        publish()
    }

    /** P6: "It's empty on purpose": the next scan may record the files as removed. */
    fun confirmEmpty(deviceId: String) = worker.execute {
        buckets[deviceId]?.let { it.confirmedEmpty = true; it.dirtyAt = 0 }
    }

    fun syncNow(deviceId: String) = worker.execute { buckets[deviceId]?.let { it.dirtyAt = 0; it.scannedAt = null } }

    /** Copies [uris] into the Bucket (files only), with progress; [cancelAdd] stops between files. */
    fun add(deviceId: String, uris: List<Uri>, onDone: (added: Int, skipped: Int) -> Unit) {
        cancelAdding.set(false)
        worker.execute {
            val live = buckets[deviceId] ?: return@execute onDone(0, uris.size)
            val tree = tree(live) ?: return@execute onDone(0, uris.size)
            var added = 0
            var skipped = 0
            for ((index, uri) in uris.withIndex()) {
                if (cancelAdding.get()) break
                live.adding = index to uris.size
                publish()
                val ok = runCatching { copyIn(tree, live, uri) }.onFailure { Log.w(TAG, "Could not add a file: ${it.javaClass.simpleName}") }.getOrDefault(false)
                if (ok) added++ else skipped++
            }
            live.adding = null
            live.dirtyAt = SystemClock.elapsedRealtime()
            publish()
            onDone(added, skipped)
        }
    }

    fun cancelAdd() = cancelAdding.set(true)

    /** A pasted or shared text or link, saved as a file (F8b, ≤ 1 MiB). */
    fun addText(deviceId: String, text: String, onDone: (Boolean) -> Unit) = worker.execute {
        val live = buckets[deviceId]
        val tree = live?.let(::tree)
        val (name, bytes) = FolderSync.textItem(text, System.currentTimeMillis()) ?: return@execute onDone(false)
        if (tree == null) return@execute onDone(false)
        val staged = File(stash(), UUID.randomUUID().toString())
        try {
            staged.writeBytes(bytes)
            val placed = tree.place(unique(tree, name), staged, null)
            if (placed != null) live.dirtyAt = SystemClock.elapsedRealtime()
            onDone(placed != null)
        } finally {
            staged.delete()
        }
    }

    private fun copyIn(tree: SafTree, live: Live, uri: Uri): Boolean {
        val resolver = app.contentResolver
        val (rawName, size) = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) (c.getString(0) ?: "File") to (if (c.isNull(1)) -1L else c.getLong(1)) else null
        } ?: ((uri.lastPathSegment ?: "File") to -1L)
        if (size > FolderSync.MAX_FILE_BYTES) return false
        if (live.manifest.values.count { !it.deleted } >= FolderSync.MAX_BUCKET_ITEMS) return false
        val name = safeName(rawName)
        val staged = File(stash(), UUID.randomUUID().toString())
        try {
            resolver.openInputStream(uri)?.use { input -> staged.outputStream().use { input.copyTo(it) } } ?: return false
            if (staged.length() > FolderSync.MAX_FILE_BYTES) return false
            return tree.place(unique(tree, name), staged, null) != null
        } finally {
            staged.delete()
        }
    }

    /** A shared file's name, made a valid top-level name (no folders in the Bucket). */
    fun safeName(raw: String): String {
        val cleaned = raw.substringAfterLast('/').filter { it != '\\' && it != '\u0000' && !it.isISOControl() }.trim().trimStart('.')
        var name = cleaned.ifBlank { "File" }
        while (name.toByteArray().size > FolderSync.MAX_SEGMENT_BYTES) name = name.dropLast(1)
        return name
    }

    private fun unique(tree: SafTree, name: String): String {
        if (tree.find(name) == null) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        for (n in 2..999) {
            val candidate = name.substring(0, dot) + " ($n)" + name.substring(dot)
            if (FolderSync.validSegment(candidate) && tree.find(candidate) == null) return candidate
        }
        return name.substring(0, dot) + " " + UUID.randomUUID().toString().take(8) + name.substring(dot)
    }

    /** "Rename": the old name is removed and the new one added on both sides (renames are found by hash). */
    fun rename(deviceId: String, name: String, newName: String, onDone: (Boolean) -> Unit) = worker.execute {
        val live = buckets[deviceId]
        val tree = live?.let(::tree)
        val target = safeName(newName)
        val item = tree?.find(name)
        val ok = item != null && FolderSync.validSegment(target) && !FolderSync.ignored(target, false) && tree.find(target) == null && tree.rename(item, target)
        if (ok) live?.dirtyAt = SystemClock.elapsedRealtime()
        onDone(ok)
    }

    /** "Remove from both": deleted here, and the tombstone removes it on the desk (to its trash). */
    fun remove(deviceId: String, name: String) = worker.execute {
        val live = buckets[deviceId] ?: return@execute
        tree(live)?.find(name)?.let { tree(live)?.delete(it) }
        live.conflicts -= name
        live.dirtyAt = SystemClock.elapsedRealtime()
        publish()
    }

    /** A conflict copy: keep both, keep this phone's (it replaces the desk's), or keep the desk's (the copy goes). */
    enum class Resolve { BOTH, PHONE, DESK }

    fun resolve(deviceId: String, copy: String, how: Resolve) = worker.execute {
        val live = buckets[deviceId] ?: return@execute
        val tree = tree(live) ?: return@execute
        val original = Regex(" \\(conflict from .* \\d{4}-\\d{2}-\\d{2}\\)( \\d+)?").replace(copy, "")
        when (how) {
            Resolve.BOTH -> Unit
            Resolve.DESK -> tree.find(copy)?.let(tree::delete)
            Resolve.PHONE -> {
                val copyItem = tree.find(copy)
                if (copyItem != null && original != copy) {
                    tree.find(original)?.let(tree::delete)
                    tree.rename(copyItem, original)
                }
            }
        }
        live.conflicts -= copy
        live.dirtyAt = SystemClock.elapsedRealtime()
        publish()
    }

    private fun keepGrant(context: Context, tree: Uri) = runCatching {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }.isSuccess

    private fun release(tree: String) {
        if (buckets.values.any { it.treeUri == tree } || asked.values.any { it.first == tree }) return
        runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }

    private fun stash() = File(app.cacheDir, "bucket-stash").apply { mkdirs() }

    // ---- the desk's packets -------------------------------------------------------------------------------------

    fun onPacket(deviceId: String, packet: NetworkPacket) {
        if (!limiter.allow(deviceId)) return
        worker.execute { runCatching { handle(deviceId, packet) }.onFailure { Log.w(TAG, "Bucket packet failed", it) } }
    }

    private fun handle(deviceId: String, packet: NetworkPacket) {
        val id = packet.getStringOrNull("id")
        val bucket = id == FolderSync.BUCKET_ID
        when (packet.getStringOrNull("op")) {
            "pairs" -> buckets[deviceId]?.let { live ->
                live.onDesk = FolderSync.BUCKET_ID in packet.getStringList("ids").orEmpty().take(64)
                if (!live.onDesk) live.phase = Phase.NOT_ON_DESK
            }
            "offer" -> when {
                // F8b: the Bucket is the only pair; any other offer is declined.
                !bucket || !packet.getBoolean("bucket", false) -> id?.takeIf { it.length in 1..64 }?.let { other ->
                    send(deviceId, packet("decline").apply { this["id"] = other })
                }
                deviceId in buckets -> send(deviceId, packet("accept").apply { this["id"] = FolderSync.BUCKET_ID })
                // Both turned it on at once: the phone's own ask already holds a folder.
                deviceId in asked -> {
                    val (tree, _) = asked.remove(deviceId)!!
                    buckets[deviceId] = Live(deviceId, tree).also { it.dirtyAt = 0; watch(it) }
                    save()
                    send(deviceId, packet("accept").apply { this["id"] = FolderSync.BUCKET_ID })
                }
                else -> {
                    offers[deviceId] = SystemClock.elapsedRealtime()
                    BucketNotice.show(app, deviceId)
                }
            }
            "accept" -> if (bucket) asked.remove(deviceId)?.let { (tree, _) ->
                buckets[deviceId] = Live(deviceId, tree).also { it.dirtyAt = 0; watch(it) }
                save()
            }
            "decline" -> if (bucket) asked.remove(deviceId)?.let { (tree, _) -> release(tree) }
            "remove" -> if (bucket) {
                buckets.remove(deviceId)?.let { unwatch(it); release(it.treeUri) }
                asked.remove(deviceId)?.let { release(it.first) }
                offers.remove(deviceId)
                save()
            }
            "manifest" -> if (bucket) onManifest(deviceId, packet)
            "request" -> if (bucket) onRequest(deviceId, packet)
        }
        publish()
    }

    private fun onManifest(deviceId: String, packet: NetworkPacket) {
        val live = buckets[deviceId] ?: return
        val parts = packet.getInt("parts", 0)
        val part = packet.getInt("part", 0)
        if (parts !in 1..FolderSync.MAX_PARTS || part !in 1..parts) return
        live.onDesk = true
        if (part == 1) live.incoming.clear()
        if (live.incoming.values.sumOf { it.size } >= FolderSync.MAX_BUCKET_ITEMS * 2) return live.incoming.clear()
        live.incoming[part] = entries(packet.getJSONArray("entries") ?: JSONArray(), fromDesk = true)
        if (live.incoming.size != parts) return
        val remote = live.incoming.values.flatten().take(FolderSync.MAX_BUCKET_ITEMS).toMap()
        live.incoming.clear()
        if (live.phase == Phase.HELD_EMPTY) return
        live.remote = HashMap(remote.filterValues { !it.deleted }.mapValues { it.value.sha256 })
        live.sending.entries.removeAll { (path, sha) -> live.remote[path] == sha }
        applyPlan(live, remote)
    }

    /** Manifest entries, bounded and checked; the desk's are also filtered by the phone's own rules (P6, F8b). */
    private fun entries(array: JSONArray, fromDesk: Boolean): List<Pair<String, Entry>> = (0 until minOf(array.length(), FolderSync.MAX_BUCKET_ITEMS * 2)).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        val path = o.opt("path") as? String ?: return@mapNotNull null
        val deleted = o.opt("deleted") == true
        val sha = (o.opt("sha256") as? String).orEmpty().lowercase()
        val size = (o.opt("size") as? Number)?.toLong() ?: return@mapNotNull null
        val mtime = (o.opt("mtime") as? Number)?.toLong() ?: return@mapNotNull null
        val vv = o.optJSONObject("vv") ?: return@mapNotNull null
        if (fromDesk && !FolderSync.takesIncoming(path, o.opt("dir") == true)) return@mapNotNull null
        val sane = FolderSync.validRelpath(path) && (deleted || (FolderSync.validSha(sha) && size in 0..FolderSync.MAX_FILE_BYTES))
        if (!sane) return@mapNotNull null
        path to Entry(size, mtime, sha, deleted, Vv(vv.optLong("desk"), vv.optLong("phone")))
    }

    private fun applyPlan(live: Live, remote: Map<String, Entry>) {
        val tree = tree(live) ?: return
        val steps = FolderSync.plan(Side.PHONE, live.manifest, remote, DeviceHelper.getDeviceName(app))
        val downloads = mutableListOf<Pair<String, Entry>>()
        var rescan = false
        for (step in steps) when (step) {
            is Step.Take -> live.manifest[step.path] = step.entry
            is Step.Bump -> live.manifest[step.path]?.let { live.manifest[step.path] = it.copy(vv = step.vv) }
            is Step.Delete -> if (delete(tree, live, step.path)) live.manifest[step.path] = step.entry else rescan = true
            is Step.Download -> downloads += step.path to step.entry
            // A move within the Bucket (a rename on the desk): renamed here, no transfer.
            is Step.Move -> {
                val item = tree.find(step.from)
                if (item != null && unchanged(item, live.manifest[step.from]) && tree.find(step.to) == null && tree.rename(item, step.to)) {
                    live.manifest[step.from] = step.tombstone
                    tree.find(step.to)?.let { placed -> live.manifest[step.to] = step.entry.copy(size = placed.size, mtime = placed.mtime) }
                    live.from[step.to] = live.from.remove(step.from) ?: Side.DESK
                } else {
                    downloads += step.to to step.entry
                    rescan = true
                }
            }
            // ponytail: a duplicate is fetched again rather than copied through SAF; correct, more bytes.
            is Step.Copy -> downloads += step.to to step.entry
            is Step.Rename -> {
                // P3: the phone's own version moves aside (" 2", " 3"… if taken); it reaches the desk as a new file.
                val item = tree.find(step.from)
                val target = freeName(tree, step.to)
                if (item == null || target == null || !unchanged(item, live.manifest[step.from]) || !tree.rename(item, target)) {
                    rescan = true
                } else {
                    live.conflicts += target
                    live.from[target] = Side.PHONE
                }
            }
            is Step.Conflict -> Unit
        }
        synchronized(wanted) {
            downloads.forEach { (path, entry) -> if (wanted.size < FolderSync.MAX_OUTSTANDING * 4) wanted[live.deviceId to path] = entry }
        }
        live.toRequest.clear()
        live.toRequest.addAll(downloads.map { it.first })
        live.outstanding.clear()
        if (rescan) live.dirtyAt = SystemClock.elapsedRealtime()
        requestMore(live)
        settle(live)
        save()
    }

    private fun freeName(tree: SafTree, path: String): String? {
        if (tree.find(path) == null) return path
        val dot = path.lastIndexOf('.').takeIf { it > 0 } ?: path.length
        for (n in 2..20) {
            val candidate = path.substring(0, dot) + " $n" + path.substring(dot)
            if (FolderSync.validSegment(candidate) && tree.find(candidate) == null) return candidate
        }
        return null
    }

    private fun unchanged(item: SafTree.Item?, entry: Entry?) = when {
        entry == null || entry.deleted -> item == null
        else -> item != null && item.size == entry.size && item.mtime == entry.mtime
    }

    private fun delete(tree: SafTree, live: Live, path: String): Boolean {
        val item = tree.find(path) ?: return true
        return unchanged(item, live.manifest[path]) && tree.delete(item)
    }

    private fun requestMore(live: Live) {
        if (live.userPaused) return
        val batch = mutableListOf<String>()
        while (live.outstanding.size < FolderSync.MAX_OUTSTANDING && batch.size < FolderSync.REQUEST_PATHS) {
            val path = live.toRequest.removeFirstOrNull() ?: break
            live.outstanding += path
            batch += path
        }
        if (batch.isNotEmpty()) send(live.deviceId, packet("request").apply { this["id"] = FolderSync.BUCKET_ID; this["paths"] = batch })
    }

    private fun settle(live: Live) {
        live.phase = if (live.toRequest.isEmpty() && live.outstanding.isEmpty()) {
            live.lastSync = System.currentTimeMillis()
            Phase.UP_TO_DATE
        } else {
            Phase.SYNCING
        }
    }

    /** P4: the desk asks for files; each is sent unless it changed or vanished meanwhile. */
    private fun onRequest(deviceId: String, packet: NetworkPacket) {
        val live = buckets[deviceId]?.takeIf { !it.userPaused } ?: return
        val device = DeskLinkApplication.getInstance().getDevice(deviceId) ?: return
        val tree = tree(live) ?: return
        for (path in packet.getStringList("paths").orEmpty().take(FolderSync.REQUEST_PATHS)) {
            val entry = live.manifest[path]?.takeIf { !it.deleted } ?: continue
            val item = tree.find(path)
            if (item == null || !unchanged(item, entry)) {
                live.dirtyAt = SystemClock.elapsedRealtime()
                continue
            }
            val input = runCatching { app.contentResolver.openInputStream(tree.uri(item)) }.getOrNull() ?: continue
            if (live.sending.size < FolderSync.MAX_OUTSTANDING) live.sending[path] = entry.sha256
            device.sendPacket(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
                this["filename"] = path.substringAfterLast('/')
                this["folder"] = FolderSync.BUCKET_ID
                this["relpath"] = path
                this["mtime"] = entry.mtime
                payload = NetworkPacket.Payload(input, entry.size)
            }, object : Device.SendPacketStatusCallback() {
                override fun onPayloadProgressChanged(percent: Int) = progress(deviceId, path, entry.size * percent.coerceIn(0, 100) / 100, entry.size, sending = true)

                override fun onSuccess() = worker.execute { finished(live, path); publish() }

                override fun onFailure(e: Throwable) {
                    Log.w(TAG, "A Bucket file was not sent: ${e.javaClass.simpleName}")
                    // A dropped link pauses it (it resumes from the checkpoint); anything else ends it.
                    val resuming = e is org.desklink.mobile.webrtc.TransferResumingException
                    worker.execute {
                        if (resuming) live.tracks[path]?.paused = true else { live.sending.remove(path); live.tracks.remove(path) }
                        publish()
                    }
                }
            })
            progress(deviceId, path, 0, entry.size, sending = true)
        }
    }

    // ---- progress -------------------------------------------------------------------------------------------------

    private val lastPosted = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Long>()
    @Volatile private var publishQueued = false
    private var lastPublishAt = 0L

    /**
     * How far a Bucket file has moved (from the transfer manager's chunks and acknowledgements, on
     * their threads). Posted to the worker at most [BucketProgress.UPDATE_EVERY_MS] apart per file.
     */
    fun progress(deviceId: String, path: String, done: Long, total: Long, sending: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val key = deviceId to path
        val last = lastPosted[key]
        if (last != null && now - last < BucketProgress.UPDATE_EVERY_MS && done < total && done > 0) return
        lastPosted[key] = now
        worker.execute {
            val live = buckets[deviceId] ?: return@execute
            val track = live.tracks.getOrPut(path) { Track(total, sending, now) }
            track.done = done.coerceIn(0, total)
            track.paused = false
            track.meter.update(track.done, now)
            live.burst += path
            publishSoon()
        }
    }

    private fun finished(live: Live, path: String) {
        live.tracks.remove(path)
        lastPosted.remove(live.deviceId to path)
        live.burst += path
    }

    /** At most 4 state emits a second, from the worker. */
    private fun publishSoon() {
        val wait = BucketProgress.UPDATE_EVERY_MS - (SystemClock.elapsedRealtime() - lastPublishAt)
        if (wait <= 0) return publish()
        if (publishQueued) return
        publishQueued = true
        worker.schedule({ publishQueued = false; publish() }, wait, TimeUnit.MILLISECONDS)
    }

    /** "Pause sync" / "Resume": nothing is requested or sent while paused; what is moving finishes. */
    fun setPaused(deviceId: String, paused: Boolean) = worker.execute {
        val live = buckets[deviceId] ?: return@execute
        live.userPaused = paused
        if (!paused) {
            live.dirtyAt = 0
            requestMore(live)
        }
        publish()
    }

    // ---- files the desk sends --------------------------------------------------------------------------------

    /** The transfer manager asks before accepting a synced file (P4): asked for, same content, and room for it. */
    fun accepts(deviceId: String, ref: FolderRef, sha256: String, size: Long): Boolean {
        if (ref.folder != FolderSync.BUCKET_ID) return false
        val asked = synchronized(wanted) { wanted[deviceId to ref.relpath] }?.let { it.sha256.equals(sha256, true) && it.size == size } == true
        if (!asked) return false
        val free = runCatching { minOf(StatFs(app.cacheDir.path).availableBytes, StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes) }.getOrDefault(Long.MAX_VALUE)
        if (!FolderSync.enoughSpace(free, size)) {
            worker.execute { buckets[deviceId]?.storageFull = true; publish() }
            return false
        }
        return true
    }

    /** A requested file arrived at [partial]; it is moved aside now and placed on the worker. */
    fun arrived(deviceId: String, ref: FolderRef, partial: File, sha256: String, size: Long) {
        val stash = File(stash(), UUID.randomUUID().toString())
        if (!partial.renameTo(stash)) {
            partial.delete()
            return
        }
        worker.execute {
            try {
                place(deviceId, ref, stash, sha256, size)
            } finally {
                stash.delete()
            }
        }
    }

    private fun place(deviceId: String, ref: FolderRef, stash: File, sha256: String, size: Long) {
        val entry = synchronized(wanted) { wanted.remove(deviceId to ref.relpath) } ?: return
        val live = buckets[deviceId] ?: return
        if (!entry.sha256.equals(sha256, true) || entry.size != size) return
        live.outstanding -= ref.relpath
        finished(live, ref.relpath)
        live.storageFull = false
        val tree = tree(live)
        val existing = tree?.find(ref.relpath)
        val placed = if (tree != null && unchanged(existing, live.manifest[ref.relpath])) tree.place(ref.relpath, stash, existing) else null
        if (placed != null) {
            live.manifest[ref.relpath] = entry.copy(size = placed.size, mtime = placed.mtime)
            live.from[ref.relpath] = Side.DESK
        } else {
            live.dirtyAt = SystemClock.elapsedRealtime()
        }
        requestMore(live)
        if (live.toRequest.isEmpty() && live.outstanding.isEmpty()) {
            settle(live)
            save()
            sendManifest(live)
        }
        publish()
    }

    // ---- scanning ---------------------------------------------------------------------------------------------

    private fun tick() {
        // A link that dropped: sync stops until the next feature-ready.
        ready.filter { DeskLinkApplication.getInstance().getDevice(it)?.hasActiveWebRtcFeatureTransport != true }.forEach(::down)
        val now = SystemClock.elapsedRealtime()
        offers.entries.removeAll { now - it.value > OFFER_LAPSES_MS }
        asked.entries.removeAll { (_, v) -> (now - v.second > OFFER_LAPSES_MS).also { if (it) release(v.first) } }
        val screenOn = app.getSystemService(PowerManager::class.java)?.isInteractive != false
        for (live in buckets.values) {
            if (live.deviceId !in ready || !live.onDesk) continue
            val every = if (screenOn) RESCAN_SCREEN_ON_MS else RESCAN_SCREEN_OFF_MS
            val due = live.dirtyAt?.let { now - it >= SETTLE_MS } == true || live.scannedAt?.let { now - it >= every } != false
            if (due) rescan(live)
        }
        publish()
    }

    private fun rescan(live: Live) {
        val first = live.scannedAt == null
        live.dirtyAt = null
        live.scannedAt = SystemClock.elapsedRealtime()
        val tree = tree(live) ?: return
        live.phase = Phase.SCANNING
        val (found, folders) = runCatching { tree.scanTop() }.getOrElse {
            live.phase = Phase.NO_ACCESS
            return
        }
        live.notSynced = folders
        live.documents = found.mapValues { it.value.documentId }
        val files = found.filterKeys { !FolderSync.ignored(it, false) }
        val stats = files.mapValues { Stat(it.value.size, it.value.mtime) }
        // P6: a Bucket that had files and now scans empty is held, never emptied on the desk.
        if (FolderSync.holdEmpty(live.manifest, stats, live.confirmedEmpty)) {
            live.phase = Phase.HELD_EMPTY
            return
        }
        live.confirmedEmpty = false
        val keep = live.toRequest.toSet() + live.outstanding
        val before = live.manifest.filterValues { !it.deleted }.keys
        val changed = FolderSync.applyScan(Side.PHONE, live.manifest, stats, { path -> files[path]?.let(tree::sha256) }, System.currentTimeMillis(), keep)
        live.manifest.filterValues { !it.deleted }.keys.filter { it !in before && it !in live.from }.forEach { live.from[it] = Side.PHONE }
        live.from.keys.retainAll(live.manifest.keys)
        live.conflicts.retainAll(files.keys)
        live.phase = when {
            live.manifest.values.count { !it.deleted } >= FolderSync.MAX_BUCKET_ITEMS -> Phase.TOO_MANY
            live.toRequest.isEmpty() && live.outstanding.isEmpty() -> Phase.UP_TO_DATE.also { live.lastSync = live.lastSync ?: System.currentTimeMillis() }
            else -> Phase.SYNCING
        }
        if (changed) save()
        if (changed || first) sendManifest(live)
    }

    private fun tree(live: Live): SafTree? {
        val uri = Uri.parse(live.treeUri)
        val granted = app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        if (!granted) {
            live.phase = Phase.NO_ACCESS
            return null
        }
        return SafTree(app.contentResolver, uri)
    }

    private fun watch(live: Live) {
        if (live.observer != null) return
        val uri = Uri.parse(live.treeUri)
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                worker.execute { buckets[live.deviceId]?.dirtyAt = SystemClock.elapsedRealtime() }
            }
        }
        runCatching {
            app.contentResolver.registerContentObserver(DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)), true, observer)
            live.observer = observer
        }
    }

    private fun unwatch(live: Live) {
        live.observer?.let { runCatching { app.contentResolver.unregisterContentObserver(it) } }
        live.observer = null
    }

    // ---- wire --------------------------------------------------------------------------------------------------

    private fun packet(op: String) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_FOLDERS).apply { this["op"] = op }

    private fun send(deviceId: String, packet: NetworkPacket) {
        DeskLinkApplication.getInstance().getDevice(deviceId)?.sendPacket(packet)
    }

    private fun sendManifest(live: Live) {
        if (live.deviceId !in ready) return
        manifestPackets(FolderSync.BUCKET_ID, live.manifest).forEach { send(live.deviceId, it) }
    }

    /** P2: the whole manifest, split so each packet stays under the budget. */
    fun manifestPackets(id: String, manifest: Map<String, Entry>): List<NetworkPacket> {
        val parts = mutableListOf(JSONArray())
        var size = 0
        for ((path, e) in manifest.toSortedMap()) {
            val value = entryJson(path, e)
            val length = value.toString().toByteArray().size + 1
            if (size + length > FolderSync.PACKET_BUDGET - 512) {
                parts += JSONArray()
                size = 0
            }
            size += length
            parts.last().put(value)
        }
        val count = minOf(parts.size, FolderSync.MAX_PARTS)
        return parts.take(count).mapIndexed { index, entries ->
            packet("manifest").apply {
                this["id"] = id
                this["part"] = index + 1
                this["parts"] = count
                this["entries"] = entries
            }
        }
    }

    private fun entryJson(path: String, e: Entry) = JSONObject().put("path", path).put("size", e.size).put("mtime", e.mtime).put("sha256", e.sha256)
        .put("vv", JSONObject().put("desk", e.vv.desk).put("phone", e.vv.phone)).apply { if (e.deleted) put("deleted", true) }

    // ---- storage (atomic, versioned, unreadable file kept aside) ---------------------------------------------

    private fun load() {
        val file = AtomicFile(File(app.filesDir, STATE_FILE))
        if (!file.baseFile.exists()) return
        val json = runCatching {
            JSONObject(String(file.readFully(), Charsets.UTF_8)).also { require(it.optInt("version", 1) <= STORED_VERSION) { "newer" } }
        }.getOrElse {
            Log.w(TAG, "The Bucket's state could not be read (${it.javaClass.simpleName}); set aside")
            file.baseFile.renameTo(File(file.baseFile.path + ".unreadable"))
            return
        }
        val array = json.optJSONArray("buckets") ?: return
        for (i in 0 until minOf(array.length(), 16)) {
            val o = array.optJSONObject(i) ?: continue
            val deviceId = o.optString("deviceId").takeIf(String::isNotEmpty) ?: continue
            val live = Live(deviceId, o.optString("treeUri").takeIf(String::isNotEmpty) ?: continue)
            live.lastSync = o.optLong("lastSync").takeIf { it > 0 }
            live.manifest = HashMap(entries(o.optJSONArray("entries") ?: JSONArray(), fromDesk = false).toMap())
            o.optJSONObject("from")?.let { f -> f.keys().forEach { k -> if (k in live.manifest) live.from[k] = if (f.optString(k) == "desk") Side.DESK else Side.PHONE } }
            o.optJSONArray("conflicts")?.let { c -> (0 until minOf(c.length(), 200)).forEach { live.conflicts += c.optString(it) } }
            buckets[deviceId] = live
            watch(live)
        }
    }

    private fun save() {
        val json = JSONObject().put("version", STORED_VERSION).put("buckets", JSONArray().apply {
            buckets.values.forEach { l ->
                put(JSONObject().put("deviceId", l.deviceId).put("treeUri", l.treeUri).put("lastSync", l.lastSync ?: 0)
                    .put("entries", JSONArray().apply { l.manifest.forEach { (p, e) -> put(entryJson(p, e)) } })
                    .put("from", JSONObject().apply { l.from.forEach { (p, s) -> put(p, if (s == Side.DESK) "desk" else "phone") } })
                    .put("conflicts", JSONArray(l.conflicts.toList())))
            }
        })
        val file = AtomicFile(File(app.filesDir, STATE_FILE).apply { parentFile?.mkdirs() })
        runCatching {
            val out = file.startWrite()
            try {
                out.write(json.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
        }.onFailure { Log.e(TAG, "Could not save the Bucket's state", it) }
    }

    private fun publish() {
        lastPublishAt = SystemClock.elapsedRealtime()
        val devices = buckets.keys + asked.keys + offers.keys
        _views.value = devices.associateWith { deviceId ->
            val live = buckets[deviceId]
            if (live == null) {
                BucketView(deviceId, asked[deviceId]?.first, if (deviceId in asked) Phase.ASKING else Phase.OFF, emptyList(), emptyList(), null, 0, deviceId in offers, false, null)
            } else {
                val tree = Uri.parse(live.treeUri)
                val local = live.manifest.filterValues { !it.deleted }
                val items = local.map { (name, e) ->
                    val state = when {
                        name in live.conflicts -> BucketItem.State.CONFLICT
                        name in live.outstanding || name in live.toRequest -> BucketItem.State.RECEIVING
                        name in live.sending -> BucketItem.State.SENDING
                        live.remote[name] == e.sha256 -> BucketItem.State.SYNCED
                        else -> BucketItem.State.WAITING
                    }
                    BucketItem(
                        name, e.size, e.mtime, live.from[name], state,
                        live.documents[name]?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) },
                        live.tracks[name]?.progress(lastPublishAt) ?: waiting(live, name, state, e.size),
                    )
                } + (live.toRequest + live.outstanding + live.tracks.filterValues { !it.sending }.keys).distinct().filter { it !in local }.map { name ->
                    val size = synchronized(wanted) { wanted[deviceId to name]?.size } ?: 0
                    BucketItem(name, size, System.currentTimeMillis(), Side.DESK, BucketItem.State.RECEIVING, null, live.tracks[name]?.progress(lastPublishAt) ?: Progress(0, size, null, 0, deviceId !in ready))
                }
                BucketView(
                    deviceId, tree.toString(), live.phase,
                    items.sortedByDescending { it.mtime }, live.notSynced, live.lastSync, local.values.sumOf { it.size },
                    false, live.storageFull, live.adding,
                    BucketProgress.summary(items.mapNotNull { it.progress }),
                    live.userPaused,
                )
            }
        }
        // The notification: every second at most while files move, then "up to date" once.
        for ((deviceId, view) in _views.value) {
            val live = buckets[deviceId] ?: continue
            if (BucketSyncNotification.update(app, view, deviceId in ready, live.burst.size)) live.burst.clear()
        }
    }

    /** A file the desk has asked for, or one queued to receive, before bytes move: "Waiting…". */
    private fun waiting(live: Live, name: String, state: BucketItem.State, size: Long): Progress? =
        if (state == BucketItem.State.SENDING || state == BucketItem.State.RECEIVING) Progress(0, size, null, 0, live.deviceId !in ready) else null
}
