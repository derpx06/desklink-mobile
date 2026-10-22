/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.folders

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One file in a synced folder's manifest (F8): its path under the pair root and what it holds. */
data class Entry(
    val relpath: String,
    val size: Long,
    val mtime: Long,
    /** Lowercase hex SHA-256, or null before it was hashed. */
    val sha256: String?,
    /** A tombstone: deleted at [mtime]. */
    val deleted: Boolean = false,
) {
    /** Same contents (or both gone): by hash when both have one, else by size and time. */
    fun sameAs(other: Entry?): Boolean = when {
        other == null -> false
        deleted || other.deleted -> deleted && other.deleted
        sha256 != null && other.sha256 != null -> sha256 == other.sha256
        else -> size == other.size && mtime == other.mtime
    }
}

enum class Mode { TWO_WAY, DESK_TO_PHONE, PHONE_TO_DESK }

/** What one side must do so both folders agree. */
sealed interface Op {
    val relpath: String

    data class Upload(override val relpath: String) : Op
    data class Download(override val relpath: String) : Op
    data class DeleteHere(override val relpath: String) : Op
    data class DeleteThere(override val relpath: String) : Op
    /** Detected by hash: a delete plus a create of the same contents. */
    data class RenameHere(val from: String, override val relpath: String) : Op
    data class RenameThere(val from: String, override val relpath: String) : Op
    /** Both changed: the newer keeps the name, the other is kept as [copyName] on both sides. */
    data class Conflict(override val relpath: String, val keepLocal: Boolean, val copyName: String) : Op
}

/**
 * F8's pure half: path rules, ignore rules and the three-way manifest diff (local, remote, and
 * the last agreed state). Deletes are tombstones kept [TOMBSTONE_DAYS]; a deletion never beats a
 * concurrent edit; renames are found by hash.
 */
object FolderSync {
    /** Files in one pair: a manifest stays a few MB, and the first scan bounded. */
    const val MAX_FILES = 20_000

    /** Directory depth under the pair root. */
    const val MAX_DEPTH = 32

    /** One path component, as the file protocol's `validateFilename` counts it. */
    const val MAX_SEGMENT_BYTES = 255

    /** A whole relpath. */
    const val MAX_RELPATH_BYTES = 4096

    /** One file: larger ones are listed as skipped, never transferred by sync. */
    const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024

    /** Synced pairs per phone. */
    const val MAX_PAIRS = 20

    const val TOMBSTONE_DAYS = 30L

    /** Never synced: tools' and editors' private files. Hidden files too unless asked for. */
    private val IGNORED_NAMES = setOf(".git", "node_modules", ".DS_Store", "Thumbs.db", ".thumbnails", ".trashed")
    private val IGNORED_SUFFIXES = listOf(".tmp", ".part", ".crdownload", ".swp", "~")

    /** Each segment as `validateFilename` wants it, and bounded depth and length. */
    fun validRelpath(relpath: String): Boolean {
        if (relpath.isEmpty() || relpath.toByteArray().size > MAX_RELPATH_BYTES || relpath.startsWith("/")) return false
        val segments = relpath.split('/')
        return segments.size <= MAX_DEPTH && segments.all { s ->
            s.isNotEmpty() && s != "." && s != ".." && s.toByteArray().size <= MAX_SEGMENT_BYTES &&
                '\\' !in s && s.none { it.isISOControl() }
        }
    }

    fun ignored(relpath: String, includeHidden: Boolean = false): Boolean = relpath.split('/').any { s ->
        s in IGNORED_NAMES || IGNORED_SUFFIXES.any(s::endsWith) || (!includeHidden && s.startsWith("."))
    }

