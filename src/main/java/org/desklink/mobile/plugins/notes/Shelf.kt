/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.notes

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap
import java.util.TreeSet

/** S1: a short text to paste. `deleted` is a tombstone (text empty). */
data class Snippet(val id: String, val text: String, val title: String = "", val modified: Long, val deleted: Boolean = false) {
    /** Its title, or its first non-blank line. */
    val label: String
        get() = (title.takeIf(String::isNotBlank) ?: text.lineSequence().firstOrNull(String::isNotBlank) ?: "").trim().takeCodePoints(60)
}

/** N1: a note. `parent` is the `modified` of the version it was edited from. */
data class Note(
    val id: String,
    val title: String = "",
    val body: String = "",
    val pinned: Boolean = false,
    val created: Long,
    val modified: Long,
    val parent: Long = 0,
    val deleted: Boolean = false,
    /**
     * W7 "notes ancestors": the `modified` of this side's earlier versions of the note (newest first, at most
     * [Shelf.MAX_ANCESTORS]). A receiver that holds any of them holds an ancestor of this version, so this one just
     * continues it: without them a second edit in a row looked like a rival of the first and left a "conflicted copy".
     */
    val ancestors: List<Long> = emptyList(),
) {
    /** Its title, or its first line without heading/list marks, or "New Note"; the desk derives conflicted-copy titles alike. */
    val label: String
        get() = if (trimNote(title).isNotEmpty()) {
            trimNote(title).takeCodePoints(80)
        } else {
            (firstLabelLine(body) ?: "New Note").takeCodePoints(80)
        }

    fun sameContent(other: Note) = title == other.title && body == other.body && pinned == other.pinned && deleted == other.deleted
}

/** What labels and duplicate checks trim, spelled out so both apps trim exactly the same characters (the desk's `TRIM`). */
private const val TRIM = " \t\n\r\u000B\u000C\u00A0\u2028\u2029\u3000"

internal fun trimNote(text: String): String = text.trim { it in TRIM }

/** The first line of [body] that says something once list and heading marks and blanks are gone, or null; stops at it. */
private fun firstLabelLine(body: String): String? {
    var start = 0
    while (start <= body.length) {
        val end = body.indexOf('\n', start).let { if (it < 0) body.length else it }
        val line = trimNote(body.substring(start, end).removeSuffix("\r").trimStart('#', ' ', '-', '*'))
        if (line.isNotEmpty()) return line
        start = end + 1
    }
    return null
}

internal fun String.takeCodePoints(n: Int): String =
    if (codePointCount(0, length) <= n) this else substring(0, offsetByCodePoints(0, n))

enum class ShelfKind(val packetType: String) {
    SNIPPETS(DeskLinkProtocol.PACKET_TYPE_SNIPPETS),
    NOTES(DeskLinkProtocol.PACKET_TYPE_NOTES),
    ;

    companion object {
        fun of(packetType: String) = entries.firstOrNull { it.packetType == packetType }
    }
}

/** What a received note did (N2). */
enum class Merged { KEPT_OURS, TAKEN, SAME, CONFLICT }

/**
 * Snippets and notes (contract F6), the pure half: merging, the index/fetch/items exchange and the
 * stored form. The same rules as the desk's `notes.rs`, so both sides reach the same result.
 */
class Shelf {
    val snippets = TreeMap<String, Snippet>()
    val notes = TreeMap<String, Note>()
    /** Per note: the `modified` both sides last had. */
    val synced = TreeMap<String, Long>()

    /** Per note, per peer: the version that peer is known to hold (it sent, listed or acknowledged it). The desk's `held`. */
    val held = TreeMap<String, TreeMap<String, Long>>()

    /** Per note, per peer: the latest version sent to it (its acknowledgement may have been lost). The desk's `sent`. */
    val sent = TreeMap<String, TreeMap<String, Long>>()

