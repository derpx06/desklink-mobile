/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.folders

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A phone folder (a SAF tree with a persisted grant) synced with one desk. */
data class FolderPair(
    val id: String,
    val deviceId: String,
    val treeUri: String,
    val name: String,
    val mode: Mode = Mode.TWO_WAY,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val includeHidden: Boolean = false,
    val paused: Boolean = false,
    /** The last scan: files, bytes, and when; 0 = never. */
    val files: Int = 0,
    val bytes: Long = 0,
    val scannedAt: Long = 0,
)

/**
 * F8 on the phone: the pairs, their grants and the scanner that builds a manifest. The exchange
 * with the desk waits for the F8 wire contract; [FolderSync] already holds the diff.
 */
object FolderPairs {
    private const val TAG = "DeskLink/Folders"
    private const val FILE = "folder_pairs.json"
    const val STORED_VERSION = 1

    private var pairs: List<FolderPair>? = null
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private fun file(context: Context) = AtomicFile(File(context.applicationContext.filesDir, FILE))

    @Synchronized
    fun all(context: Context): List<FolderPair> = pairs ?: load(context).also { pairs = it }

    private fun load(context: Context): List<FolderPair> = runCatching {
        val f = file(context)
        if (!f.baseFile.exists()) return emptyList()
        val json = JSONObject(String(f.readFully(), Charsets.UTF_8))
        require(json.optInt("version", 1) <= STORED_VERSION) { "newer" }
        val a = json.optJSONArray("pairs") ?: JSONArray()
        (0 until minOf(a.length(), FolderSync.MAX_PAIRS)).mapNotNull { a.optJSONObject(it)?.let(::fromJson) }
    }.getOrElse {
        Log.w(TAG, "Could not read the synced folders (${it.javaClass.simpleName}); starting empty")
        runCatching { file(context).baseFile.renameTo(File(file(context).baseFile.path + ".unreadable")) }
        emptyList()
    }

    @Synchronized
    private fun store(context: Context, list: List<FolderPair>) {
        pairs = list
        val plain = JSONObject().put("version", STORED_VERSION).put("pairs", JSONArray().apply { list.forEach { put(toJson(it)) } }).toString()
        writer.execute { write(context, plain) }
        _revision.value++
    }

    /** Writes in order, off the main thread. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun write(context: Context, plain: String) {
        runCatching {
            val f = file(context)
            val out = f.startWrite()
            try {
                out.write(plain.toByteArray(Charsets.UTF_8))
                f.finishWrite(out)
            } catch (e: Exception) {
                f.failWrite(out)
                throw e
            }
        }.onFailure { Log.e(TAG, "Could not save the synced folders", it) }
    }

    /** Adds a picked tree for [deviceId], keeping its grant; false at [FolderSync.MAX_PAIRS] or when it cannot be kept. */
    fun add(context: Context, deviceId: String, tree: Uri): Boolean {
        val list = all(context)
        if (list.size >= FolderSync.MAX_PAIRS) return false
        val kept = runCatching {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.isSuccess
        if (!kept) return false
        val name = runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/') }.getOrNull()
            ?.ifBlank { null } ?: "Folder"
        store(context, list.filter { it.treeUri != tree.toString() || it.deviceId != deviceId } +
            FolderPair(java.util.UUID.randomUUID().toString(), deviceId, tree.toString(), name.take(120)))
        return true
    }

    fun update(context: Context, pair: FolderPair) = store(context, all(context).map { if (it.id == pair.id) pair else it })

    /** Stops syncing it and gives the grant back (unless another pair uses the same folder). */
    fun remove(context: Context, id: String) {
        val list = all(context)
        val gone = list.firstOrNull { it.id == id } ?: return
        val rest = list.filter { it.id != id }
        store(context, rest)
        if (rest.none { it.treeUri == gone.treeUri }) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(gone.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }

    /** Whether the grant is still held (the person can revoke it in Settings). */
    fun granted(context: Context, pair: FolderPair) =
        context.contentResolver.persistedUriPermissions.any { it.uri.toString() == pair.treeUri && it.isReadPermission && it.isWritePermission }

    // ---- scanning (background threads only) ---------------------------------------------------------

    /** Hashing reads this much at a time. */
    private const val HASH_BUFFER = 64 * 1024

    /**
     * The pair's manifest: every file under the tree (ignore rules, depth and count bounds applied),
     * hashed only when its size or time changed since [previous]. Files missing since [previous]
     * become tombstones.
     */
    fun scan(context: Context, pair: FolderPair, previous: Map<String, Entry>, now: Long = System.currentTimeMillis()): Map<String, Entry> {
        val tree = Uri.parse(pair.treeUri)
        val resolver = context.contentResolver
        val out = HashMap<String, Entry>()
        val queue = ArrayDeque<Pair<String, String>>() // (documentId, relpath prefix)
        queue += DocumentsContract.getTreeDocumentId(tree) to ""
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (queue.isNotEmpty() && out.size < FolderSync.MAX_FILES) {
            val (docId, prefix) = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            resolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < FolderSync.MAX_FILES) {
                    val name = c.getString(1) ?: continue
                    val relpath = prefix + name
                    if (!FolderSync.validRelpath(relpath) || FolderSync.ignored(relpath, pair.includeHidden)) continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        queue += c.getString(0) to "$relpath/"
                        continue
                    }
                    val size = c.getLong(3)
                    val mtime = c.getLong(4)
                    if (size > FolderSync.MAX_FILE_BYTES) continue
                    val old = previous[relpath]
                    val sha = if (old != null && !old.deleted && old.size == size && old.mtime == mtime && old.sha256 != null) old.sha256
                    else hash(context, DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)))
                    out[relpath] = Entry(relpath, size, mtime, sha)
                }
            }
        }
        previous.forEach { (path, entry) -> if (path !in out) out[path] = if (entry.deleted) entry else entry.copy(deleted = true, mtime = now, sha256 = null, size = 0) }
        return FolderSync.prune(out, now)
    }

    private fun hash(context: Context, document: Uri): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(document)?.use { input ->
            val buffer = ByteArray(HASH_BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun toJson(p: FolderPair) = JSONObject()
        .put("id", p.id).put("deviceId", p.deviceId).put("treeUri", p.treeUri).put("name", p.name).put("mode", p.mode.name)
        .put("wifiOnly", p.wifiOnly).put("chargingOnly", p.chargingOnly).put("includeHidden", p.includeHidden).put("paused", p.paused)
        .put("files", p.files).put("bytes", p.bytes).put("scannedAt", p.scannedAt)

    private fun fromJson(o: JSONObject): FolderPair? {
        val id = o.optString("id").takeIf(String::isNotEmpty) ?: return null
        val uri = o.optString("treeUri").takeIf(String::isNotEmpty) ?: return null
        return FolderPair(
            id = id, deviceId = o.optString("deviceId"), treeUri = uri, name = o.optString("name").take(120),
            mode = runCatching { Mode.valueOf(o.optString("mode")) }.getOrDefault(Mode.TWO_WAY),
            wifiOnly = o.optBoolean("wifiOnly", true), chargingOnly = o.optBoolean("chargingOnly"),
            includeHidden = o.optBoolean("includeHidden"), paused = o.optBoolean("paused"),
            files = o.optInt("files"), bytes = o.optLong("bytes"), scannedAt = o.optLong("scannedAt"),
        )
    }
}
