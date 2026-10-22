/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.folders

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
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

/** F8 P4: which pair a file belongs to and where (an offer's `folder`/`relpath`/`mtime`). */
data class FolderRef(val folder: String, val relpath: String, val mtime: Long?)

/** A phone folder synced with one desk. */
data class FolderPair(
    val id: String,
    val deviceId: String,
    val treeUri: String,
    val name: String,
    val includeHidden: Boolean = false,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
)

/** What the screen shows for a pair. */
enum class Phase { WAITING, NOT_ON_DESK, OFFERED, PAUSED_WIFI, PAUSED_CHARGING, SCANNING, SYNCING, UP_TO_DATE, TOO_MANY, NO_ACCESS }

data class PairView(val pair: FolderPair, val phase: Phase, val files: Int, val pending: Int, val lastSync: Long?)

/** A desk's offer to sync one of its folders, waiting for the person to pick a phone folder. */
data class Offer(val deviceId: String, val id: String, val name: String, val at: Long)

/**
 * F8 on the phone (contract "DeskLink 1.2 — F8 two-way folder sync"): pairs, manifests with version
 * vectors, the plan, and placing files in SAF trees. One worker thread does everything; it runs only
 * while that desk's link is feature-ready (inside the existing foreground service's lifetime).
 */
object FolderEngine {
    private const val TAG = "DeskLink/Folders"
    private const val PAIRS_FILE = "folders/pairs.json"
    const val STORED_VERSION = 1

    /** P1: an offer not answered in this long lapses. */
    const val OFFER_LAPSES_MS = 10 * 60_000L
    const val MAX_PENDING_OFFERS = 8
    /** A change is synced once the folder has been quiet this long. */
    const val SETTLE_MS = 2_000L
    /** Rescans while the screen is on, and (bounded) while it is off: SAF providers rarely report changes. */
    const val RESCAN_SCREEN_ON_MS = 60_000L
    const val RESCAN_SCREEN_OFF_MS = 15 * 60_000L
    /** Packets a minute from one desk (the desk's own bound). */
    const val MAX_PACKETS_PER_MINUTE = 1200

    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val limiter = RateLimiter(MAX_PACKETS_PER_MINUTE, 60_000, TAG)
    private lateinit var app: Context

    private class Live(var pair: FolderPair) {
        var manifest = HashMap<String, Entry>()
        var lastSync: Long? = null
        var phase = Phase.WAITING
        var dirtyAt: Long? = null
        var scannedAt: Long? = null
        var onDesk = true
        val incoming = sortedMapOf<Int, List<Pair<String, Entry>>>()
        val toRequest = ArrayDeque<String>()
        val outstanding = LinkedHashSet<String>()
        var observer: ContentObserver? = null
    }

    private val pairs = LinkedHashMap<String, Live>()
    private val ready = HashSet<String>()
    /** Phone-made offers waiting for the desk's accept: id → (pair, at). */
    private val asked = HashMap<String, Pair<FolderPair, Long>>()
    private val offers = ArrayList<Offer>()
    /** P4: files this phone asked for: (pair, path) → (desk, entry). */
    private val wanted = HashMap<Pair<String, String>, Pair<String, Entry>>()

    private val _views = MutableStateFlow<List<PairView>>(emptyList())
    val views: StateFlow<List<PairView>> = _views
    private val _offers = MutableStateFlow<List<Offer>>(emptyList())
    val pendingOffers: StateFlow<List<Offer>> = _offers

    // ---- lifecycle -------------------------------------------------------------------------------------------