    private fun record(marks: TreeMap<String, TreeMap<String, Long>>, id: String, peer: String, version: Long) {
        if (peer.isEmpty()) return
        val peers = marks.getOrPut(id) { TreeMap() }
        peers[peer] = version
        while (peers.size > MAX_PEERS_PER_NOTE) {
            val oldest = peers.minByOrNull { it.value }?.key ?: break
            peers.remove(oldest)
        }
    }

    fun recordHeld(id: String, peer: String, version: Long) = record(held, id, peer, version)

    fun recordSent(id: String, peer: String, version: Long) = record(sent, id, peer, version)

    /** The `parent` of the next edit of [id]: the oldest version a peer is known to hold, else the version both sides last had. */
    fun baseOf(id: String): Long? = held[id]?.values?.minOrNull() ?: synced[id]

    /** The versions of [id] that must stay among its ancestors however many edits follow: what peers hold and were sent. */
    fun mustKeep(id: String): List<Long> = (held[id]?.values.orEmpty() + sent[id]?.values.orEmpty()).toList()

    /** Notes [ids] are going to [peer]: it will hold that version, or the one it held. */
    fun sentTo(kind: ShelfKind, ids: Collection<String>, peer: String) {
        if (kind != ShelfKind.NOTES || peer.isEmpty()) return
        for (id in ids) notes[id]?.let { recordSent(id, peer, it.modified) }
    }

    /** A peer was unpaired: what was remembered of it goes (the notes stay). */
    fun forgetPeer(peer: String) {
        for (marks in listOf(held, sent)) {
            marks.values.forEach { it.remove(peer) }
            marks.values.removeAll { it.isEmpty() }
        }
    }

    /** S2: the newer wins (equal: ours). Whether [incoming] was taken. */
    fun mergeSnippet(incoming: Snippet): Boolean {
        val local = snippets[incoming.id]
        if (local != null && local.modified >= incoming.modified) return false
        snippets[incoming.id] = incoming
        return true
    }

    /** N2: [incoming] came from [peer] (empty: unknown), which therefore holds it. */
    fun mergeNote(incoming: Note, peer: String = ""): Merged {
        val local = notes[incoming.id] ?: run {
            take(incoming, peer)
            return Merged.TAKEN
        }
        // Equal `modified` is the same version only if it says the same (two sides can edit in the same millisecond); otherwise siblings.
        if (local.modified == incoming.modified && local.sameContent(incoming)) {
            synced[local.id] = local.modified
            recordHeld(local.id, peer, local.modified)
            return Merged.SAME
        }
        if (incoming.parent == local.modified || local.modified in incoming.ancestors) {
            take(incoming, peer)
            return Merged.TAKEN
        }
        if (local.parent == incoming.modified || incoming.modified in local.ancestors) {
            // Its version is an ancestor of ours: that peer holds it.
            recordHeld(local.id, peer, incoming.modified)
            return Merged.KEPT_OURS
        }
        // Siblings, both edited since the last agreement.
        val incomingWins = when {
            local.deleted != incoming.deleted -> local.deleted // a deletion never beats a concurrent edit
            local.sameContent(incoming) -> incoming.modified > local.modified
            incoming.modified != local.modified -> incoming.modified > local.modified
            else -> compareBytes(incoming.body, local.body) > 0
        }
        val (winner, loser) = if (incomingWins) incoming to local else local to incoming
        val conflict = !winner.sameContent(loser) && !winner.deleted && !loser.deleted
        val copy = if (conflict) loser.copy(id = "${loser.id}~${loser.modified}", title = "${loser.label} $COPY_SUFFIX", parent = loser.modified) else null
        // A copy that is already here, as it was, edited or deleted, was resolved before: it is never written over (that
        // reverted the person's edits to it and brought deleted copies back).
        val fresh = copy?.takeIf { it.id !in notes }
        if (!incomingWins && (!conflict || fresh == null)) return Merged.KEPT_OURS
        fresh?.let { take(it, "") }
        take(winner, if (incomingWins) peer else "")
        return if (fresh != null) Merged.CONFLICT else Merged.TAKEN
    }

