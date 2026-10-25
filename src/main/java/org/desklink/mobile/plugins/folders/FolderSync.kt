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

/** P2: one path's state. A tombstone has `deleted`, size 0 and no hash; a folder (F8c `dir`) has no size or hash. */
data class Entry(val size: Long, val mtime: Long, val sha256: String, val deleted: Boolean = false, val vv: Vv = Vv(), val dir: Boolean = false)

/** A local file's (or folder's) size and time, from a scan. */
data class Stat(val size: Long, val mtime: Long, val dir: Boolean = false)

/** Why something in the Bucket folder is not synced (listed as "Not synced (N)"). */
enum class Skip { TOO_DEEP, NAME_TOO_LONG, INVALID_NAME, CASE_CLASH, TOO_LARGE }

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
    /** P2: manifest entries per pair (live items and tombstones). */
    const val MAX_FILES = 20_000
    /** P2: segments any wire path may have (the Bucket itself allows fewer, [MAX_BUCKET_DEPTH]). */
    const val MAX_DEPTH = 32
    const val MAX_SEGMENT_BYTES = 255
    const val MAX_RELPATH_BYTES = 1024
    const val MAX_NAME_CHARS = 80
    /** A conflict copy keeps the original's extension only up to this many bytes (the desk's rule). */
    const val MAX_EXTENSION_BYTES = 16
    /** Characters no name may hold, as [skipReason] refuses them: a conflict copy's name never has them. */
    private const val NAME_REFUSED = "/\\:\u0000"
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
        // The desk measures the extension in bytes, so a multi-byte one is cut off the same way here.
        val (stem0, ext) = if (dot > 0 && name.substring(dot + 1).toByteArray().size <= MAX_EXTENSION_BYTES) name.substring(0, dot) to name.substring(dot) else name to ""
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(Math.floorDiv(mtime, 1000L) * 1000L))
        val who = phone.filter { it !in NAME_REFUSED && !it.isISOControl() }.takeCodePoints(40)
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
        return ordered(renamesByHash(local, steps), local)
    }

    /**
     * F8c order: folders are created first (shallowest first), files move, and a folder's tombstone
     * comes last (deepest first), applied only if the folder ends up empty.
     */
    private fun ordered(steps: List<Step>, local: Map<String, Entry>): List<Step> {
        fun depth(path: String) = path.count { it == '/' }
        val makeDirs = steps.filter { it is Step.Download && it.entry.dir }.sortedBy { depth((it as Step.Download).path) }
        val removeDirs = steps.filter { it is Step.Delete && local[it.path]?.dir == true }.sortedByDescending { depth((it as Step.Delete).path) }
        return makeDirs + steps.filter { it !in makeDirs && it !in removeDirs } + removeDirs
    }

    /** A download whose content a deleted (or kept) local file already has becomes a move (or a copy). */
    private fun renamesByHash(local: Map<String, Entry>, steps: List<Step>): List<Step> {
        val deletes = LinkedHashMap<String, Pair<String, Entry>>()
        // Folders have no hash: they are never matched (a folder rename is its files' moves).
        steps.filterIsInstance<Step.Delete>().forEach { d ->
            local[d.path]?.takeIf { !it.deleted && !it.dir }?.let { deletes.putIfAbsent(it.sha256, d.path to d.entry) }
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
        local.toSortedMap().forEach { (path, e) -> if (!e.deleted && !e.dir && path !in changing) kept[e.sha256] = path }
        val moved = HashSet<String>()
        val out = mutableListOf<Step>()
        for (step in steps) {
            if (step is Step.Download && !step.entry.dir) {
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
            if (stat.dir) {
                // A folder is there or not: its time moves with every child.
                if (existing != null && !existing.deleted && existing.dir) continue
                if (existing == null && manifest.size >= MAX_FILES) continue
                manifest[path] = Entry(0, stat.mtime, "", false, (existing?.vv ?: Vv()).bumped(side), dir = true)
                changed = true
                continue
            }
            if (existing != null && !existing.deleted && !existing.dir && existing.size == stat.size && existing.mtime == stat.mtime) continue
            val sha = hash(path) ?: continue
            when {
                existing != null && !existing.deleted && !existing.dir && existing.sha256 == sha -> manifest[path] = existing.copy(size = stat.size, mtime = stat.mtime)
                existing != null -> manifest[path] = Entry(stat.size, stat.mtime, sha, false, existing.vv.bumped(side))
                manifest.size >= MAX_FILES -> continue
                else -> manifest[path] = Entry(stat.size, stat.mtime, sha, false, Vv().bumped(side))
            }
            changed = true
        }
        for ((path, entry) in manifest.entries.toList()) {
            if (!entry.deleted && path !in found && path !in keep) {
                manifest[path] = Entry(0, now, "", true, entry.vv.bumped(side), dir = entry.dir)
                changed = true
            }
        }
        val cutoff = now - TOMBSTONE_DAYS * 24 * 3600 * 1000
        manifest.entries.removeAll { it.value.deleted && it.value.mtime <= cutoff }
        return changed
    }

    // ---- F8b/F8c the Bucket -----------------------------------------------------------------------------------

    /** F8b: a phone's Bucket is the pair with this id; it is the only pair. */
    const val BUCKET_ID = "bucket"
    /** F8c: folder levels (`A/B/file`; folders `A` and `A/B`). */
    const val MAX_BUCKET_DEPTH = 2
    /**
     * Manifest entries taken from the desk's parts: live items and 30-day tombstones, so as many as
     * the desk sends ([MAX_FILES]). The live-item limit [MAX_BUCKET_ITEMS] still applies to adds.
     */
    const val MAX_MANIFEST_ENTRIES = MAX_FILES

    /** The entries of a complete manifest the phone reads, in order, at most [MAX_MANIFEST_ENTRIES]. */
    fun <T> takeManifest(entries: List<T>): List<T> = entries.take(MAX_MANIFEST_ENTRIES)

    /** F8c: items per Bucket, files and folders together. */
    const val MAX_BUCKET_ITEMS = 10_000
    /** F8c: one file ("Files over 1 GB can't go in the Bucket"). */
    const val MAX_BUCKET_FILE_BYTES = 1L shl 30
    /** 1.3 decision 3: a warning from 8 GB, new additions refused at 10 GB. */
    const val WARN_BUCKET_BYTES = 8L shl 30
    const val MAX_BUCKET_BYTES = 10L shl 30
    /** F8c: one add (a share, a pick, a folder): at most this many items and bytes; the rest are left out. */
    const val MAX_ADD_ITEMS = 500
    const val MAX_ADD_BYTES = 5L shl 30
    /**
     * Bucket transfers in flight per direction; the rest wait. The transfer manager runs two per direction, so one is always left
     * for a share or an insert-from-phone: a person's send never queues behind a background sync (I16).
     */
    const val MAX_IN_FLIGHT = 1
    /** F8b: a pasted text or link saved as a file. */
    const val MAX_TEXT_BYTES = 1024 * 1024
    /** F8b: free space kept on the phone beyond an incoming file. */
    const val MIN_FREE_BYTES = 500L * 1024 * 1024
    /** W0: a file another app is still writing is sent once its size and time hold this long. */
    const val WRITE_SETTLE_MS = 2_000L

    /**
     * Why [path] cannot be in the Bucket, or null. Depth (at most [MAX_BUCKET_DEPTH] folders), 255-byte
     * names, and names the other side cannot hold (`:`, `\`, control characters, a trailing dot or space).
     */
    fun skipReason(path: String, dir: Boolean): Skip? {
        if (path.isEmpty() || path.toByteArray().size > MAX_RELPATH_BYTES) return Skip.NAME_TOO_LONG
        val segments = path.split('/')
        if (segments.any { it.toByteArray().size > MAX_SEGMENT_BYTES }) return Skip.NAME_TOO_LONG
        if (segments.any { s -> !validSegment(s) || s.any { it == ':' || it == '\\' || it.isISOControl() } || s.endsWith('.') || s.endsWith(' ') }) return Skip.INVALID_NAME
        val folders = if (dir) segments.size else segments.size - 1
        return if (folders > MAX_BUCKET_DEPTH) Skip.TOO_DEEP else null
    }

    /**
     * An entry from the desk's manifest the phone will take: a path the Bucket allows (F8c) that the
     * phone's own ignore rules would not skip (P6).
     */
    fun takesIncoming(path: String, dir: Boolean): Boolean = skipReason(path, dir) == null && !ignored(path, includeHidden = false)

    /** Unicode code point order, which is UTF-8 byte order (not Kotlin's UTF-16 order, which differs above U+E000). */
    internal val utf8Order = Comparator<String> { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return@Comparator x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        (a.length - i).compareTo(b.length - j)
    }

    /**
     * "W3 — case clashes" (docs/protocol/stageD-protocol.md; the computer applies the same rule). Among the LIVE items of one
     * directory (files and folders together), names equal after `lowercase(Locale.ROOT)` are one name: the one that sorts first
     * in UTF-8 byte order (so `A.txt` beats `a.txt`, `É` beats `é`) syncs, the others do not, nor does anything below a folder
     * that is left out. Different directories never clash. Returns the paths left out (not what lies below them; see [isLeftOut]).
     * Tombstones are not passed in: they never clash.
     */
    fun caseClashes(livePaths: Collection<String>): Set<String> {
        val first = HashSet<Pair<String, String>>()
        val losers = LinkedHashSet<String>()
        for (path in livePaths.sortedWith(utf8Order)) {
            if (underLoser(path, losers)) continue
            val parent = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            if (!first.add(parent to name.lowercase(java.util.Locale.ROOT))) losers += path
        }
        return losers
    }

    private fun underLoser(path: String, losers: Set<String>): Boolean {
        if (losers.isEmpty()) return false
        var at = path.indexOf('/')
        while (at >= 0) {
            if (path.substring(0, at) in losers) return true
            at = path.indexOf('/', at + 1)
        }
        return false
    }

    /**
     * The manifest guard: of two live paths in [received] that differ only in capitals the later one, and what is below it, is
     * dropped before planning. A tombstone is kept as it is, and so is a path [alreadySynced] here (kept untouched). Returns the
     * manifest to plan with and the paths dropped.
     */
    fun guardManifest(received: Map<String, Entry>, alreadySynced: (String) -> Boolean = { false }): Pair<Map<String, Entry>, Set<String>> {
        val leftOut = caseClashes(received.filterValues { !it.deleted }.keys)
        val dropped = received.filter { (path, entry) -> !entry.deleted && isLeftOut(path, leftOut) && !alreadySynced(path) }.keys
        return (received - dropped) to dropped
    }

    /** [path] is a left-out item or lies below one. */
    fun isLeftOut(path: String, losers: Set<String>): Boolean = path in losers || underLoser(path, losers)

    /**
     * A scan that lost more than a fifth of what the manifest still expects to find (at least ten files) is more likely a
     * partial listing than that many deletions: it is not applied until a second scan agrees on the same missing files.
     */
    fun suspiciousShrink(expected: Set<String>, found: Set<String>): Set<String>? {
        if (expected.size < 10) return null
        val missing = expected - found
        return missing.takeIf { it.size * 5 > expected.size }
    }

    /** F8b: an incoming file of [size] fits when [free] keeps [MIN_FREE_BYTES] after it. */
    fun enoughSpace(free: Long, size: Long): Boolean = size in 0..MAX_BUCKET_FILE_BYTES && free - size >= MIN_FREE_BYTES

    /** What one add may take: the files in, and how many were left out, by reason. */
    data class Admitted(val accepted: List<Int>, val tooLarge: Int, val bucketFull: Int, val overAddLimit: Int)

    /** F8c limits for one add of files with [sizes] into a Bucket holding [bytes] in [items] items. */
    fun admit(sizes: List<Long>, bytes: Long, items: Int): Admitted {
        val accepted = mutableListOf<Int>()
        var tooLarge = 0
        var full = 0
        var over = 0
        var addBytes = 0L
        var total = bytes
        var count = items
        for ((index, size) in sizes.withIndex()) {
            when {
                size > MAX_BUCKET_FILE_BYTES -> tooLarge++
                accepted.size >= MAX_ADD_ITEMS || addBytes + size > MAX_ADD_BYTES -> over++
                total + size > MAX_BUCKET_BYTES || count >= MAX_BUCKET_ITEMS -> full++
                else -> {
                    accepted += index
                    addBytes += size
                    total += size
                    count++
                }
            }
        }
        return Admitted(accepted, tooLarge, full, over)
    }

    /** W0: a new or changed file is taken once it has held still for [WRITE_SETTLE_MS] (another app may still be writing it). */
    fun settled(previous: Pair<Stat, Long>?, stat: Stat, now: Long): Boolean =
        stat.dir || (previous != null && previous.first == stat && now - previous.second >= WRITE_SETTLE_MS)

    /** The next paths to request: at most [MAX_IN_FLIGHT] outstanding at once. */
    fun nextBatch(waiting: List<String>, outstanding: Int): List<String> = waiting.take((MAX_IN_FLIGHT - outstanding).coerceAtLeast(0))

    /**
     * F8c "Move to folder": [items] (path, is folder, and each folder's descendants) moved into
     * [target] ("" is the top). Each item's new path, or null when it would pass the depth limit.
     */
    fun moveInto(target: String, path: String, dir: Boolean, descendants: List<Pair<String, Boolean>>): String? {
        val name = path.substringAfterLast('/')
        val to = if (target.isEmpty()) name else "$target/$name"
        if (to == path || (dir && (target == path || target.startsWith("$path/")))) return null
        if (skipReason(to, dir) != null) return null
        return to.takeIf { descendants.all { (child, childDir) -> skipReason(to + child.removePrefix(path), childDir) == null } }
    }

    /** P6/W0: a Bucket that had files and suddenly scans empty (grant lost, folder deleted, card removed) is held, never tombstoned. */
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