    /** "name (conflict from realme 2026-10-22).ext", next to the original. */
    fun conflictName(relpath: String, from: String, at: Long): String {
        val slash = relpath.lastIndexOf('/')
        val dir = relpath.substring(0, slash + 1)
        val name = relpath.substring(slash + 1)
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(at))
        val who = from.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.take(40).ifBlank { "other device" }
        val suffix = " (conflict from $who $day)"
        // Keep the segment within its byte bound by shortening the stem.
        var stem = name.substring(0, dot)
        val ext = name.substring(dot)
        while (stem.isNotEmpty() && (stem + suffix + ext).toByteArray().size > MAX_SEGMENT_BYTES) stem = stem.dropLast(1)
        return dir + stem + suffix + ext
    }

    /**
     * The three-way diff. [base] is what both sides last agreed on (by relpath); [local] and
     * [remote] are the current manifests: each side turns a file missing since [base] into a
     * tombstone. [remoteName]/[localName] name the conflict copies.
     */
    fun diff(base: Map<String, Entry>, local: Map<String, Entry>, remote: Map<String, Entry>, mode: Mode, remoteName: String, localName: String): List<Op> {
        val ops = mutableListOf<Op>()
        for (path in (base.keys + local.keys + remote.keys).toSortedSet()) {
            val b = base[path]
            val l = local[path]
            val r = remote[path]
            val lChanged = changed(b, l)
            val rChanged = changed(b, r)
            val lLive = l != null && !l.deleted
            val rLive = r != null && !r.deleted
            when {
                (l != null && l.sameAs(r)) || (!lLive && !rLive) -> Unit
                // One way: the source wins, but a file the source never had is never deleted.
                mode == Mode.DESK_TO_PHONE -> if (rLive) ops += Op.Download(path) else if (r != null || b != null) ops += Op.DeleteHere(path)
                mode == Mode.PHONE_TO_DESK -> if (lLive) ops += Op.Upload(path) else if (l != null || b != null) ops += Op.DeleteThere(path)
                lChanged && !rChanged -> ops += if (lLive) Op.Upload(path) else Op.DeleteThere(path)
                rChanged && !lChanged -> ops += if (rLive) Op.Download(path) else Op.DeleteHere(path)
                // Both changed. A deletion never beats an edit.
                !lLive -> ops += Op.Download(path)
                !rLive -> ops += Op.Upload(path)
                else -> {
                    val keepLocal = l!!.mtime > r!!.mtime || (l.mtime == r.mtime && (l.sha256 ?: "") > (r.sha256 ?: ""))
                    val loserAt = if (keepLocal) r.mtime else l.mtime
                    ops += Op.Conflict(path, keepLocal, conflictName(path, if (keepLocal) remoteName else localName, loserAt))
                }
            }
        }
        return renames(ops, base, local, remote)
    }

    /** An entry differs from the agreed one (absent on both is no change). */
    private fun changed(base: Entry?, now: Entry?): Boolean = when {
        base == null -> now != null && !now.deleted
        now == null -> false
        else -> !now.sameAs(base)
    }

    /** A delete and an upload (or download) of the same contents become a rename. */
    private fun renames(ops: List<Op>, base: Map<String, Entry>, local: Map<String, Entry>, remote: Map<String, Entry>): List<Op> {
        val result = ops.toMutableList()
        fun pair(isDelete: (Op) -> Boolean, isCreate: (Op) -> Boolean, contents: (String) -> String?, rename: (String, String) -> Op) {
            val deletes = result.filter(isDelete).mapNotNull { op -> base[op.relpath]?.sha256?.let { it to op } }.groupBy({ it.first }, { it.second }).mapValues { it.value.toMutableList() }
            for (create in result.filter(isCreate)) {
                val sha = contents(create.relpath) ?: continue
                val delete = deletes[sha]?.removeFirstOrNull() ?: continue
                val at = result.indexOf(create)
                result[at] = rename(delete.relpath, create.relpath)
                result.remove(delete)
            }
        }
        pair({ it is Op.DeleteThere }, { it is Op.Upload }, { local[it]?.sha256 }) { from, to -> Op.RenameThere(from, to) }
        pair({ it is Op.DeleteHere }, { it is Op.Download }, { remote[it]?.sha256 }) { from, to -> Op.RenameHere(from, to) }
        return result
    }

    /** Tombstones older than [TOMBSTONE_DAYS] are forgotten. */
    fun prune(entries: Map<String, Entry>, now: Long): Map<String, Entry> {
        val cutoff = now - TOMBSTONE_DAYS * 24 * 3600 * 1000
        return entries.filterValues { !it.deleted || it.mtime > cutoff }
    }
}
