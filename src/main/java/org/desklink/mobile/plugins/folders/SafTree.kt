/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.folders

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * A synced folder through the Storage Access Framework: listing, hashing and writing under the
 * picked tree only. Every path comes from [FolderSync.validRelpath]; documents are found by name
 * segment by segment from the tree's root, so nothing outside it is ever reached.
 */
class SafTree(private val resolver: ContentResolver, val tree: Uri) {
    private val rootId = DocumentsContract.getTreeDocumentId(tree)

    data class Item(val documentId: String, val dir: Boolean, val size: Long, val mtime: Long)

    private val projection = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
    )

    private fun children(documentId: String): Map<String, Item> {
        val out = HashMap<String, Item>()
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId), projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out[name] = Item(c.getString(0), c.getString(2) == Document.MIME_TYPE_DIR, c.getLong(3), c.getLong(4))
            }
        }
        return out
    }

    /** Every file (P5 ignores, depth and count bounds); `tooMany` when the bound cut it short. */
    fun scan(includeHidden: Boolean): Pair<Map<String, Item>, Boolean> {
        val out = HashMap<String, Item>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue += rootId to ""
        var tooMany = false
        while (queue.isNotEmpty()) {
            val (docId, prefix) = queue.removeFirst()
            for ((name, item) in children(docId)) {
                val path = prefix + name
                if (!FolderSync.validRelpath(path) || FolderSync.ignored(path, includeHidden)) continue
                if (item.dir) {
                    queue += item.documentId to "$path/"
                } else if (item.size <= FolderSync.MAX_FILE_BYTES) {
                    if (out.size >= FolderSync.MAX_FILES) {
                        tooMany = true
                        break
                    }
                    out[path] = item
                }
            }
            if (tooMany) break
        }
        return out to tooMany
    }

    /** The document at [path], or null. */
    fun find(path: String): Item? {
        var id = rootId
        var item: Item? = null
        for (segment in path.split('/')) {
            item = children(id)[segment] ?: return null
            id = item.documentId
        }
        return item
    }

    fun uri(item: Item): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, item.documentId)

    fun sha256(item: Item): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri(item))?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** The directory holding [path], created as needed (each existing one must be a directory). */
    private fun parentOf(path: String): String? {
        var id = rootId
        for (segment in path.split('/').dropLast(1)) {
            val existing = children(id)[segment]
            id = when {
                existing == null -> DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id), Document.MIME_TYPE_DIR, segment)
                    ?.let(DocumentsContract::getDocumentId) ?: return null
                existing.dir -> existing.documentId
                else -> return null
            }
        }
        return id
    }

    /** Writes [source] at [path]: a temporary document beside it, then renamed into place. The new stat, or null. */
    fun place(path: String, source: File, replace: Item?): Item? {
        val parent = parentOf(path) ?: return null
        val name = path.substringAfterLast('/')
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parent)
        val temp = DocumentsContract.createDocument(resolver, parentUri, "application/octet-stream", ".desklink-${UUID.randomUUID()}.tmp") ?: return null
        val ok = runCatching {
            resolver.openOutputStream(temp, "wt")?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: error("no stream")
            replace?.let { DocumentsContract.deleteDocument(resolver, uri(it)) }
            val renamed = DocumentsContract.renameDocument(resolver, temp, name) ?: error("not renamed")
            renamed
        }.getOrNull()
        if (ok == null) {
            runCatching { DocumentsContract.deleteDocument(resolver, temp) }
            return null
        }
        return find(path)
    }

    fun delete(item: Item): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, uri(item)) }.getOrDefault(false)

    /** Renames [item] within its directory (a conflict copy goes beside the original). */
    fun rename(item: Item, newName: String): Boolean = runCatching { DocumentsContract.renameDocument(resolver, uri(item), newName) != null }.getOrDefault(false)

    /** Old temporaries a crash left behind. */
    fun cleanTemps() {
        val queue = ArrayDeque<String>()
        queue += rootId
        var seen = 0
        while (queue.isNotEmpty() && seen < 2048) {
            seen++
            for ((name, item) in children(queue.removeFirst())) {
                if (item.dir) queue += item.documentId
                else if (name.startsWith(".desklink-") && name.endsWith(".tmp") && System.currentTimeMillis() - item.mtime > 3_600_000) delete(item)
            }
        }
    }
}
