/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.content.ContentValues
import android.annotation.SuppressLint
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
data class Published(val uri: Uri, val name: String, val mime: String?, val size: Long, val fellBackToDownloads: Boolean = false)

/**
 * Where a verified, received file goes. The v2 receiver has already checked every block and the file hash, so this
 * does one streamed copy and nothing else (no second hash). It throws if the file is not completely there: the receiver
 * then tells the sender it failed instead of `complete`, and keeps the file for a retry ([UnpublishedFiles]).
 */
fun interface ReceiveDestination {
    @Throws(IOException::class)
    fun publish(source: File, name: String, lastModified: Long?, mime: String?): Published

    /** Room for one more copy of a file here, when it can be known (a chosen folder on another volume cannot). */
    fun freeBytes(): Long = Long.MAX_VALUE
}

/**
 * The phone's own destination: the folder chosen in Settings > Files (a document tree), else Downloads
 * (through MediaStore from Android 10, where an app may not create arbitrary files there directly).
 */
class DeviceStorageDestination(private val context: Context) : ReceiveDestination {
    @SuppressLint("UsableSpace") // what is free now is the question; clearing caches for room is not ours to do
    override fun freeBytes(): Long {
        val custom = ShareDestination.isCustomDestinationEnabled(context)
        // Downloads is on the phone's own storage, as the app's files are; a chosen folder may be on a card.
        return if (custom && ShareDestination.customFolderUsable(context)) Long.MAX_VALUE else context.filesDir.usableSpace
    }

