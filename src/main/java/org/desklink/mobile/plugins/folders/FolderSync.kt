/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.folders

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class Side { DESK, PHONE }

/** P2 version vector: each side bumps its own counter on a local change. */
data class Vv(val desk: Long = 0, val phone: Long = 0) {
    fun dominates(other: Vv) = desk >= other.desk && phone >= other.phone && this != other
    fun max(other: Vv) = Vv(maxOf(desk, other.desk), maxOf(phone, other.phone))
    fun bumped(side: Side) = if (side == Side.DESK) copy(desk = desk + 1) else copy(phone = phone + 1)
}

/** P2: one path's state. A tombstone has `deleted`, size 0 and no hash. */
data class Entry(val size: Long, val mtime: Long, val sha256: String, val deleted: Boolean = false, val vv: Vv = Vv())

/** A local file's size and time, from a scan. */
data class Stat(val size: Long, val mtime: Long)

/** One step of a plan, for the side that made it (P3). */
sealed interface Step {
    /** Record the other side's entry (a tombstone, or the same content). */
    data class Take(val path: String, val entry: Entry) : Step
    /** Remove the local file; record [entry] (a tombstone). */
    data class Delete(val path: String, val entry: Entry) : Step
    data class Download(val path: String, val entry: Entry) : Step
    /** A local file the plan deletes has the content to fetch. */
    data class Move(val from: String, val to: String, val entry: Entry, val tombstone: Entry) : Step
    /** Another local file has the content to fetch. */
    data class Copy(val from: String, val to: String, val entry: Entry) : Step
    /** Ours wins a concurrent change: our entry gets [vv], so the other side takes it. */
    data class Bump(val path: String, val vv: Vv) : Step
    /** The phone's own version moves aside under the conflict name. */
    data class Rename(val from: String, val to: String) : Step
    /** The desk lists a conflict (the phone acts). */
    data class Conflict(val path: String, val copy: String) : Step
}

/**
 * F8's pure half, the desk's `folders.rs` rule for rule: paths, ignores, scans folded into the
 * manifest, and the plan. Both sides compute the same outcome; in a both-edited conflict only the
 * phone acts (renames its own version, takes the desk's).
 */
object FolderSync {
    /** P1: pairs per phone. */
    const val MAX_PAIRS = 8
    /** P2: entries per pair. */
    const val MAX_FILES = 20_000
    const val MAX_DEPTH = 32
    const val MAX_SEGMENT_BYTES = 255
    const val MAX_RELPATH_BYTES = 1024
    /** P5: larger files are skipped. */
    const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_NAME_CHARS = 80
    /** P2: one manifest packet, and parts per manifest. */
    const val PACKET_BUDGET = org.desklink.mobile.webrtc.WebRtcEnvelope.FEATURE_PACKET_BUDGET
    const val MAX_PARTS = 256
    /** P4. */
    const val REQUEST_PATHS = 64
    const val MAX_OUTSTANDING = 256
    const val TOMBSTONE_DAYS = 30L

    fun validSegment(s: String) =
        s.isNotEmpty() && s != "." && s != ".." && s.toByteArray().size <= MAX_SEGMENT_BYTES && '/' !in s && '\\' !in s && '\u0000' !in s

    /** P2 / file protocol `validate_relpath`. */
    fun validRelpath(relpath: String): Boolean {
        if (relpath.isEmpty() || relpath.toByteArray().size > MAX_RELPATH_BYTES) return false
        val segments = relpath.split('/')
        return segments.size <= MAX_DEPTH && segments.all(::validSegment)
    }

    /** P5. */
    fun ignored(path: String, includeHidden: Boolean): Boolean = path.split('/').any { s ->
        s == ".git" || s == "node_modules" || s.startsWith(".desklink") || s.endsWith(".tmp") || s.endsWith(".part") ||
            s.endsWith("~") || (!includeHidden && s.startsWith("."))
    }

