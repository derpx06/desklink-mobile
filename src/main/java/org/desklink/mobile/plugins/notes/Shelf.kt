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
) {
    /** Its title, or its first line without heading/list marks, or "New Note"; the desk derives conflicted-copy titles alike. */
    val label: String
        get() = if (title.isNotBlank()) {
            title.trim().takeCodePoints(80)
        } else {
            (body.split('\n').map { it.removeSuffix("\r").trimStart('#', ' ', '-', '*').trim() }.firstOrNull(String::isNotEmpty) ?: "New Note")
                .takeCodePoints(80)
        }

    fun sameContent(other: Note) = title == other.title && body == other.body && pinned == other.pinned && deleted == other.deleted
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

    /** S2: the newer wins (equal: ours). Whether [incoming] was taken. */
    fun mergeSnippet(incoming: Snippet): Boolean {
        val local = snippets[incoming.id]
        if (local != null && local.modified >= incoming.modified) return false
        snippets[incoming.id] = incoming
        return true
    }

    /** N2. */
    fun mergeNote(incoming: Note): Merged {
        val local = notes[incoming.id] ?: run {
            take(incoming)
            return Merged.TAKEN
        }
        if (local.modified == incoming.modified) {
            synced[local.id] = local.modified
            return Merged.SAME
        }
        if (incoming.parent == local.modified) {
            take(incoming)
            return Merged.TAKEN
        }
        if (local.parent == incoming.modified) return Merged.KEPT_OURS
        // Siblings, both edited since the last agreement.
        val incomingWins = when {
            local.deleted != incoming.deleted -> local.deleted // a deletion never beats a concurrent edit
            local.sameContent(incoming) -> incoming.modified > local.modified
            incoming.modified != local.modified -> incoming.modified > local.modified
            else -> compareBytes(incoming.body, local.body) > 0
        }
        val (winner, loser) = if (incomingWins) incoming to local else local to incoming
        val conflict = !winner.sameContent(loser) && !winner.deleted && !loser.deleted
        val copy = if (conflict) loser.copy(id = "${loser.id}~${loser.modified}", title = "${loser.label} (conflicted copy)", parent = loser.modified) else null
        // Ours stays and the copy is already here: resolved before.
        if (!incomingWins && (copy == null || notes[copy.id] == copy)) return Merged.KEPT_OURS
        copy?.let(::take)
        take(winner)
        return if (conflict) Merged.CONFLICT else Merged.TAKEN
    }

    private fun take(note: Note) {
        synced[note.id] = note.modified
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

    fun indexPacket(kind: ShelfKind): NetworkPacket {
        val items = JSONArray()
        val all: Collection<Triple<String, Long, Boolean>> = when (kind) {
            ShelfKind.SNIPPETS -> snippets.values.map { Triple(it.id, it.modified, it.deleted) }
            ShelfKind.NOTES -> notes.values.map { Triple(it.id, it.modified, it.deleted) }
        }
        all.forEach { (id, modified, deleted) ->
            items.put(JSONObject().put("id", id).put("modified", modified).apply { if (deleted) put("deleted", true) })
        }
        return NetworkPacket(kind.packetType).apply {
            this["op"] = "index"
            this["items"] = items
        }
    }

    fun itemValues(kind: ShelfKind, ids: Collection<String>): List<JSONObject> = ids.mapNotNull { id ->
        when (kind) {
            ShelfKind.SNIPPETS -> snippets[id]?.let(::toJson)
            ShelfKind.NOTES -> notes[id]?.let(::toJson)
        }
    }

    /** A packet from a peer: the packets to answer with, and whether the shelf changed (to save). */
    fun handle(kind: ShelfKind, packet: NetworkPacket): Pair<List<NetworkPacket>, Boolean> = when (packet.getStringOrNull("op")) {
        "index" -> {
            val ids = wanted(kind, packet.getJSONArray("items") ?: JSONArray())
            ids.chunked(FETCH_IDS).map { chunk ->
                NetworkPacket(kind.packetType).apply {
                    this["op"] = "fetch"
                    this["ids"] = chunk
                }
            } to false
        }
        "fetch" -> {
            val raw = packet.getJSONArray("ids") ?: JSONArray()
            val ids = (0 until raw.length()).mapNotNull { raw.opt(it) as? String }.take(FETCH_IDS)
            itemsPackets(kind, itemValues(kind, ids)) to false
        }
        "items" -> {
            val raw = packet.getJSONArray("items") ?: JSONArray()
            var changed = false
            val sendBack = mutableListOf<String>()
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
                        when (mergeNote(note)) {
                            Merged.KEPT_OURS -> sendBack += note.id
                            Merged.SAME -> Unit
                            Merged.TAKEN -> changed = true
                            // The peer resolves it alike once it has ours.
                            Merged.CONFLICT -> {
                                changed = true
                                sendBack += note.id
                            }
                        }
                    }
                }
            }
            itemsPackets(kind, itemValues(kind, sendBack)) to changed
        }
        else -> emptyList<NetworkPacket>() to false
    }

    /** Drops tombstones older than 90 days. Whether anything went. */
    fun prune(now: Long): Boolean {
        val cutoff = now - TOMBSTONE_DAYS * 24 * 3600 * 1000
        val before = snippets.size + notes.size + synced.size
        snippets.values.removeAll { it.deleted && it.modified <= cutoff }
        notes.values.removeAll { it.deleted && it.modified <= cutoff }
        synced.keys.retainAll(notes.keys)
        return before != snippets.size + notes.size + synced.size
    }

    /** The stored form (sealed by [NotesStore]). */
    fun toStored(): JSONObject = JSONObject()
        .put("version", STORED_VERSION)
        .put("snippets", JSONArray().apply { snippets.values.forEach { put(toJson(it)) } })
        .put("notes", JSONArray().apply { notes.values.forEach { put(toJson(it)) } })
        .put("synced", JSONObject().apply { synced.forEach { (id, at) -> put(id, at) } })

    companion object {
        const val MAX_ID_CHARS = 128
        const val MAX_SNIPPETS = 500
        const val MAX_SNIPPET_BYTES = 16 * 1024
        const val MAX_SNIPPET_TITLE_CHARS = 120
        const val MAX_NOTES = 2000
        const val MAX_NOTE_TITLE_CHARS = 200
        const val MAX_NOTE_BYTES = 64 * 1024
        /** Ids per `fetch` (contract). */
        const val FETCH_IDS = 100

        /** A peer's `items` beyond this in one packet are ignored: a 100 KiB packet cannot hold more real ones. */
        const val MAX_ITEMS_PER_PACKET = 500

        /** Index entries read from one packet: every stored item fits, and a junk index costs bounded work. */
        const val MAX_INDEX_ENTRIES = 5000

        /** Stored notes, tombstones and conflicted copies included: bounds memory and the sealed file (~140 MB worst case). */
        const val MAX_STORED_NOTES = 4000

        /** Stored snippets, tombstones included (16 KiB each at most: 32 MB worst case). */
        const val MAX_STORED_SNIPPETS = 2000

        /** The stored file's schema; a newer one is kept aside rather than overwritten. */
        const val STORED_VERSION = 1
        const val TOMBSTONE_DAYS = 90L

        /**
         * One `items` packet's serialized size. The contract says 256 KiB, but a feature packet must
         * stay under the WebRTC payload bound (`MAX_PAYLOAD_BYTES`, 128 KiB on both apps).
         */
        const val PACKET_BUDGET = 100 * 1024

        fun fromStored(json: JSONObject): Shelf = Shelf().apply {
            require(json.optInt("version", 1) <= STORED_VERSION) { "written by a newer DeskLink" }
            json.optJSONArray("snippets")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::snippetFrom) }.forEach { snippets[it.id] = it } }
            json.optJSONArray("notes")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::noteFrom) }.forEach { notes[it.id] = it } }
            json.optJSONObject("synced")?.let { o -> o.keys().forEach { id -> synced[id] = o.optLong(id) } }
        }

        fun validId(id: String) = id.isNotEmpty() && id.codePointCount(0, id.length) <= MAX_ID_CHARS && id.none(Char::isISOControl)

        fun validSnippet(s: Snippet) = validId(s.id) && s.text.toByteArray().size <= MAX_SNIPPET_BYTES &&
            s.title.codePointCount(0, s.title.length) <= MAX_SNIPPET_TITLE_CHARS && (s.deleted || s.text.isNotEmpty()) && s.modified > 0

        fun validNote(n: Note) = validId(n.id) && n.body.toByteArray().size <= MAX_NOTE_BYTES &&
            n.title.codePointCount(0, n.title.length) <= MAX_NOTE_TITLE_CHARS && n.modified > 0

        fun toJson(s: Snippet): JSONObject = JSONObject().put("id", s.id).put("text", s.text).put("title", s.title).put("modified", s.modified)
            .apply { if (s.deleted) put("deleted", true) }

        fun toJson(n: Note): JSONObject = JSONObject().put("id", n.id).put("title", n.title).put("body", n.body).put("pinned", n.pinned)
            .put("created", n.created).put("modified", n.modified).put("parent", n.parent).apply { if (n.deleted) put("deleted", true) }

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
            )
        }

        /** `items` packets for [values], each under [budget] serialized bytes. An item over it alone is dropped. */
        fun itemsPackets(kind: ShelfKind, values: List<JSONObject>, budget: Int = PACKET_BUDGET): List<NetworkPacket> {
            val packets = mutableListOf<NetworkPacket>()
            var batch = JSONArray()
            var size = 0
            fun flush() {
                if (batch.length() == 0) return
                val items = batch
                packets += NetworkPacket(kind.packetType).apply {
                    this["op"] = "items"
                    this["items"] = items
                }
                batch = JSONArray()
                size = 0
            }
            for (value in values) {
                val length = value.toString().toByteArray().size + 1
                if (length > budget - 256) continue // ponytail: never happens within the item bounds unless JSON escaping balloons it
                if (size + length > budget - 256) flush()
                batch.put(value)
                size += length
            }
            flush()
            return packets
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
