/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.desklink.mobile.helpers.FilesHelper
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** A received file as it now exists for the person: where, under what name. */
data class Published(val uri: Uri, val name: String, val mime: String?, val size: Long)

/**
 * Where a verified, received file goes. The v2 receiver has already checked every block and the file hash, so this
 * does one streamed copy and nothing else (no second hash). It throws if the file is not completely there: the receiver
 * then tells the sender it failed instead of `complete`, and keeps the file for a retry ([UnpublishedFiles]).
 */
fun interface ReceiveDestination {
    @Throws(IOException::class)
    fun publish(source: File, name: String, lastModified: Long?, mime: String?): Published
}

/**
 * The phone's own destination: the folder chosen in Settings > Files (a document tree), else Downloads
 * (through MediaStore from Android 10, where an app may not create arbitrary files there directly).
 */
class DeviceStorageDestination(private val context: Context) : ReceiveDestination {
    override fun publish(source: File, name: String, lastModified: Long?, mime: String?): Published {
        val type = mime?.takeIf { it.isNotBlank() && it != "*/*" } ?: FilesHelper.getMimeTypeFromFile(name)
        val tree = if (ShareDestination.isCustomDestinationEnabled(context)) ShareDestination.getDestinationDirectory(context) else null
        return when {
            tree != null && tree.uri.scheme != "file" -> viaTree(tree, source, name, type)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> viaMediaStore(source, name, lastModified, type)
            else -> viaFile(ShareDestination.getDefaultDestinationDirectory(), source, name, lastModified, type)
        }
    }

    private fun viaTree(dir: DocumentFile, source: File, name: String, type: String): Published {
        val unique = FilesHelper.findValidNonExistingFileName(dir, name)
        val target = dir.createFile(type, unique) ?: throw IOException("Could not create $unique in the chosen folder")
        try {
            val out = context.contentResolver.openOutputStream(target.uri, "w") ?: throw IOException("Could not open $unique")
            out.use { copyWhole(source, it) }
        } catch (e: Exception) {
            target.delete()
            throw e as? IOException ?: IOException(e.message, e)
        }
        return Published(target.uri, target.name ?: unique, target.type ?: type, source.length())
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun viaMediaStore(source: File, name: String, lastModified: Long?, type: String): Published {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, type)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
            lastModified?.takeIf { it > 0 }?.let { put(MediaStore.Downloads.DATE_MODIFIED, it / 1000) }
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Could not create $name in Downloads")
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("Could not open $name")
            out.use { copyWhole(source, it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e as? IOException ?: IOException(e.message, e)
        }
        // MediaStore may have renamed it ("name (1).ext"): the person is told the name it really has.
        val shown = runCatching {
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: name
        return Published(uri, shown, type, source.length())
    }

    /** Before Android 10: a plain file in the public Downloads folder. */
    internal fun viaFile(dir: File, source: File, name: String, lastModified: Long?, type: String): Published {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create ${dir.path}")
        val unique = uniqueIn(dir, name)
        val target = File(dir, unique)
        try {
            target.outputStream().use { out -> copyWhole(source, out); out.flush(); (out as java.io.FileOutputStream).fd.sync() }
        } catch (e: Exception) {
            target.delete()
            throw e as? IOException ?: IOException(e.message, e)
        }
        lastModified?.takeIf { it > 0 }?.let { target.setLastModified(it) }
        runCatching { org.desklink.mobile.helpers.MediaStoreHelper.indexFile(context, Uri.fromFile(target)) }
        return Published(Uri.fromFile(target), unique, type, source.length())
    }

    companion object {
        /** `name`, else `name (1)`, `name (2)`… with the extension kept. */
        fun uniqueIn(dir: File, name: String): String {
            val clean = name.trimEnd('.').ifEmpty { "bad_name_file" }
            val base = File(clean).nameWithoutExtension
            val ext = File(clean).extension.let { if (it.isEmpty()) "" else ".$it" }
            var n = 0
            while (true) {
                val candidate = if (n == 0) clean else "$base ($n)$ext"
                if (!File(dir, candidate).exists()) return candidate
                n++
            }
        }

        /** One streamed copy; the whole of [source] must arrive or this throws. */
        fun copyWhole(source: File, out: OutputStream) {
            var copied = 0L
            source.inputStream().use { input -> copied = copy(input, out) }
            if (copied != source.length()) throw IOException("Only $copied of ${source.length()} bytes could be written")
        }

        private fun copy(input: InputStream, out: OutputStream): Long {
            val buffer = ByteArray(1 shl 20)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return total
                out.write(buffer, 0, n)
                total += n
            }
        }
    }
}

/**
 * Verified files that could not be published (no room, the chosen folder gone, a revoked grant): kept, in the app's own
 * storage, until the person tries again from the Files tab or a week has passed.
 */
object UnpublishedFiles {
    private const val TAG = "DeskLink/Files"
    private const val KEEP_MS = 7 * 24 * 3600 * 1000L

    data class Item(val id: String, val name: String, val size: Long, val mime: String?, val lastModified: Long?, val deviceId: String, val file: File)

    private fun dir(context: Context) = File(context.applicationContext.filesDir, "webrtc-unpublished").apply { mkdirs() }

    /** Moves [part] aside under a new id; null if it could not be kept (it is then still where it was). */
    fun keep(context: Context, part: File, name: String, mime: String?, lastModified: Long?, deviceId: String): Item? = runCatching {
        val id = UUID.randomUUID().toString()
        val target = File(dir(context), "$id.part")
        if (!part.renameTo(target)) return null
        File(dir(context), "$id.json").writeText(
            JSONObject().put("name", name).put("size", target.length()).put("mime", mime ?: JSONObject.NULL)
                .put("mtime", lastModified ?: 0L).put("device", deviceId).put("at", System.currentTimeMillis()).toString(),
        )
        Item(id, name, target.length(), mime, lastModified, deviceId, target)
    }.onFailure { Log.w(TAG, "could not keep an unpublished file", it) }.getOrNull()

    fun list(context: Context): List<Item> = dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { meta ->
        runCatching {
            val id = meta.name.removeSuffix(".json")
            val json = JSONObject(meta.readText())
            val file = File(meta.parentFile, "$id.part").takeIf { it.isFile } ?: return@runCatching null
            Item(id, json.getString("name"), file.length(), json.optString("mime").takeIf { json.has("mime") && !json.isNull("mime") }, json.optLong("mtime").takeIf { it > 0 }, json.getString("device"), file)
        }.getOrNull()
    }.sortedBy { it.name }

    /** Publishes it again; true when it is now in place (and the kept copy is gone). */
    fun retry(context: Context, id: String, destination: ReceiveDestination): Published? {
        val item = list(context).firstOrNull { it.id == id } ?: return null
        val published = runCatching { destination.publish(item.file, item.name, item.lastModified, item.mime) }
            .onFailure { Log.w(TAG, "retry could not publish ${item.name}", it) }.getOrNull() ?: return null
        discard(context, id)
        TransferHistory.recordReceived(context, item.deviceId, published.name, published.size, published.uri, published.mime)
        return published
    }

    fun discard(context: Context, id: String) {
        File(dir(context), "$id.part").delete()
        File(dir(context), "$id.json").delete()
        TransferHistory.changed()
    }

    /** What a week of waiting is worth: nothing; the sender still has the original. */
    fun prune(context: Context, now: Long = System.currentTimeMillis()) {
        dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .filter { now - it.lastModified() > KEEP_MS }
            .forEach { discard(context, it.name.removeSuffix(".json")) }
    }
}