    override fun publish(source: File, name: String, lastModified: Long?, mime: String?): Published {
        val type = mime?.takeIf { it.isNotBlank() && it != "*/*" } ?: FilesHelper.getMimeTypeFromFile(name)
        val custom = ShareDestination.isCustomDestinationEnabled(context)
        val tree = if (custom) ShareDestination.getDestinationDirectory(context) else null
        // A folder was chosen but is not there (the grant was revoked, the card is out): saved in Downloads, and said so.
        val fellBack = custom && (tree == null || tree.uri.scheme == "file")
        val published = when {
            tree != null && tree.uri.scheme != "file" -> viaTree(tree, source, name, type)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> viaMediaStore(source, name, lastModified, type)
            else -> viaFile(ShareDestination.getDefaultDestinationDirectory(), source, name, lastModified, type)
        }
        return if (fellBack) published.copy(fellBackToDownloads = true) else published
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
    private fun viaMediaStore(source: File, wanted: String, lastModified: Long?, type: String): Published {
        val resolver = context.contentResolver
        // Named by the one rule ("name (2)"), before MediaStore would pick its own "name (1)".
        val name = uniqueName(wanted) { candidate ->
            runCatching {
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                    arrayOf(candidate, Environment.DIRECTORY_DOWNLOADS + "%"), null,
                )?.use { it.count > 0 } == true
            }.getOrDefault(false)
        }
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
        /** [name], else `name (2)`, `name (3)`… until [taken] says no. Pure apart from [taken]. */
        fun uniqueName(name: String, taken: (String) -> Boolean): String {
            val clean = name.trimEnd('.').ifEmpty { "bad_name_file" }
            val base = File(clean).nameWithoutExtension
            val ext = File(clean).extension.let { if (it.isEmpty()) "" else ".$it" }
            var n = 0
            while (true) {
                val candidate = if (n == 0) clean else "$base (${n + 1})$ext"
                if (!taken(candidate) || n > 10_000) return candidate
                n++
            }
        }

        /** `name`, else `name (2)`, `name (3)`… with the extension kept (the computer's rule: the second file is "(2)"). */
        fun uniqueIn(dir: File, name: String): String {
            val clean = name.trimEnd('.').ifEmpty { "bad_name_file" }
            val base = File(clean).nameWithoutExtension
            val ext = File(clean).extension.let { if (it.isEmpty()) "" else ".$it" }
            var n = 0
            while (true) {
                val candidate = if (n == 0) clean else "$base (${n + 1})$ext"
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
    private const val STRAY_MS = 24 * 3600 * 1000L
    /** Room left over after a copy, as the receiver keeps it while it writes. */
    const val RESERVE_BYTES = 16L * 1024 * 1024

    data class Item(
        val id: String,
        val name: String,
        val size: Long,
        val mime: String?,
        val lastModified: Long?,
        val deviceId: String,
        val file: File,
        /** Why it could not be saved, in words, or null. */
        val reason: String? = null,
        val storageFull: Boolean = false,
    )

    /** What a retry came to. */
    sealed interface Retry {
        data class Done(val published: Published) : Retry
        /** Not saved: [message] says why in plain words; [freeUpBytes] how much room is missing, if that is the reason. */
        data class Failed(val message: String, val freeUpBytes: Long? = null) : Retry
        /** Already being tried (a second tap). */
        data object Busy : Retry
        data object Missing : Retry
    }

    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun isRetrying(id: String) = id in inFlight

    private fun dir(context: Context) = File(context.applicationContext.filesDir, "webrtc-unpublished").apply { mkdirs() }

    /** Keeps [part] aside under a new id; null if it could not be kept (it is then still where it was). */
    fun keep(context: Context, part: File, name: String, mime: String?, lastModified: Long?, deviceId: String, reason: String? = null, storageFull: Boolean = false): Item? {
        val id = UUID.randomUUID().toString()
        val folder = dir(context)
        val meta = File(folder, "$id.json")
        val target = File(folder, "$id.part")
        return try {
            // The record first: a part with no record is invisible, a record with no part is simply dropped by [list].
            meta.writeText(
                JSONObject().put("name", name).put("size", part.length()).put("mime", mime ?: JSONObject.NULL)
                    .put("mtime", lastModified ?: 0L).put("device", deviceId).put("at", System.currentTimeMillis())
                    .put("reason", reason ?: JSONObject.NULL).put("full", storageFull).toString(),
            )
            if (!part.renameTo(target)) {
                meta.delete()
                return null
            }
            Item(id, name, target.length(), mime, lastModified, deviceId, target, reason, storageFull)
        } catch (e: Exception) {
            meta.delete()
            Log.w(TAG, "could not keep an unpublished file (${e.javaClass.simpleName})")
            null
        }
    }

    fun list(context: Context): List<Item> = dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { meta ->
        runCatching {
            val id = meta.name.removeSuffix(".json")
            val json = JSONObject(meta.readText())
            val file = File(meta.parentFile, "$id.part").takeIf { it.isFile } ?: return@runCatching null
            Item(
                id, json.getString("name"), file.length(), json.optString("mime").takeIf { json.has("mime") && !json.isNull("mime") },
                json.optLong("mtime").takeIf { it > 0 }, json.getString("device"), file,
                json.optString("reason").takeIf { json.has("reason") && !json.isNull("reason") }, json.optBoolean("full", false),
            )
        }.getOrNull()
    }.sortedBy { it.name }

    /** Publishes it again. A second call for the same file while one runs does nothing. */
    fun retry(context: Context, id: String, destination: ReceiveDestination): Retry {
        if (!inFlight.add(id)) return Retry.Busy
        try {
            val item = list(context).firstOrNull { it.id == id } ?: return Retry.Missing
            val needed = item.size + RESERVE_BYTES
            val free = destination.freeBytes()
            if (free < needed) {
                return Retry.Failed("Not enough space on the phone.", freeUpBytes = needed - free)
            }
            val published = try {
                destination.publish(item.file, item.name, item.lastModified, item.mime)
            } catch (e: Exception) {
                Log.w(TAG, "a retry could not publish (${e.javaClass.simpleName})")
                return Retry.Failed(if (isStorageFull(e)) "Not enough space on the phone." else "It still could not be saved.")
            }
            discard(context, id)
            TransferHistory.recordReceived(context, item.deviceId, published.name, published.size, published.uri, published.mime)
            return Retry.Done(published)
        } finally {
            inFlight.remove(id)
        }
    }

    fun discard(context: Context, id: String) {
        File(dir(context), "$id.part").delete()
        File(dir(context), "$id.json").delete()
        TransferHistory.changed()
    }

    /**
     * What a week of waiting is worth: nothing (the sender still has the original). Also parts with no record and records
     * with no part left behind by a crash, once they are a day old.
     */
    fun prune(context: Context, now: Long = System.currentTimeMillis()) {
        val folder = dir(context)
        folder.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .filter { now - it.lastModified() > KEEP_MS }
            .forEach { discard(context, it.name.removeSuffix(".json")) }
        folder.listFiles().orEmpty().forEach { f ->
            val id = f.name.substringBeforeLast('.')
            val stray = when (f.extension) {
                "part" -> !File(folder, "$id.json").exists()
                "json" -> !File(folder, "$id.part").exists()
                else -> true
            }
            if (stray && now - f.lastModified() > STRAY_MS) f.delete()
        }
    }

    /** Whether [error] (or what caused it) is the disk being full. */
    fun isStorageFull(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
        "ENOSPC" in it.message.orEmpty() || "No space left" in it.message.orEmpty()
    }
}
