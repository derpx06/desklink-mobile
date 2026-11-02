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
    fun sinceKey(id: String) = "gallery_since_$id"
    fun shotsKey(id: String) = "gallery_shots_$id"
    fun cameraKey(id: String) = "gallery_camera_$id"

    fun isOn(context: Context, id: String) = prefs(context).getBoolean(onKey(id), false)
    fun folders(context: Context, id: String): List<String> = listOfNotNull(prefs(context).getString(shotsKey(id), null), prefs(context).getString(cameraKey(id), null))

    /** Turning it on starts the clock: what is in the folders now is never sent. */
    fun setOn(context: Context, id: String, on: Boolean) {
        prefs(context).edit {
            putBoolean(onKey(id), on)
            if (on) putLong(sinceKey(id), System.currentTimeMillis())
        }
        kick(context)
    }

    /** Where the folder picker starts, e.g. "Pictures/Screenshots" on the phone's own storage. */
    fun pickerStart(path: String): Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$path")

    /** The person picked a folder: the grant is kept across restarts. */
    fun setFolder(context: Context, id: String, shots: Boolean, tree: Uri): Boolean {
        val kept = runCatching {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (!kept) return false
        prefs(context).edit { putString(if (shots) shotsKey(id) else cameraKey(id), tree.toString()) }
        kick(context)
        return true
    }

    private class State {
        @Volatile var sending = false
        @Volatile var blocked = false
        var retryScheduled = false
        val budget = GalleryRules.Budget()
    }

    private val states = HashMap<String, State>()
    private val observers = HashMap<Pair<String, String>, ContentObserver>()
    private fun state(id: String) = synchronized(states) { states.getOrPut(id) { State() } }

    /** Looks again: observers for what is on, and one send in each computer that can take a file now. */
    fun kick(context: Context) {
        val app = context.applicationContext
        worker.execute { runCatching { reconcile(app) }.onFailure { Log.w(TAG, "gallery pass failed (${it.javaClass.simpleName})") } }
    }

    private fun reconcile(context: Context) {
        val devices = DeskLinkApplication.getInstance().devices.values.filter { it.isPaired }
        val wanted = devices.filter { isOn(context, it.deviceId) }.flatMap { d -> folders(context, d.deviceId).map { d.deviceId to it } }.toSet()
        synchronized(observers) {
            observers.keys.filter { it !in wanted }.forEach { key -> observers.remove(key)?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } } }
            wanted.filter { it !in observers }.forEach { key ->
                val tree = Uri.parse(key.second)
                val observer = object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) = kick(context)
                }
                runCatching {
                    context.contentResolver.registerContentObserver(
                        DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), true, observer,
                    )
                    observers[key] = observer
                }
            }
        }
        devices.forEach { device ->
            val s = state(device.deviceId)
            val accepted = device.getPlugin(DeskSettingsPlugin::class.java)?.settings?.galleryAccepted == true
            if (!accepted) s.blocked = false // a refusal "off" holds until the computer's switch has been seen off and on again
            if (isOn(context, device.deviceId) && accepted && !s.blocked && !s.sending && device.hasActiveWebRtcFeatureTransport) sendNext(context, device, s)
        }
    }

    private fun sendNext(context: Context, device: Device, s: State) {
        val id = device.deviceId
        val ledger = GalleryRules.Ledger(File(context.filesDir, "gallery/$id.json"))
        val since = prefs(context).getLong(sinceKey(id), Long.MAX_VALUE)
        val found = folders(context, id).flatMap { list(context, Uri.parse(it)) }
        val next = GalleryRules.toSend(found, ledger, since, System.currentTimeMillis()).firstOrNull() ?: return
        if (!s.budget.allows(next.size)) {
            retryLater(context, s, s.budget.nextRoomAt() - System.currentTimeMillis())
            return
        }
        // Marked first, so a restart in the middle never sends it again; a refusal that says "later" takes the mark back.
        ledger.add(next.uri, next.mtime)
        s.sending = true
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
                override fun onSuccess() { s.budget.record(next.size); s.sending = false; kick(context) }

                override fun onFailure(e: Throwable) {
                    s.sending = false
                    when (GalleryRules.refusalOf(e.message)) {
                        GalleryRules.Refusal.OFF -> { ledger.remove(next.uri, next.mtime); s.blocked = true }
                        GalleryRules.Refusal.LIMIT -> { ledger.remove(next.uri, next.mtime); retryLater(context, s, 10 * 60_000L) }
                        GalleryRules.Refusal.TYPE -> Unit // never again
                        GalleryRules.Refusal.NONE -> if (e is TransferResumingException) Unit else kick(context)
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
        // A folder another computer also uses keeps its grant.
        val others = p.all.filterKeys { it.startsWith("gallery_shots_") || it.startsWith("gallery_camera_") }.filterKeys { !it.endsWith(id) }.values.toSet()
        mine.filter { it !in others }.forEach { tree ->
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        kick(context)
    }
}