    /**
     * Peer [peer] holds [modified] of note [id] (it took it, or listed it): the version our next edit names as its `parent`,
     * when it is one of ours and newer than what that peer was known to hold. Whether anything moved.
     */
    fun acknowledge(id: String, modified: Long, peer: String = ""): Boolean {
        val note = notes[id] ?: return false
        val chain = listOf(note.modified) + note.ancestors
        val at = chain.indexOf(modified)
        if (at < 0) return false
        fun position(version: Long?) = version?.let(chain::indexOf)?.takeIf { it >= 0 } ?: Int.MAX_VALUE
        var moved = false
        if (at < position(synced[id])) {
            synced[id] = modified
            moved = true
        }
        if (peer.isNotEmpty() && at < position(held[id]?.get(peer))) {
            recordHeld(id, peer, modified)
            moved = true
        }
        return moved
    }

    private fun take(note: Note, peer: String) {
        synced[note.id] = note.modified
        recordHeld(note.id, peer, note.modified)
        notes[note.id] = note
    }

    /** The ids in a peer's index to fetch: lacking, or (snippets) newer, or (notes) different. */
    fun wanted(kind: ShelfKind, index: JSONArray): List<String> = (0 until minOf(index.length(), MAX_INDEX_ENTRIES)).mapNotNull { i ->
        val entry = index.optJSONObject(i) ?: return@mapNotNull null
        val id = entry.opt("id") as? String ?: return@mapNotNull null
        val modified = (entry.opt("modified") as? Number)?.toLong() ?: return@mapNotNull null
        if (!validId(id)) return@mapNotNull null
        val ours = when (kind) {
            ShelfKind.SNIPPETS -> snippets[id]?.modified
            ShelfKind.NOTES -> notes[id]?.modified
        }
        id.takeIf {
            when {
                ours == null -> true
                kind == ShelfKind.SNIPPETS -> modified > ours
                else -> modified != ours
            }
        }
    }

    /**
     * The index, split under [PACKET_BUDGET] (a full shelf's index is far larger than one packet).
     * Nothing is deleted by absence, so several index packets mean the same as one.
     */
    fun indexPackets(kind: ShelfKind): List<NetworkPacket> {
        val all: Collection<Triple<String, Long, Boolean>> = when (kind) {
            ShelfKind.SNIPPETS -> snippets.values.map { Triple(it.id, it.modified, it.deleted) }
            ShelfKind.NOTES -> notes.values.filter { !isBlankNew(it) }.map { Triple(it.id, it.modified, it.deleted) }
        }
        val summaries = all.map { (id, modified, deleted) -> JSONObject().put("id", id).put("modified", modified).apply { if (deleted) put("deleted", true) } }
        return chunked(summaries).ifEmpty { listOf(JSONArray()) }.map { items ->
            NetworkPacket(kind.packetType).apply {
                this["op"] = "index"
                this["items"] = items
            }
        }
    }

    fun itemValues(kind: ShelfKind, ids: Collection<String>): List<JSONObject> = ids.mapNotNull { id ->
        when (kind) {
            ShelfKind.SNIPPETS -> snippets[id]?.let(::toJson)
            ShelfKind.NOTES -> notes[id]?.let(::toJson)
        }
    }

