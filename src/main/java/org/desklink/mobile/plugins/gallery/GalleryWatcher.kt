/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.gallery

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.desklink.mobile.Device
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.helpers.AppLog
import org.desklink.mobile.helpers.Guarded
import org.desklink.mobile.plugins.desksettings.DeskSettingsPlugin
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.webrtc.TransferResumingException
import org.desklink.mobile.webrtc.UriFileSource
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Gallery (W12): new screenshots and photos go to a computer that accepts them. Off by default, per computer; both this phone's
 * switch and the computer's (`galleryAccepted`) must be on. A ContentObserver on the two picked folders (no timer) finds a new image;
 * [kick] looks again when a link comes up or the computer's switch changes. One file at a time, nothing queued across an absence.
 */
object GalleryWatcher {
    private const val TAG = "DeskLink/Gallery"
    private val worker = Guarded.Scheduled("DeskLink-gallery")

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)
    fun onKey(id: String) = "gallery_on_$id"
    fun sinceKey(id: String, shots: Boolean) = "gallery_since_${if (shots) "shots" else "camera"}_$id"
    fun shotsKey(id: String) = "gallery_shots_$id"
    fun cameraKey(id: String) = "gallery_camera_$id"

    fun isOn(context: Context, id: String) = prefs(context).getBoolean(onKey(id), false)
    fun folders(context: Context, id: String): List<String> = listOfNotNull(prefs(context).getString(shotsKey(id), null), prefs(context).getString(cameraKey(id), null))

    /** Where the folder picker starts, e.g. "Pictures/Screenshots" on the phone's own storage. */
    fun pickerStart(path: String): Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$path")

    /** Turning it on starts the clock for both folders: what is in them now is never sent. Turning it off ends sends in flight. */
    fun setOn(context: Context, id: String, on: Boolean) {
        val now = System.currentTimeMillis()
        prefs(context).edit {
            putBoolean(onKey(id), on)
            if (on) { putLong(sinceKey(id, true), now); putLong(sinceKey(id, false), now) }
        }
        if (!on) {
            runCatching { DeskLinkApplication.getInstance().webRtcCoordinator(id)?.cancelGallerySends() }
            state(id).blocked = false
        }
        kick(context)
    }

    /** Whether a gallery send cut by a lost link may carry on: the switch is on and the computer still accepts. */
    fun mayResume(context: Context, id: String): Boolean =
        isOn(context, id) && DeskLinkApplication.getInstance().getDevice(id)?.getPlugin(DeskSettingsPlugin::class.java)?.settings?.galleryAccepted == true

    /**
     * The person picked a folder: the grant is kept across restarts, the one it replaces is let go (unless another slot or the Bucket
     * uses it), and what is in the new folder now is never sent.
     */
    fun setFolder(context: Context, id: String, shots: Boolean, tree: Uri): Boolean {
        val kept = runCatching {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (!kept) return false
        val key = if (shots) shotsKey(id) else cameraKey(id)
        val old = prefs(context).getString(key, null)
        prefs(context).edit { putString(key, tree.toString()); putLong(sinceKey(id, shots), System.currentTimeMillis()) }
        if (old != null && old != tree.toString()) releaseUnlessUsed(context, old)
        kick(context)
        return true
    }

    /** Whether [tree] is still some computer's gallery folder or a Bucket's. */
    private fun inUse(context: Context, tree: String): Boolean =
        prefs(context).all.any { (k, v) -> (k.startsWith("gallery_shots_") || k.startsWith("gallery_camera_")) && v == tree } ||
            org.desklink.mobile.plugins.folders.BucketEngine.views.value.values.any { it.treeUri == tree }

    private fun releaseUnlessUsed(context: Context, tree: String) {
        if (inUse(context, tree)) return
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private class State {
        @Volatile var sending = false
        @Volatile var sendingSince = 0L
        @Volatile var blocked = false
        var retryScheduled = false
        val budget = GalleryRules.Budget()
        /** Failures with no known reason, by file: let go after [GalleryRules.MAX_TRIES]. */
        val tries = HashMap<String, Int>()
    }

    /** A callback that never came (the queue was cleared) must not stall the gallery for good. */
    private const val SENDING_STALE_MS = 10 * 60_000L
    private const val POLL_MS = 60_000L
    private const val COALESCE_MS = 2_000L
    private var pollScheduled = false
    private var kickPending = false

    private val states = HashMap<String, State>()
    private val observers = HashMap<Pair<String, String>, ContentObserver>()
    private fun state(id: String) = synchronized(states) { states.getOrPut(id) { State() } }

    /** Looks again: observers for what is on, and one send in each computer that can take a file now. */
    fun kick(context: Context) {
        val app = context.applicationContext
        worker.execute { runCatching { reconcile(app) }.onFailure { Log.w(TAG, "gallery pass failed (${it.javaClass.simpleName})") } }
    }

    /** A burst of provider notifications is one pass, a moment later. */
    private fun kickSoon(context: Context) {
        val app = context.applicationContext
        synchronized(this) { if (kickPending) return; kickPending = true }
        runCatching { worker.schedule({ synchronized(this) { kickPending = false }; kick(app) }, COALESCE_MS, TimeUnit.MILLISECONDS) }
    }

    private fun reconcile(context: Context) {
        val devices = DeskLinkApplication.getInstance().devices.values.filter { it.isPaired }
        val wanted = devices.filter { isOn(context, it.deviceId) }.flatMap { d -> folders(context, d.deviceId).map { d.deviceId to it } }.toSet()
        synchronized(observers) {
            observers.keys.filter { it !in wanted }.forEach { key -> observers.remove(key)?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } } }
            wanted.filter { it !in observers }.forEach { key ->
                val tree = Uri.parse(key.second)
                val observer = object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) = kickSoon(context)
                }
                runCatching {
                    context.contentResolver.registerContentObserver(
                        DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), true, observer,
                    )
                    observers[key] = observer
                }
            }
        }
        var watching = false
        devices.forEach { device ->
            val s = state(device.deviceId)
            val accepted = device.getPlugin(DeskSettingsPlugin::class.java)?.settings?.galleryAccepted == true
            if (!accepted) s.blocked = false // a refusal "off" holds until the computer's switch has been seen off and on again
            if (s.sending && System.currentTimeMillis() - s.sendingSince > SENDING_STALE_MS) s.sending = false
            val ready = isOn(context, device.deviceId) && accepted && !s.blocked && device.hasActiveWebRtcFeatureTransport
            if (ready) watching = true
            if (ready && !s.sending) sendNext(context, device, s)
        }
        // The provider may not report a new picture in a picked folder: while a computer is taking them and connected, look again
        // every minute (one bounded listing). Nothing is scheduled otherwise.
        if (watching) {
            val first = synchronized(this) { (!pollScheduled).also { pollScheduled = true } }
            if (first) runCatching { worker.schedule({ synchronized(this) { pollScheduled = false }; kick(context) }, POLL_MS, TimeUnit.MILLISECONDS) }
        }
    }

    private fun sendNext(context: Context, device: Device, s: State) {
        val id = device.deviceId
        val ledger = GalleryRules.Ledger(File(context.filesDir, "gallery/$id.json"))
        val p = prefs(context)
        val sinces = listOf(true, false).associate { shots -> (if (shots) p.getString(shotsKey(id), null) else p.getString(cameraKey(id), null)) to p.getLong(sinceKey(id, shots), Long.MAX_VALUE) }
        val found = sinces.keys.filterNotNull().flatMap { tree -> GalleryRules.newest(list(context, Uri.parse(tree)).map { it.copy(folder = tree) }) }
        val next = GalleryRules.toSend(found, ledger, { sinces[it.folder] ?: Long.MAX_VALUE }, System.currentTimeMillis()).firstOrNull() ?: return
        if (!s.budget.allows(next.size)) {
            retryLater(context, s, s.budget.nextRoomAt() - android.os.SystemClock.elapsedRealtime())
            return
        }
        // Marked first, so a restart in the middle never sends it twice (a send cut by the link resumes by itself); a failure that
        // says "later", or that nobody can explain, takes the mark back.
        ledger.add(next.uri, next.mtime)
        s.sending = true
        s.sendingSince = System.currentTimeMillis()
        AppLog.event("gallery", "sending one image")
        device.sendBulk(
            NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
                this["filename"] = next.name
                this["gallery"] = true
                this["mimeType"] = next.mime
                this["mtime"] = next.mtime
                payload = NetworkPacket.Payload(next.size).also { it.source = UriFileSource(context, Uri.parse(next.uri)) }
            },
            object : Device.SendPacketStatusCallback() {
                override fun onSuccess() { s.budget.record(next.size); s.tries.remove(next.uri); s.sending = false; kick(context) }

                override fun onFailure(e: Throwable) {
                    s.sending = false
                    when (GalleryRules.refusalOf(e.message)) {
                        GalleryRules.Refusal.OFF -> { ledger.remove(next.uri, next.mtime); s.blocked = true }
                        GalleryRules.Refusal.LIMIT -> { ledger.remove(next.uri, next.mtime); retryLater(context, s, 10 * 60_000L) }
                        GalleryRules.Refusal.TYPE -> Unit // never again
                        GalleryRules.Refusal.NONE -> if (e !is TransferResumingException) {
                            // The queue gave up, the camera was still writing, the computer had no room: tried again later, a few times.
                            val n = (s.tries[next.uri] ?: 0) + 1
                            s.tries[next.uri] = n
                            if (n < GalleryRules.MAX_TRIES) { ledger.remove(next.uri, next.mtime); retryLater(context, s, 60_000L) }
                        }
                    }
                    AppLog.event("gallery", "send ended: ${GalleryRules.refusalOf(e.message).name.lowercase()}")
                }
            },
        )
    }

    private fun retryLater(context: Context, s: State, delayMs: Long) {
        if (s.retryScheduled) return
        s.retryScheduled = true
        runCatching { worker.schedule({ s.retryScheduled = false; kick(context) }, delayMs.coerceAtLeast(30_000L), TimeUnit.MILLISECONDS) }
    }

    /** One level of a picked folder: images are in Pictures/Screenshots and DCIM/Camera, not below. */
    private fun list(context: Context, tree: Uri): List<GalleryRules.Found> = runCatching {
        val parent = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        context.contentResolver.query(parent, columns, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val mime = c.getString(2) ?: continue
                    add(GalleryRules.Found(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString(), c.getString(1).orEmpty(), mime, c.getLong(3), c.getLong(4)))
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** Unpaired: its choices are in the default preferences (swept by id), its ledger and grants go here. */
    fun forgetDevice(context: Context, id: String) {
        val p = prefs(context)
        val mine = listOfNotNull(p.getString(shotsKey(id), null), p.getString(cameraKey(id), null))
        File(context.filesDir, "gallery/$id.json").delete()
        synchronized(states) { states.remove(id) }
        // A folder another computer's gallery (or a Bucket) also uses keeps its grant.
        val others = p.all.filterKeys { it.startsWith("gallery_shots_") || it.startsWith("gallery_camera_") }.filterKeys { !it.endsWith(id) }.values.toSet()
        val bucketTrees = org.desklink.mobile.plugins.folders.BucketEngine.views.value.values.mapNotNull { it.treeUri }.toSet()
        mine.filter { it !in others && it !in bucketTrees }.forEach { tree ->
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        kick(context)
    }
}
