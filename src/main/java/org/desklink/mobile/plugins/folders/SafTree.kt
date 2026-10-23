/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.folders

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * A Bucket folder through the Storage Access Framework: listing, hashing and writing under the
 * picked tree only. When [folder] is set (one Bucket per computer), everything lives in that child of
 * the picked folder, created on first use. Documents are found by name segment by segment from the
 * root, so nothing outside it is ever reached.
 */
class SafTree(private val resolver: ContentResolver, val tree: Uri, private val folder: String? = null) {
    data class Item(val documentId: String, val dir: Boolean, val size: Long, val mtime: Long)

    /** A scan: what may sync (files and folders), and what may not, with why. */
    data class Scan(val found: Map<String, Item>, val skipped: List<Pair<String, Skip>>)

    private val treeRoot = DocumentsContract.getTreeDocumentId(tree)

    /** The Bucket's root document: the computer's folder inside the picked one (made if missing). */
    private val rootId: String by lazy {
        val name = folder ?: return@lazy treeRoot
        children(treeRoot)[name]?.takeIf { it.dir }?.documentId
            ?: DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, treeRoot), Document.MIME_TYPE_DIR, name)
                ?.let(DocumentsContract::getDocumentId)
            ?: error("Could not create the Bucket folder")
    }

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

    /**
     * F8c: files and folders up to [FolderSync.MAX_BUCKET_DEPTH] folders deep. Deeper folders,
     * over-long or unusable names and files over 1 GB are listed in `skipped`, never synced.
     * Providers list only folders and documents (no symlinks or special files reach here).
     */
    fun scan(maxItems: Int = FolderSync.MAX_BUCKET_ITEMS): Scan {
        val found = HashMap<String, Item>()
        val skipped = mutableListOf<Pair<String, Skip>>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue += rootId to ""
        while (queue.isNotEmpty()) {
            val (docId, prefix) = queue.removeFirst()
            for ((name, item) in children(docId).toSortedMap()) {
                val path = prefix + name
                if (FolderSync.ignored(path, includeHidden = false)) continue
                val reason = FolderSync.skipReason(path, item.dir) ?: if (!item.dir && item.size > FolderSync.MAX_BUCKET_FILE_BYTES) Skip.TOO_LARGE else null
                when {
                    reason != null -> if (skipped.size < MAX_SKIPPED) skipped += path to reason
                    found.size >= maxItems -> Unit
                    else -> {
                        found[path] = item
                        if (item.dir) queue += item.documentId to "$path/"
                    }
                }
            }
        }
        return Scan(found, skipped)
    }

    /** Every file and folder below this tree's root, keyed by path (capped, so a huge tree cannot exhaust memory). */
    fun walk(cap: Int = WALK_CAP): Map<String, Item> {
        val out = HashMap<String, Item>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue += rootId to ""
        while (queue.isNotEmpty() && out.size < cap) {
            val (id, prefix) = queue.removeFirst()
            for ((name, item) in children(id).toSortedMap()) {
                if (out.size >= cap) break
                out[prefix + name] = item
                if (item.dir) queue += item.documentId to "$prefix$name/"
            }
        }
        return out
    }

    /** The names in a folder. */
    fun names(item: Item): Set<String> = children(item.documentId).keys

    /** The document at [path] ("" is the root itself), or null. */
    fun find(path: String): Item? {
        if (path.isEmpty()) return Item(rootId, true, 0, 0)
        var id = rootId
        var item: Item? = null
        for (segment in path.split('/')) {
            item = children(id)[segment] ?: return null
            id = item.documentId
        }
        return item
    }

    fun uri(item: Item): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, item.documentId)

    fun open(item: Item): InputStream? = resolver.openInputStream(uri(item))

    fun sha256(item: Item): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        open(item)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** The folder at [path], created (with its parents) as needed; each existing one must be a folder. */
    fun mkdirs(path: String): Item? {
        var id = rootId
        var item: Item? = Item(rootId, true, 0, 0)
        if (path.isEmpty()) return item
        for (segment in path.split('/')) {
            val existing = children(id)[segment]
            item = when {
                existing == null -> DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id), Document.MIME_TYPE_DIR, segment)
                    ?.let { Item(DocumentsContract.getDocumentId(it), true, 0, System.currentTimeMillis()) } ?: return null
                existing.dir -> existing
                else -> return null
            }
            id = item.documentId
        }
        return item
    }

    /** Paths this tree refused because another file there differs only in capital letters (the provider would have renamed ours). */
    val caseClashes = mutableSetOf<String>()

    /** Tests turn the look-before-creating off to prove the check afterwards also holds. */
    @androidx.annotation.VisibleForTesting
    internal var checkBeforeCreating = true

    /** The name already in [path]'s folder that differs from its own only in capital letters, or null. */
    fun caseVariantOf(path: String): String? {
        val parent = find(path.substringBeforeLast('/', "")) ?: return null
        val name = path.substringAfterLast('/')
        return children(parent.documentId).keys.firstOrNull { it != name && it.equals(name, ignoreCase = true) }
    }

    /** Writes [source] at [path]: a temporary document beside it, then renamed into place. The new stat, or null. */
    fun place(path: String, source: File, replace: Item?): Item? = source.inputStream().use { place(path, it, replace) }

    fun place(path: String, source: InputStream, replace: Item?): Item? {
        val parent = mkdirs(path.substringBeforeLast('/', "")) ?: return null
        val name = path.substringAfterLast('/')
        // Another name differing only in capitals: a provider that ignores case would quietly call ours "name (1)" and
        // we would upload that back as a new file. Never created.
        if (checkBeforeCreating && caseVariantOf(path) != null && children(parent.documentId)[name] == null) {
            caseClashes += path
            return null
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parent.documentId)
        val temp = DocumentsContract.createDocument(resolver, parentUri, "application/octet-stream", ".desklink-${UUID.randomUUID()}.tmp") ?: return null
        val ok = runCatching {
            resolver.openOutputStream(temp, "wt")?.use { out -> source.copyTo(out) } ?: error("no stream")
            replace?.let { DocumentsContract.deleteDocument(resolver, uri(it)) }
            DocumentsContract.renameDocument(resolver, temp, name) ?: error("not renamed")
        }.getOrNull()
        if (ok == null) {
            runCatching { DocumentsContract.deleteDocument(resolver, temp) }
            return null
        }
        // The provider may have kept our file under another name (it renames on any clash, whatever we asked for): then it
        // is not the file we meant to create. Removed, and never uploaded back.
        if (displayName(ok) != name) {
            runCatching { DocumentsContract.deleteDocument(resolver, ok) }
            caseClashes += path
            return null
        }
        return find(path)
    }

    private fun displayName(document: Uri): String? = runCatching {
        resolver.query(document, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** Deletes a file, or a folder with everything in it (one call: one item). */
    fun delete(item: Item): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, uri(item)) }.getOrDefault(false)

    fun isEmptyFolder(item: Item) = item.dir && children(item.documentId).isEmpty()

    /** Renames [item] within its folder. */
    fun rename(item: Item, newName: String): Boolean = runCatching { DocumentsContract.renameDocument(resolver, uri(item), newName) != null }.getOrDefault(false)

    /**
     * Moves the item at [from] to [to] (another folder, maybe another name), without copying when the
     * provider can move (Android 7+). False when it could not; the caller then copies.
     */
    fun move(from: String, to: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val item = find(from) ?: return false
        val fromParent = find(from.substringBeforeLast('/', "")) ?: return false
        val toParent = mkdirs(to.substringBeforeLast('/', "")) ?: return false
        return runCatching {
            val moved = if (fromParent.documentId == toParent.documentId) uri(item) else
                DocumentsContract.moveDocument(resolver, uri(item), uri(fromParent), uri(toParent)) ?: return false
            val name = to.substringAfterLast('/')
            if (name != from.substringAfterLast('/')) DocumentsContract.renameDocument(resolver, moved, name) ?: return false
            true
        }.getOrDefault(false)
    }

    /** Old temporaries a crash left behind. */
    fun cleanTemps() {
        val queue = ArrayDeque<Pair<String, Int>>()
        queue += rootId to 0
        while (queue.isNotEmpty()) {
            val (id, depth) = queue.removeFirst()
            for ((name, item) in children(id)) {
                if (item.dir && depth < FolderSync.MAX_BUCKET_DEPTH) queue += item.documentId to depth + 1
                else if (!item.dir && name.startsWith(".desklink-") && name.endsWith(".tmp") && System.currentTimeMillis() - item.mtime > 3_600_000) delete(item)
            }
        }
    }

    companion object {
        /** Not-synced items listed (the count beyond is still shown). */
        const val MAX_SKIPPED = 200
        const val WALK_CAP = 5_000

        /** A computer's name as a folder name (one Bucket per computer). */
        fun folderName(desk: String): String {
            var name = desk.filter { it != '/' && it != '\\' && it != ':' && !it.isISOControl() }.trim().trimEnd('.').trimStart('.')
            while (name.toByteArray().size > FolderSync.MAX_SEGMENT_BYTES) name = name.dropLast(1)
            return name.ifBlank { "Computer" }
        }
    }
}