    /** A packet from a peer: the packets to answer with, and whether the shelf changed (to save). */
    fun handle(kind: ShelfKind, packet: NetworkPacket, peer: String = ""): Pair<List<NetworkPacket>, Boolean> = when (packet.getStringOrNull("op")) {
        "index" -> {
            val index = packet.getJSONArray("items") ?: JSONArray()
            // What the peer lists exactly as we hold it, it holds: the base of our next edit.
            var moved = false
            if (kind == ShelfKind.NOTES) {
                for (i in 0 until minOf(index.length(), MAX_INDEX_ENTRIES)) {
                    val entry = index.optJSONObject(i) ?: continue
                    val id = entry.opt("id") as? String ?: continue
                    val modified = (entry.opt("modified") as? Number)?.toLong() ?: continue
                    if (acknowledge(id, modified, peer)) moved = true
                }
            }
            val ids = wanted(kind, index)
            ids.chunked(FETCH_IDS).map { chunk ->
                NetworkPacket(kind.packetType).apply {
                    this["op"] = "fetch"
                    this["ids"] = chunk
                }
            } to moved
        }
        "ack" -> {
            // The peer took these versions of ours (older builds never send this).
            var changed = false
            if (kind == ShelfKind.NOTES) {
                val raw = packet.getJSONArray("items") ?: JSONArray()
                for (i in 0 until minOf(raw.length(), MAX_ITEMS_PER_PACKET)) {
                    val entry = raw.optJSONObject(i) ?: continue
                    val id = entry.opt("id") as? String ?: continue
                    val modified = (entry.opt("modified") as? Number)?.toLong() ?: continue
                    if (acknowledge(id, modified, peer)) changed = true
                }
            }
            emptyList<NetworkPacket>() to changed
        }
        "fetch" -> {
            val raw = packet.getJSONArray("ids") ?: JSONArray()
            val ids = (0 until raw.length()).mapNotNull { raw.opt(it) as? String }.take(FETCH_IDS)
            sentTo(kind, ids, peer)
            itemsPackets(kind, itemValues(kind, ids)) to false
        }
        "items" -> {
            val raw = packet.getJSONArray("items") ?: JSONArray()
            var changed = false
            val sendBack = mutableListOf<String>()
            val took = mutableListOf<Pair<String, Long>>()
            for (i in 0 until minOf(raw.length(), MAX_ITEMS_PER_PACKET)) {
                val item = raw.optJSONObject(i) ?: continue
                when (kind) {
                    ShelfKind.SNIPPETS -> {
                        val snippet = snippetFrom(item)?.takeIf(::validSnippet) ?: continue
                        if (snippet.id !in snippets && snippets.size >= MAX_STORED_SNIPPETS) continue
                        val older = snippets[snippet.id]?.let { it.modified > snippet.modified } == true
                        if (mergeSnippet(snippet)) changed = true else if (older) sendBack += snippet.id
                    }
                    ShelfKind.NOTES -> {
                        val note = noteFrom(item)?.takeIf(::validNote) ?: continue
                        // A conflict may add a copy too, hence the 1 spare.
                        if (note.id !in notes && notes.size >= MAX_STORED_NOTES - 1) continue
                        when (mergeNote(note, peer)) {
                            Merged.KEPT_OURS -> sendBack += note.id
                            Merged.SAME -> took += note.id to note.modified
                            Merged.TAKEN -> {
                                changed = true
                                took += note.id to note.modified
                            }
                            // The peer resolves it alike once it has ours.
                            Merged.CONFLICT -> {
                                changed = true
                                sendBack += note.id
                            }
                        }
                    }
                }
            }
            // Tell the sender it now has these versions here (its next edit then names one as its base instead of an older one).
            val acks = took.chunked(FETCH_IDS).map { chunk ->
                NetworkPacket(kind.packetType).apply {
                    this["op"] = "ack"
                    this["items"] = JSONArray(chunk.map { (id, modified) -> JSONObject().put("id", id).put("modified", modified) })
                }
            }
            sentTo(kind, sendBack, peer)
            (itemsPackets(kind, itemValues(kind, sendBack)) + acks) to changed
        }
        else -> emptyList<NetworkPacket>() to false
    }

    // ---- local edits ---------------------------------------------------------------------------------------