    /** `<stem> (conflict from <phone> <YYYY-MM-DD>)<.ext>` beside [path]; the date is [mtime]'s, UTC. */
    fun conflictName(path: String, phone: String, mtime: Long): String {
        val slash = path.lastIndexOf('/')
        val dir = path.substring(0, slash + 1)
        val name = path.substring(slash + 1)
        val dot = name.lastIndexOf('.')
        val (stem0, ext) = if (dot > 0 && name.length - dot - 1 <= 16) name.substring(0, dot) to name.substring(dot) else name to ""
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(Math.floorDiv(mtime, 1000L) * 1000L))
        val who = phone.filter { it != '/' && it != '\\' && it != '\u0000' && !it.isISOControl() }.takeCodePoints(40)
        val suffix = " (conflict from $who $date)$ext"
        var stem = stem0
        while (stem.isNotEmpty() && (stem + suffix).toByteArray().size > MAX_SEGMENT_BYTES) stem = stem.dropLast(1)
        return dir + stem + suffix
    }

    private fun String.takeCodePoints(n: Int) = if (codePointCount(0, length) <= n) this else substring(0, offsetByCodePoints(0, n))

    /** P3: what [localSide] does with the other side's complete manifest. */
    fun plan(localSide: Side, local: Map<String, Entry>, remote: Map<String, Entry>, phoneName: String): List<Step> {
        val steps = mutableListOf<Step>()
        for ((path, theirs) in remote.toSortedMap()) {
            val ours = local[path]
            when {
                ours == null -> steps += if (theirs.deleted) Step.Take(path, theirs) else Step.Download(path, theirs)
                ours.vv == theirs.vv -> Unit
                theirs.vv.dominates(ours.vv) -> {
                    val same = ours.sha256 == theirs.sha256 && ours.deleted == theirs.deleted
                    steps += when {
                        same || (ours.deleted && theirs.deleted) -> Step.Take(path, theirs)
                        theirs.deleted -> Step.Delete(path, theirs)
                        else -> Step.Download(path, theirs)
                    }
                }
                ours.vv.dominates(theirs.vv) -> Unit
                else -> {
                    val merged = ours.vv.max(theirs.vv)
                    when {
                        (ours.deleted && theirs.deleted) || (!ours.deleted && !theirs.deleted && ours.sha256 == theirs.sha256) ->
                            steps += Step.Take(path, theirs.copy(vv = merged))
                        // An edit beats a concurrent deletion.
                        ours.deleted -> steps += Step.Download(path, theirs.copy(vv = merged))
                        theirs.deleted -> steps += Step.Bump(path, merged.bumped(localSide))
                        else -> {
                            val phoneMtime = if (localSide == Side.DESK) theirs.mtime else ours.mtime
                            val copy = conflictName(path, phoneName, phoneMtime)
                            if (localSide == Side.DESK) {
                                steps += Step.Conflict(path, copy)
                            } else {
                                steps += Step.Rename(path, copy)
                                steps += Step.Download(path, theirs.copy(vv = merged.bumped(Side.DESK)))
                            }
                        }
                    }
                }
            }
        }
        return renamesByHash(local, steps)
    }

    /** A download whose content a deleted (or kept) local file already has becomes a move (or a copy). */
    private fun renamesByHash(local: Map<String, Entry>, steps: List<Step>): List<Step> {
        val deletes = LinkedHashMap<String, Pair<String, Entry>>()
        steps.filterIsInstance<Step.Delete>().forEach { d ->
            local[d.path]?.takeIf { !it.deleted }?.let { deletes.putIfAbsent(it.sha256, d.path to d.entry) }
        }
        val changing = steps.mapNotNull {
            when (it) {
                is Step.Delete -> it.path
                is Step.Download -> it.path
                is Step.Rename -> it.from
                else -> null
            }
        }.toSet()
        val kept = HashMap<String, String>()
        local.toSortedMap().forEach { (path, e) -> if (!e.deleted && path !in changing) kept[e.sha256] = path }
        val moved = HashSet<String>()
        val out = mutableListOf<Step>()
        for (step in steps) {
            if (step is Step.Download) {
                val delete = deletes.remove(step.entry.sha256)
                val from = kept[step.entry.sha256]
                out += when {
                    delete != null -> {
                        moved += delete.first
                        Step.Move(delete.first, step.path, step.entry, delete.second)
                    }
                    from != null -> Step.Copy(from, step.path, step.entry)
                    else -> step
                }
            } else {
                out += step
            }
        }
        return out.filterNot { it is Step.Delete && it.path in moved }
    }

    /**
     * Folds a scan into [manifest] for [side]: new or changed files bump its counter (hashing only
     * what changed), vanished ones become tombstones, except [keep] (downloads on the way). Whether
     * anything changed.
     */
    fun applyScan(side: Side, manifest: MutableMap<String, Entry>, found: Map<String, Stat>, hash: (String) -> String?, now: Long, keep: Set<String> = emptySet()): Boolean {
        var changed = false
        for ((path, stat) in found.toSortedMap()) {
            val existing = manifest[path]
            if (existing != null && !existing.deleted && existing.size == stat.size && existing.mtime == stat.mtime) continue
            val sha = hash(path) ?: continue
            when {
                existing != null && !existing.deleted && existing.sha256 == sha -> manifest[path] = existing.copy(size = stat.size, mtime = stat.mtime)
                existing != null -> manifest[path] = Entry(stat.size, stat.mtime, sha, false, existing.vv.bumped(side))
                manifest.size >= MAX_FILES -> continue
                else -> manifest[path] = Entry(stat.size, stat.mtime, sha, false, Vv().bumped(side))
            }
            changed = true
        }
        for ((path, entry) in manifest.entries.toList()) {
            if (!entry.deleted && path !in found && path !in keep) {
                manifest[path] = Entry(0, now, "", true, entry.vv.bumped(side))
                changed = true
            }
        }
        val cutoff = now - TOMBSTONE_DAYS * 24 * 3600 * 1000
        manifest.entries.removeAll { it.value.deleted && it.value.mtime <= cutoff }
        return changed
    }

    // ---- F8b the Bucket ----------------------------------------------------------------------------------------

    /** F8b: a phone's Bucket is the pair with this id; it is the only pair. */
    const val BUCKET_ID = "bucket"
    /** F8b: items per Bucket. */
    const val MAX_BUCKET_ITEMS = 10_000
    /** F8b: a pasted text or link saved as a file. */
    const val MAX_TEXT_BYTES = 1024 * 1024
    /** F8b: free space kept on the phone beyond an incoming file. */
    const val MIN_FREE_BYTES = 500L * 1024 * 1024
    /** F8b: the Bucket shows a warning past this. */
    const val WARN_BUCKET_BYTES = 10L * 1024 * 1024 * 1024

    /**
     * An entry from the desk's manifest the phone will take: a top-level file name (F8b: files only,
     * no `/`, no folders) that the phone's own ignore rules would not skip (P6).
     */
    fun takesIncoming(path: String, dir: Boolean): Boolean = !dir && '/' !in path && validSegment(path) && !ignored(path, includeHidden = false)

    /** F8b: an incoming file of [size] fits when [free] keeps [MIN_FREE_BYTES] after it. */
    fun enoughSpace(free: Long, size: Long): Boolean = size in 0..MAX_FILE_BYTES && free - size >= MIN_FREE_BYTES

    /**
     * P6 empty-folder hold: a folder that had files and now scans empty (grant lost, card removed)
     * is held, never tombstoned, until the person confirms.
     */
    fun holdEmpty(manifest: Map<String, Entry>, found: Map<String, Stat>, confirmed: Boolean): Boolean =
        !confirmed && found.isEmpty() && manifest.values.any { !it.deleted }

    /** F8b: a text or link saved as a file: "Text <date time>.txt", or "<host> <date>.url" (a freedesktop link). */
    fun textItem(text: String, now: Long): Pair<String, ByteArray>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val link = Regex("^https?://[^\\s]+$").matches(trimmed)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(Date(now))
        val (name, body) = if (link) {
            val host = runCatching { java.net.URI(trimmed).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: "Link"
            "${host.filter { it != '/' && it != '\\' && !it.isISOControl() }} ${stamp.substring(0, 10)}.url" to
                "[Desktop Entry]\nEncoding=UTF-8\nName=$host\nType=Link\nURL=$trimmed\n"
        } else {
            "Text $stamp.txt" to trimmed + "\n"
        }
        val bytes = body.toByteArray()
        return if (bytes.size > MAX_TEXT_BYTES || !validSegment(name)) null else name to bytes
    }

    fun validSha(sha: String) = sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