    /** Reads the pairs (off the main thread) and starts the periodic check. */
    @Synchronized
    fun start(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        worker.execute {
            load()
            publish()
            pairs.values.forEach { live -> runCatching { tree(live)?.cleanTemps() } }
        }
        worker.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { Log.w(TAG, "Folder sync tick failed", it) } }, 5, 5, TimeUnit.SECONDS)
    }

    /** The desk's link came up or went down: sync runs only while up. */
    fun link(deviceId: String, up: Boolean) = worker.execute {
        if (up) {
            ready += deviceId
            send(deviceId, packet("pairs").apply { this["ids"] = pairs.values.filter { it.pair.deviceId == deviceId }.map { it.pair.id } })
            pairs.values.filter { it.pair.deviceId == deviceId }.forEach { it.dirtyAt = 0; it.scannedAt = null; it.onDesk = true }
        } else {
            ready -= deviceId
            pairs.values.filter { it.pair.deviceId == deviceId }.forEach {
                it.phase = Phase.WAITING
                it.incoming.clear(); it.toRequest.clear(); it.outstanding.clear()
            }
            synchronized(wanted) { wanted.entries.removeAll { it.value.first == deviceId } }
        }
        publish()
    }

    // ---- the person's actions ----------------------------------------------------------------------------------

    /** "Add a folder": keeps the grant and offers the folder to the desk (P1). False when it can't. */
    fun add(context: Context, deviceId: String, tree: Uri): Boolean {
        if (!keepGrant(context, tree)) return false
        worker.execute {
            if (pairs.values.count { it.pair.deviceId == deviceId } + asked.size >= FolderSync.MAX_PAIRS) return@execute
            val id = UUID.randomUUID().toString()
            val pair = FolderPair(id, deviceId, tree.toString(), nameOf(tree))
            asked[id] = pair to SystemClock.elapsedRealtime()
            send(deviceId, packet("offer").apply { this["id"] = id; this["name"] = pair.name })
            publish()
        }
        return true
    }

    /** A desk's offer, answered with a picked folder. */
    fun accept(context: Context, offer: Offer, tree: Uri): Boolean {
        if (!keepGrant(context, tree)) return false
        worker.execute {
            if (offers.removeAll { it.id == offer.id && it.deviceId == offer.deviceId } && offer.id !in pairs) {
                pairs[offer.id] = Live(FolderPair(offer.id, offer.deviceId, tree.toString(), offer.name)).also { it.dirtyAt = 0 }
                savePairs()
                watch(pairs.getValue(offer.id))
                send(offer.deviceId, packet("accept").apply { this["id"] = offer.id })
            }
            publish()
        }
        return true
    }

    fun decline(offer: Offer) = worker.execute {
        offers.removeAll { it.id == offer.id && it.deviceId == offer.deviceId }
        send(offer.deviceId, packet("decline").apply { this["id"] = offer.id })
        publish()
    }

    /** Stops syncing (both sides); files stay. The grant is given back. */
    fun remove(id: String) = worker.execute {
        val gone = pairs.remove(id)?.pair ?: asked.remove(id)?.first ?: return@execute
        send(gone.deviceId, packet("remove").apply { this["id"] = id })
        dropPair(gone)
        publish()
    }

    fun update(pair: FolderPair) = worker.execute {
        pairs[pair.id]?.let { it.pair = pair; it.dirtyAt = 0 }
        savePairs()
        publish()
    }

    fun syncNow(id: String) = worker.execute { pairs[id]?.let { it.dirtyAt = 0; it.scannedAt = null } }

    private fun keepGrant(context: Context, tree: Uri) = runCatching {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }.isSuccess

    private fun nameOf(tree: Uri) = runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/') }.getOrNull()
        ?.ifBlank { null }?.take(FolderSync.MAX_NAME_CHARS) ?: "Folder"

    private fun dropPair(pair: FolderPair) {
        File(app.filesDir, bookFile(pair.id)).delete()
        savePairs()
        if (pairs.values.none { it.pair.treeUri == pair.treeUri } && asked.values.none { it.first.treeUri == pair.treeUri }) {
            runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(pair.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
    }

    // ---- the desk's packets ------------------------------------------------------------------------------------

    fun onPacket(deviceId: String, packet: NetworkPacket) {
        if (!limiter.allow(deviceId)) return
        worker.execute { runCatching { handle(deviceId, packet) }.onFailure { Log.w(TAG, "Folder packet failed", it) } }
    }

    private fun validId(id: String?) = id != null && id.length in 1..64 && id.all { (it.isLetterOrDigit() && it.code < 128) || it == '-' }

    private fun handle(deviceId: String, packet: NetworkPacket) {
        val id = packet.getStringOrNull("id")?.takeIf(::validId)
        when (packet.getStringOrNull("op")) {
            "pairs" -> {
                val listed = packet.getStringList("ids").orEmpty().take(64).toSet()
                pairs.values.filter { it.pair.deviceId == deviceId }.forEach {
                    it.onDesk = it.pair.id in listed
                    if (!it.onDesk) it.phase = Phase.NOT_ON_DESK
                }
            }
            "offer" -> {
                id ?: return
                if (id in pairs || offers.any { it.id == id }) return
                val room = offers.size < MAX_PENDING_OFFERS && pairs.values.count { it.pair.deviceId == deviceId } < FolderSync.MAX_PAIRS
                if (!room) return send(deviceId, packet("decline").apply { this["id"] = id })
                val name = packet.getStringOrNull("name").orEmpty().filter { !it.isISOControl() }.take(FolderSync.MAX_NAME_CHARS).ifBlank { "Folder" }
                offers += Offer(deviceId, id, name, SystemClock.elapsedRealtime())
                FolderOfferNotice.show(app, deviceId, name)
            }
            "accept" -> {
                id ?: return
                val (pair, _) = asked[id]?.takeIf { it.first.deviceId == deviceId } ?: return
                asked.remove(id)
                pairs[id] = Live(pair).also { it.dirtyAt = 0 }
                savePairs()
                watch(pairs.getValue(id))
            }
            "decline" -> {
                id ?: return
                asked[id]?.takeIf { it.first.deviceId == deviceId }?.let { (pair, _) -> asked.remove(id); dropPair(pair) }
            }
            "remove" -> {
                id ?: return
                pairs[id]?.takeIf { it.pair.deviceId == deviceId }?.let { live -> pairs.remove(id); unwatch(live); dropPair(live.pair) }
                offers.removeAll { it.id == id }
                asked.remove(id)?.let { dropPair(it.first) }
            }
            "manifest" -> onManifest(deviceId, id ?: return, packet)
            "request" -> onRequest(deviceId, id ?: return, packet)
        }
        publish()
    }

    private fun onManifest(deviceId: String, id: String, packet: NetworkPacket) {
        val live = pairs[id]?.takeIf { it.pair.deviceId == deviceId } ?: return
        val parts = packet.getInt("parts", 0)
        val part = packet.getInt("part", 0)
        if (parts !in 1..FolderSync.MAX_PARTS || part !in 1..parts) return
        live.onDesk = true
        if (part == 1) live.incoming.clear()
        if (live.incoming.values.sumOf { it.size } >= FolderSync.MAX_FILES * 2) return live.incoming.clear()
        live.incoming[part] = entries(packet.getJSONArray("entries") ?: JSONArray())
        if (live.incoming.size != parts) return
        val remote = live.incoming.values.flatten().take(FolderSync.MAX_FILES).toMap()
        live.incoming.clear()
        if (!allowedNow(live)) return
        applyPlan(live, remote)
    }

    private fun entries(array: JSONArray): List<Pair<String, Entry>> = (0 until minOf(array.length(), FolderSync.MAX_FILES)).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        val path = o.opt("path") as? String ?: return@mapNotNull null
        val deleted = o.opt("deleted") == true
        val sha = (o.opt("sha256") as? String).orEmpty().lowercase()
        val size = (o.opt("size") as? Number)?.toLong() ?: return@mapNotNull null
        val mtime = (o.opt("mtime") as? Number)?.toLong() ?: return@mapNotNull null
        val vv = o.optJSONObject("vv") ?: return@mapNotNull null
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
            // ponytail: a move or copy is fetched again (SAF has no cheap cross-folder move); correct, just more bytes.
            is Step.Move -> {
                if (delete(tree, live, step.from)) live.manifest[step.from] = step.tombstone else rescan = true
                downloads += step.to to step.entry
            }
            is Step.Copy -> downloads += step.to to step.entry
            is Step.Rename -> {
                // P3: the phone's own version moves aside (" 2", " 3"… if taken); it reaches the desk as a new file.
                val item = tree.find(step.from)
                val target = freeName(tree, step.to)
                if (item == null || target == null || !unchanged(item, live.manifest[step.from]) || !tree.rename(item, target.substringAfterLast('/'))) rescan = true
            }
            is Step.Conflict -> Unit
        }
        synchronized(wanted) {
            downloads.forEach { (path, entry) -> if (wanted.size < FolderSync.MAX_OUTSTANDING * FolderSync.MAX_PAIRS * 4) wanted[live.pair.id to path] = live.pair.deviceId to entry }
        }
        live.toRequest.clear()
        live.toRequest.addAll(downloads.map { it.first })
        live.outstanding.clear()
        if (rescan) live.dirtyAt = SystemClock.elapsedRealtime()
        requestMore(live)
        settle(live)
        saveBook(live)
    }

    private fun freeName(tree: SafTree, path: String): String? {
        if (tree.find(path) == null) return path
        val dot = path.lastIndexOf('.').takeIf { it > path.lastIndexOf('/') + 1 } ?: path.length
        for (n in 2..20) {
            val candidate = path.substring(0, dot) + " $n" + path.substring(dot)
            if (FolderSync.validRelpath(candidate) && tree.find(candidate) == null) return candidate
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
        val batch = mutableListOf<String>()
        while (live.outstanding.size < FolderSync.MAX_OUTSTANDING && batch.size < FolderSync.REQUEST_PATHS) {
            val path = live.toRequest.removeFirstOrNull() ?: break
            live.outstanding += path
            batch += path
        }
        if (batch.isNotEmpty()) send(live.pair.deviceId, packet("request").apply { this["id"] = live.pair.id; this["paths"] = batch })
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
    private fun onRequest(deviceId: String, id: String, packet: NetworkPacket) {
        val live = pairs[id]?.takeIf { it.pair.deviceId == deviceId } ?: return
        val device = DeskLinkApplication.getInstance().getDevice(deviceId) ?: return
        val tree = tree(live) ?: return
        for (path in packet.getStringList("paths").orEmpty().take(FolderSync.REQUEST_PATHS)) {
            val entry = live.manifest[path]?.takeIf { !it.deleted } ?: continue
            val item = tree.find(path)
            if (!unchanged(item, entry)) {
                live.dirtyAt = SystemClock.elapsedRealtime()
                continue
            }
            val input = runCatching { app.contentResolver.openInputStream(tree.uri(item!!)) }.getOrNull() ?: continue
            device.sendPacket(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
                this["filename"] = path.substringAfterLast('/')
                this["folder"] = id
                this["relpath"] = path
                this["mtime"] = entry.mtime
                payload = NetworkPacket.Payload(input, entry.size)
            }, object : Device.SendPacketStatusCallback() {
                override fun onSuccess() = Unit
                override fun onFailure(e: Throwable) { Log.w(TAG, "A synced file was not sent: ${e.javaClass.simpleName}") }
            })
        }
    }

    // ---- files the desk sends --------------------------------------------------------------------------------

    /** The transfer manager asks before accepting a synced file (P4): asked for, from that desk, same content. */
    fun accepts(deviceId: String, ref: FolderRef, sha256: String, size: Long): Boolean = synchronized(wanted) {
        wanted[ref.folder to ref.relpath]?.let { (desk, entry) -> desk == deviceId && entry.sha256.equals(sha256, true) && entry.size == size } == true
    }

    /** A requested file arrived at [partial]; it is moved aside now and placed on the worker. */
    fun arrived(deviceId: String, ref: FolderRef, partial: File, sha256: String, size: Long) {
        val stash = File(app.cacheDir, "folder-stash").apply { mkdirs() }.let { File(it, UUID.randomUUID().toString()) }
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
        val (owner, entry) = synchronized(wanted) { wanted.remove(ref.folder to ref.relpath) } ?: return
        val live = pairs[ref.folder] ?: return
        if (owner != deviceId || !entry.sha256.equals(sha256, true) || entry.size != size) return
        live.outstanding -= ref.relpath
        val tree = tree(live)
        val existing = tree?.find(ref.relpath)
        val placed = if (tree != null && unchanged(existing, live.manifest[ref.relpath])) tree.place(ref.relpath, stash, existing) else null
        if (placed != null) {
            live.manifest[ref.relpath] = entry.copy(size = placed.size, mtime = placed.mtime)
        } else {
            live.dirtyAt = SystemClock.elapsedRealtime()
        }
        requestMore(live)
        if (live.toRequest.isEmpty() && live.outstanding.isEmpty()) {
            settle(live)
            saveBook(live)
            sendManifest(live)
        }
        publish()
    }

    // ---- scanning ---------------------------------------------------------------------------------------------

    private fun tick() {
        // A link that dropped: sync stops until the next feature-ready.
        ready.filter { DeskLinkApplication.getInstance().getDevice(it)?.hasActiveWebRtcFeatureTransport != true }.forEach { gone ->
            ready -= gone
            pairs.values.filter { it.pair.deviceId == gone }.forEach {
                it.phase = Phase.WAITING
                it.incoming.clear(); it.toRequest.clear(); it.outstanding.clear()
            }
            synchronized(wanted) { wanted.entries.removeAll { it.value.first == gone } }
        }
        val now = SystemClock.elapsedRealtime()
        offers.removeAll { now - it.at > OFFER_LAPSES_MS }
        asked.entries.removeAll { (_, v) -> (now - v.second > OFFER_LAPSES_MS).also { if (it) dropPair(v.first) } }
        val screenOn = app.getSystemService(PowerManager::class.java)?.isInteractive != false
        for (live in pairs.values) {
            if (live.pair.deviceId !in ready || !live.onDesk) continue
            if (!allowedNow(live)) continue
            val every = if (screenOn) RESCAN_SCREEN_ON_MS else RESCAN_SCREEN_OFF_MS
            val due = live.dirtyAt?.let { now - it >= SETTLE_MS } == true || live.scannedAt?.let { now - it >= every } != false
            if (due) rescan(live)
        }
        publish()
    }

    /** "Only on Wi-Fi" / "Only while charging". */
    private fun allowedNow(live: Live): Boolean {
        if (live.pair.wifiOnly) {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true && caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) != true) {
                live.phase = Phase.PAUSED_WIFI
                return false
            }
        }
        if (live.pair.chargingOnly) {
            val plugged = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            if (plugged == 0) {
                live.phase = Phase.PAUSED_CHARGING
                return false
            }
        }
        return true
    }

    private fun rescan(live: Live) {
        val first = live.scannedAt == null
        live.dirtyAt = null
        live.scannedAt = SystemClock.elapsedRealtime()
        val tree = tree(live) ?: return
        live.phase = Phase.SCANNING
        val (found, tooMany) = runCatching { tree.scan(live.pair.includeHidden) }.getOrElse {
            live.phase = Phase.NO_ACCESS
            return
        }
        val keep = live.toRequest.toSet() + live.outstanding
        val changed = FolderSync.applyScan(
            Side.PHONE, live.manifest, found.mapValues { Stat(it.value.size, it.value.mtime) },
            { path -> found[path]?.let(tree::sha256) }, System.currentTimeMillis(), keep,
        )
        live.phase = when {
            tooMany -> Phase.TOO_MANY
            live.toRequest.isEmpty() && live.outstanding.isEmpty() -> Phase.UP_TO_DATE
            else -> Phase.SYNCING
        }
        if (changed) saveBook(live)
        if (changed || first) sendManifest(live)
    }

    private fun tree(live: Live): SafTree? {
        val uri = Uri.parse(live.pair.treeUri)
        val granted = app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        if (!granted) {
            live.phase = Phase.NO_ACCESS
            return null
        }
        return SafTree(app.contentResolver, uri)
    }

    /** A change in the folder (when its provider reports one) marks it dirty; [SETTLE_MS] later it syncs. */
    private fun watch(live: Live) {
        if (live.observer != null) return
        val uri = Uri.parse(live.pair.treeUri)
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                worker.execute { pairs[live.pair.id]?.dirtyAt = SystemClock.elapsedRealtime() }
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
        val device = DeskLinkApplication.getInstance().getDevice(deviceId) ?: return
        if (deviceId in ready || packet.getStringOrNull("op") != "manifest") device.sendPacket(packet)
    }

    private fun sendManifest(live: Live) {
        if (live.pair.deviceId !in ready) return
        manifestPackets(live.pair.id, live.manifest).forEach { send(live.pair.deviceId, it) }
    }

    /** P2: the whole manifest, split so each packet stays under the budget. */
    fun manifestPackets(id: String, manifest: Map<String, Entry>): List<NetworkPacket> {
        val parts = mutableListOf(JSONArray())
        var size = 0
        for ((path, e) in manifest.toSortedMap()) {
            val value = JSONObject().put("path", path).put("size", e.size).put("mtime", e.mtime).put("sha256", e.sha256)
                .put("vv", JSONObject().put("desk", e.vv.desk).put("phone", e.vv.phone)).apply { if (e.deleted) put("deleted", true) }
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

    // ---- storage (atomic, versioned, unreadable files kept aside) ---------------------------------------------

    private fun bookFile(id: String) = "folders/$id.json"

    private fun read(name: String): JSONObject? {
        val file = AtomicFile(File(app.filesDir, name))
        if (!file.baseFile.exists()) return null
        return runCatching {
            JSONObject(String(file.readFully(), Charsets.UTF_8)).also { require(it.optInt("version", 1) <= STORED_VERSION) { "newer" } }
        }.getOrElse {
            Log.w(TAG, "A folder sync file could not be read (${it.javaClass.simpleName}); set aside")
            file.baseFile.renameTo(File(file.baseFile.path + ".unreadable"))
            null
        }
    }

    private fun write(name: String, json: JSONObject) {
        val file = AtomicFile(File(app.filesDir, name).apply { parentFile?.mkdirs() })
        runCatching {
            val out = file.startWrite()
            try {
                out.write(json.put("version", STORED_VERSION).toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
        }.onFailure { Log.e(TAG, "Could not save folder sync state", it) }
    }

    private fun load() {
        val json = read(PAIRS_FILE) ?: return
        val array = json.optJSONArray("pairs") ?: return
        for (i in 0 until minOf(array.length(), FolderSync.MAX_PAIRS * 4)) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf(::validId) ?: continue
            val pair = FolderPair(
                id, o.optString("deviceId"), o.optString("treeUri"), o.optString("name").take(FolderSync.MAX_NAME_CHARS),
                o.optBoolean("includeHidden"), o.optBoolean("wifiOnly", true), o.optBoolean("chargingOnly"),
            )
            val live = Live(pair)
            read(bookFile(id))?.let { book ->
                live.lastSync = book.optLong("lastSync").takeIf { it > 0 }
                live.manifest = HashMap(entries(book.optJSONArray("entries") ?: JSONArray()).toMap())
            }
            pairs[id] = live
            watch(live)
        }
    }

    private fun savePairs() = write(PAIRS_FILE, JSONObject().put("pairs", JSONArray().apply {
        pairs.values.forEach { l ->
            val p = l.pair
            put(JSONObject().put("id", p.id).put("deviceId", p.deviceId).put("treeUri", p.treeUri).put("name", p.name)
                .put("includeHidden", p.includeHidden).put("wifiOnly", p.wifiOnly).put("chargingOnly", p.chargingOnly))
        }
    }))

    private fun saveBook(live: Live) {
        val entries = manifestPackets(live.pair.id, live.manifest).flatMap { p -> p.getJSONArray("entries")!!.let { a -> (0 until a.length()).map(a::getJSONObject) } }
        write(bookFile(live.pair.id), JSONObject().put("lastSync", live.lastSync ?: 0).put("entries", JSONArray(entries)))
    }

    private fun publish() {
        _views.value = pairs.values.map { l ->
            PairView(l.pair, l.phase, l.manifest.values.count { !it.deleted }, l.toRequest.size + l.outstanding.size, l.lastSync)
        } + asked.values.map { (pair, _) -> PairView(pair, Phase.OFFERED, 0, 0, null) }
        _offers.value = offers.toList()
    }
}