    /**
     * One local edit of note [id] (title, body, pinned or deleted): the ids to push (the note, and a copy made of what the
     * person typed over), and the note's `modified` after it, read in the same step; null when nothing changed (the editor
     * then keeps the version it loaded, and a newer one that landed is shown when it reloads). [loaded] is the version the
     * editor was showing: when another version landed meanwhile, the edit is merged as a sibling of it, so that version is kept
     * as a "conflicted copy" instead of being overwritten without a word.
     */
    fun applyEdit(id: String, loaded: Long?, now: Long, change: (Note) -> Note): Pair<List<String>, Long?> {
        val none = emptyList<String>() to null
        val before = notes[id] ?: return none
        var after = change(before).let { it.copy(id = before.id, title = it.title.takeCodePoints(MAX_NOTE_TITLE_CHARS)) }
        // Text typed over a note that was deleted underneath the editor is kept, not wiped: it brings the note back.
        if (before.deleted && after.deleted && (after.title.isNotEmpty() || after.body.isNotEmpty())) after = after.copy(deleted = false)
        if (!syncable(after.body) || after.sameContent(before)) return none
        if (after.deleted) after = after.copy(title = "", body = "")
        // (A tombstone is not a version the person typed over: no copy of it.)
        if (loaded != null && loaded != before.modified && loaded > 0 && !before.deleted) {
            val mine = after.copy(parent = loaded, modified = maxOf(now, before.modified + 1), ancestors = listOf(loaded))
            val known = notes.keys.toSet()
            mergeNote(mine)
            return (listOf(id) + (notes.keys - known)) to notes[id]?.modified
        }
        notes[id] = edited(before, after, baseOf(id), now, mustKeep(id))
        return listOf(id) to notes[id]?.modified
    }

    // ---- duplicate conflicted copies -----------------------------------------------------------------------

    /**
     * Which live copies to delete, each with the note that stays and says the same. A copy goes only when something that
     * stays says the same: [everywhere] is the person's "Remove duplicate copies" (any other live note, or the lowest-id one
     * of identical copies); without it, only the note it was made from, or an identical copy of the same note. A copy whose
     * text is anywhere else unique is never listed. Each key is computed once, and only for the notes whose body is as long
     * as some copy's.
     */
    fun duplicateCopies(everywhere: Boolean): List<Pair<String, String>> {
        val copies = notes.values.filter { !it.deleted && isCopy(it) }
        if (copies.isEmpty()) return emptyList()
        val lengths = copies.mapTo(HashSet()) { it.body.length }
        val groups = HashMap<Pair<String, String>, MutableList<Note>>()
        for (note in notes.values) {
            if (!note.deleted && note.body.length in lengths) groups.getOrPut(contentKey(note)) { mutableListOf() }.add(note)
        }
        val found = mutableListOf<Pair<String, String>>()
        for (copy in copies) {
            val base = copyBase(copy.id)
            val group = groups[contentKey(copy)] ?: continue
            // Something that is not itself a copy, or the made-from note, says it too; else an identical copy with a lower id.
            val survivor = group.firstOrNull { !isCopy(it) && it.id != copy.id && (everywhere || it.id == base) }
                ?: group.firstOrNull { isCopy(it) && it.id < copy.id && (everywhere || copyBase(it.id) == base) }
            if (survivor != null) found += copy.id to survivor.id
        }
        return found
    }

    /**
     * Deletes (as the person would: a tombstone that syncs) the copies [duplicateCopies] lists. A pinned copy pins the note
     * that stays, so a pin is never lost. The ids changed (to send).
     */
    fun removeDuplicates(everywhere: Boolean, now: Long): List<String> {
        val doomed = duplicateCopies(everywhere).toMap(TreeMap())
        val toPin = TreeSet<String>()
        for ((copy, survivor) in doomed) {
            // The survivor may itself be going (a lower-id copy): follow to what stays.
            var stays = survivor
            while (true) stays = doomed[stays] ?: break
            if (notes[copy]?.pinned == true && notes[stays]?.pinned != true) toPin += stays
        }
        val changed = mutableListOf<String>()
        for (id in toPin) {
            val before = notes[id] ?: continue
            notes[id] = edited(before, before.copy(pinned = true), baseOf(id), now, mustKeep(id))
            changed += id
        }
        for (id in doomed.keys) {
            val before = notes[id] ?: continue
            notes[id] = edited(before, before.copy(deleted = true, title = "", body = ""), baseOf(id), now, mustKeep(id))
            changed += id
        }
        return changed
    }

    /** Drops tombstones older than 90 days. Whether anything went. */
    fun prune(now: Long): Boolean {
        val cutoff = now - TOMBSTONE_DAYS * 24 * 3600 * 1000
        val before = snippets.size + notes.size + synced.size
        snippets.values.removeAll { it.deleted && it.modified <= cutoff }
        notes.values.removeAll { it.deleted && it.modified <= cutoff }
        synced.keys.retainAll(notes.keys)
        held.keys.retainAll(notes.keys)
        sent.keys.retainAll(notes.keys)
        return before != snippets.size + notes.size + synced.size
    }

    /** The stored form (sealed by [NotesStore]). */
    fun toStored(): JSONObject = JSONObject()
        .put("version", STORED_VERSION)
        .put("snippets", JSONArray().apply { snippets.values.forEach { put(toJson(it)) } })
        .put("notes", JSONArray().apply { notes.values.forEach { put(toJson(it)) } })
        .put("synced", JSONObject().apply { synced.forEach { (id, at) -> put(id, at) } })
        .put("held", peersJson(held))
        .put("sent", peersJson(sent))

    private fun peersJson(marks: TreeMap<String, TreeMap<String, Long>>) = JSONObject().apply {
        marks.forEach { (id, peers) -> put(id, JSONObject().apply { peers.forEach { (peer, version) -> put(peer, version) } }) }
    }

    companion object {
        const val MAX_ID_CHARS = 128
        const val MAX_SNIPPETS = 500
        const val MAX_SNIPPET_BYTES = 16 * 1024
        const val MAX_SNIPPET_TITLE_CHARS = 120
        const val MAX_NOTES = 2000
        const val MAX_NOTE_TITLE_CHARS = 200
        /** Wire size fix: a note body, so its escaped JSON fits one packet (was 64 KiB; over it is dropped on receipt). */
        const val MAX_NOTE_BYTES = 20 * 1024
        /** Ids per `fetch` (contract). */
        const val FETCH_IDS = 100

        /** A peer's `items` beyond this in one packet are ignored: a 100 KiB packet cannot hold more real ones. */
        const val MAX_ITEMS_PER_PACKET = 500

        /** Index entries read from one packet: every stored item fits, and a junk index costs bounded work. */
        const val MAX_INDEX_ENTRIES = 5000

        /** Stored notes, tombstones and conflicted copies included: bounds memory and the sealed file (~140 MB worst case). */
        const val MAX_STORED_NOTES = 4000

        /** Stored snippets, tombstones included (16 KiB each at most: 32 MB worst case). */
        const val MAX_STORED_SNIPPETS = 1000

        /** The stored file's schema; a newer one is kept aside rather than overwritten. */
        const val STORED_VERSION = 1
        const val TOMBSTONE_DAYS = 90L

        /** One packet's serialized size (`WebRtcEnvelope.FEATURE_PACKET_BUDGET`: under the data channel's 64 KiB once enveloped). */
        const val PACKET_BUDGET = org.desklink.mobile.webrtc.WebRtcEnvelope.FEATURE_PACKET_BUDGET

        /**
         * The largest note body this phone writes: one note must fit one packet, so a body past
         * this could not cross the link (the contract's 64 KiB is still accepted on receipt).
         */
        const val MAX_SYNCABLE_NOTE_BYTES = 34 * 1024

        /** A body this phone may save: as JSON (escapes counted) within [MAX_SYNCABLE_NOTE_BYTES]. */
        fun syncable(body: String) = body.toByteArray().size <= MAX_NOTE_BYTES && JSONObject.quote(body).toByteArray().size <= MAX_SYNCABLE_NOTE_BYTES

        /** [values] in arrays each under [budget] serialized bytes; a value alone over it is dropped. */
        fun chunked(values: List<JSONObject>, budget: Int = PACKET_BUDGET): List<JSONArray> {
            val out = mutableListOf<JSONArray>()
            var batch = JSONArray()
            var size = 0
            for (value in values) {
                val length = value.toString().toByteArray().size + 1
                if (length > budget - 256) continue
                if (size + length > budget - 256 && batch.length() > 0) {
                    out += batch
                    batch = JSONArray()
                    size = 0
                }
                batch.put(value)
                size += length
            }
            if (batch.length() > 0) out += batch
            return out
        }

        fun fromStored(json: JSONObject): Shelf = Shelf().apply {
            require(json.optInt("version", 1) <= STORED_VERSION) { "written by a newer DeskLink" }
            json.optJSONArray("snippets")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::snippetFrom) }.forEach { snippets[it.id] = it } }
            json.optJSONArray("notes")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::noteFrom) }.forEach { notes[it.id] = it } }
            json.optJSONObject("synced")?.let { o -> o.keys().forEach { id -> synced[id] = o.optLong(id) } }
            for ((name, marks) in listOf("held" to held, "sent" to sent)) {
                json.optJSONObject(name)?.let { all ->
                    all.keys().forEach { id ->
                        val peers = all.optJSONObject(id) ?: return@forEach
                        peers.keys().forEach { peer -> marks.getOrPut(id) { TreeMap() }[peer] = peers.optLong(peer) }
                    }
                }
            }
        }

        fun validId(id: String) = id.isNotEmpty() && id.codePointCount(0, id.length) <= MAX_ID_CHARS && id.none(Char::isISOControl)

        fun validSnippet(s: Snippet) = validId(s.id) && s.text.toByteArray().size <= MAX_SNIPPET_BYTES &&
            s.title.codePointCount(0, s.title.length) <= MAX_SNIPPET_TITLE_CHARS && (s.deleted || s.text.isNotEmpty()) && s.modified > 0

        fun validNote(n: Note) = validId(n.id) && n.body.toByteArray().size <= MAX_NOTE_BYTES &&
            n.title.codePointCount(0, n.title.length) <= MAX_NOTE_TITLE_CHARS && n.modified > 0

        fun toJson(s: Snippet): JSONObject = JSONObject().put("id", s.id).put("text", s.text).put("title", s.title).put("modified", s.modified)
            .apply { if (s.deleted) put("deleted", true) }

        fun toJson(n: Note): JSONObject = JSONObject().put("id", n.id).put("title", n.title).put("body", n.body).put("pinned", n.pinned)
            .put("created", n.created).put("modified", n.modified).put("parent", n.parent)
            .apply {
                if (n.deleted) put("deleted", true)
                if (n.ancestors.isNotEmpty()) put("ancestors", org.json.JSONArray(n.ancestors.take(MAX_ANCESTORS)))
            }

        /**
         * The note [after] an edit of [before], as it is stored and pushed: `parent` is [base] (the oldest version a peer is known
         * to hold, else the one both sides last had), `modified` is [now] (strictly after [before]), and [Note.ancestors] grows
         * by the version it replaces; past [MAX_ANCESTORS] the versions in [keep] (held by, or sent to, a peer) stay and the
         * rest are the newest others.
         */
        fun edited(before: Note, after: Note, base: Long?, now: Long, keep: List<Long> = emptyList()): Note = after.copy(
            parent = base ?: 0,
            modified = maxOf(now, before.modified + 1),
            ancestors = ancestorsAfter(before, keep),
        )

        fun ancestorsAfter(before: Note, keep: List<Long>): List<Long> {
            val chain = listOf(before.modified) + before.ancestors
            if (chain.size <= MAX_ANCESTORS) return chain
            val kept = chain.filter { it in keep }.take(MAX_ANCESTORS)
            val recent = chain.filter { it !in kept }.take(MAX_ANCESTORS - kept.size)
            return (kept + recent).sortedDescending()
        }

        /** Phones remembered per note; past it the one holding the oldest version goes. */
        const val MAX_PEERS_PER_NOTE = 3

        /** A note nobody has written in or edited yet: it stays on this side until its first edit, which sends it. */
        fun isBlankNew(note: Note) = !note.deleted && note.ancestors.isEmpty() && note.parent == 0L && trimNote(note.title).isEmpty() && trimNote(note.body).isEmpty()

        const val MAX_ANCESTORS = 8

        /** What a conflicted copy's title ends with (the desk's wording, character for character). */
        const val COPY_SUFFIX = "(conflicted copy)"

        /** The note a conflicted copy was made from: copies are `<id>~<modified>`. */
        fun copyBase(id: String): String? {
            val at = id.lastIndexOf('~')
            if (at <= 0 || at == id.length - 1) return null
            return id.substring(0, at).takeIf { id.substring(at + 1).all { it in '0'..'9' } }
        }

        fun isCopy(note: Note) = copyBase(note.id) != null && note.title.endsWith(COPY_SUFFIX)

        /**
         * What a note says, for telling duplicates: a copy's title without the suffix, or a note's whole title (its label when it
         * has none), and the body. Whole titles, not the 80-character label: two titles that only agree up to there differ.
         */
        fun contentKey(note: Note): Pair<String, String> {
            if (isCopy(note)) {
                var title = note.title
                while (title.endsWith(COPY_SUFFIX)) title = trimNote(title.removeSuffix(COPY_SUFFIX))
                return title to note.body
            }
            return (trimNote(note.title).ifEmpty { note.label }) to note.body
        }

        /** A wire item, or null when a required field is missing or of the wrong type. */
        fun snippetFrom(o: JSONObject): Snippet? {
            val id = o.opt("id") as? String ?: return null
            val text = o.opt("text") as? String ?: return null
            val modified = (o.opt("modified") as? Number)?.toLong() ?: return null
            return Snippet(id, text, o.opt("title") as? String ?: "", modified, o.opt("deleted") == true)
        }

        fun noteFrom(o: JSONObject): Note? {
            val id = o.opt("id") as? String ?: return null
            val created = (o.opt("created") as? Number)?.toLong() ?: return null
            val modified = (o.opt("modified") as? Number)?.toLong() ?: return null
            return Note(
                id = id,
                title = o.opt("title") as? String ?: "",
                body = o.opt("body") as? String ?: "",
                pinned = o.opt("pinned") == true,
                created = created,
                modified = modified,
                parent = (o.opt("parent") as? Number)?.toLong() ?: 0,
                deleted = o.opt("deleted") == true,
                ancestors = o.optJSONArray("ancestors")?.let { a -> (0 until minOf(a.length(), MAX_ANCESTORS)).mapNotNull { i -> (a.opt(i) as? Number)?.toLong() } }.orEmpty(),
            )
        }

        /** `items` packets for [values], each under [budget] serialized bytes. An item over it alone is dropped. */
        fun itemsPackets(kind: ShelfKind, values: List<JSONObject>, budget: Int = PACKET_BUDGET): List<NetworkPacket> =
            chunked(values, budget).map { items ->
                NetworkPacket(kind.packetType).apply {
                    this["op"] = "items"
                    this["items"] = items
                }
            }

        /** Rust's `&[u8]` order: UTF-8 bytes compared unsigned. */
        fun compareBytes(a: String, b: String): Int {
            val x = a.toByteArray()
            val y = b.toByteArray()
            for (i in 0 until minOf(x.size, y.size)) {
                val c = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
                if (c != 0) return c
            }
            return x.size - y.size
        }
    }
}
