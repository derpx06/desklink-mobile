/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notes

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Contract F6 on the phone: the same cases as the desk's `notes.rs` tests, so both sides agree. */
@RunWith(RobolectricTestRunner::class)
class ShelfTest {
    private fun note(id: String, body: String, modified: Long, parent: Long) = Note(id, body = body, created = 1, modified = modified, parent = parent)

    private fun shelfWith(vararg notes: Note) = Shelf().apply {
        notes.forEach {
            this.notes[it.id] = it
            synced[it.id] = it.parent
        }
    }

    @Test
    fun aNoteEditedFromOursIsTakenAndOursEditedFromTheirsIsKept() {
        val shelf = shelfWith(note("n", "v1", 10, 0))
        assertEquals(Merged.TAKEN, shelf.mergeNote(note("n", "v2", 20, 10)))
        assertEquals("v2", shelf.notes["n"]!!.body)
        assertEquals(20L, shelf.synced["n"])
        shelf.notes["n"] = note("n", "v3", 30, 20)
        assertEquals(Merged.KEPT_OURS, shelf.mergeNote(note("n", "v2", 20, 10)))
        assertEquals("v3", shelf.notes["n"]!!.body)
        assertEquals(Merged.SAME, shelf.mergeNote(note("n", "v3", 30, 20)))
    }

    @Test
    fun concurrentEditsKeepBothAlikeOnBothSides() {
        val desk = note("n", "desk text", 31, 20)
        val phone = note("n", "phone text", 35, 20)
        val here = shelfWith(desk)
        val there = shelfWith(phone)
        assertEquals(Merged.CONFLICT, here.mergeNote(phone))
        assertEquals(Merged.CONFLICT, there.mergeNote(desk))
        assertEquals(here.notes, there.notes)
        assertEquals("phone text", here.notes["n"]!!.body)
        val copy = here.notes["n~31"]!!
        assertEquals("desk text", copy.body)
        assertEquals("desk text (conflicted copy)", copy.title)
        assertEquals(31L, copy.parent)
        // Seen again: nothing changes; the copy sent anyway is a no-op.
        assertEquals(Merged.KEPT_OURS, here.mergeNote(desk))
        assertEquals(Merged.SAME, there.mergeNote(phone))
        assertEquals(2, here.notes.size)
        assertEquals(Merged.SAME, there.mergeNote(here.notes["n~31"]!!))
    }

    @Test
    fun equalTimesFallBackToTheBodyBytes() {
        // Same modified would be SAME; the byte order only decides between siblings with distinct
        // content at different times, so check the comparison itself (Rust's &[u8] order).
        assertTrue(Shelf.compareBytes("b", "a") > 0)
        assertTrue(Shelf.compareBytes("é", "z") > 0) // 0xC3 > 0x7A unsigned
        assertTrue(Shelf.compareBytes("😀", "￿") > 0) // UTF-8, not UTF-16, order
        assertTrue(Shelf.compareBytes("ab", "a") > 0)
    }

    @Test
    fun aDeletionNeverBeatsAConcurrentEdit() {
        val deleted = note("n", "", 40, 20).copy(deleted = true)
        val edited = note("n", "kept", 35, 20)
        val here = shelfWith(edited)
        here.mergeNote(deleted)
        assertFalse(here.notes["n"]!!.deleted)
        assertEquals(1, here.notes.size)
        val there = shelfWith(deleted)
        there.mergeNote(edited)
        assertEquals("kept", there.notes["n"]!!.body)
    }

    @Test
    fun snippetsTakeTheNewerAndFetchWhatDiffers() {
        fun snippet(id: String, text: String, modified: Long) = Snippet(id, text, modified = modified)
        val shelf = Shelf()
        assertTrue(shelf.mergeSnippet(snippet("a", "one", 10)))
        assertFalse(shelf.mergeSnippet(snippet("a", "old", 5)))
        assertFalse("equal: keep ours", shelf.mergeSnippet(snippet("a", "tie", 10)))
        assertTrue(shelf.mergeSnippet(snippet("a", "two", 11)))
        shelf.snippets["a2"] = snippet("a2", "x", 9)
        val index = JSONArray()
            .put(JSONObject().put("id", "a").put("modified", 11))
            .put(JSONObject().put("id", "b").put("modified", 1))
            .put(JSONObject().put("id", "a2").put("modified", 3))
        assertEquals(listOf("b"), shelf.wanted(ShelfKind.SNIPPETS, index))
        val notes = shelfWith(note("n", "x", 5, 0)).apply { this.notes["m"] = note("m", "y", 9, 0) }
        val noteIndex = JSONArray()
            .put(JSONObject().put("id", "n").put("modified", 4))
            .put(JSONObject().put("id", "m").put("modified", 9))
        assertEquals(listOf("n"), notes.wanted(ShelfKind.NOTES, noteIndex))
    }

    @Test
    fun anIndexRoundTripBringsAPeerUpToDateAndJunkIsDropped() {
        val desk = Shelf().apply { notes["n"] = note("n", "hello", 10, 0) }
        val phone = Shelf()
        val (fetches, _) = phone.handle(ShelfKind.NOTES, wire(desk.indexPacket(ShelfKind.NOTES)))
        assertEquals(1, fetches.size)
        assertEquals("fetch", fetches[0].getString("op"))
        val (items, _) = desk.handle(ShelfKind.NOTES, wire(fetches[0]))
        val (back, changed) = phone.handle(ShelfKind.NOTES, wire(items[0]))
        assertTrue(changed && back.isEmpty())
        assertEquals("hello", phone.notes["n"]!!.body)

        val junk = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_NOTES).apply {
            this["op"] = "items"
            this["items"] = JSONArray()
                .put(JSONObject().put("id", "").put("modified", 1).put("created", 1))
                .put(JSONObject().put("id", "big").put("modified", 1).put("created", 1).put("body", "x".repeat(Shelf.MAX_NOTE_BYTES + 1)))
                .put(JSONObject().put("id", "nocreated").put("modified", 1))
                .put("nonsense")
        }
        assertFalse(phone.handle(ShelfKind.NOTES, wire(junk)).second)
        // An unknown op is ignored.
        val odd = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SNIPPETS).apply { this["op"] = "upsert" }
        assertEquals(emptyList<NetworkPacket>() to false, phone.handle(ShelfKind.SNIPPETS, odd))
    }

    @Test
    fun anOlderSnippetIsAnsweredWithOurs() {
        val shelf = Shelf().apply { snippets["s"] = Snippet("s", "new", modified = 20) }
        val incoming = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SNIPPETS).apply {
            this["op"] = "items"
            this["items"] = JSONArray().put(Shelf.toJson(Snippet("s", "old", modified = 10)))
        }
        val (back, changed) = shelf.handle(ShelfKind.SNIPPETS, incoming)
        assertFalse(changed)
        assertEquals("new", back.single().getJSONArray("items")!!.getJSONObject(0).getString("text"))
    }

    @Test
    fun bigRepliesSplitUnderThePayloadBoundAndTombstonesExpire() {
        val values = (0 until 40).map { Shelf.toJson(Note(it.toString(), body = "x".repeat(16 * 1024), created = 1, modified = 1)) }
        val packets = Shelf.itemsPackets(ShelfKind.NOTES, values)
        assertTrue(packets.size >= 6)
        assertTrue(packets.all { it.serialize().toByteArray().size < 128 * 1024 })
        assertEquals(40, packets.sumOf { it.getJSONArray("items")!!.length() })

        val shelf = Shelf()
        shelf.notes["old"] = note("old", "", 1, 0).copy(deleted = true)
        shelf.synced["old"] = 1
        shelf.notes["live"] = note("live", "x", 1, 0)
        assertTrue(shelf.prune(100L * 24 * 3600 * 1000))
        assertFalse("old" in shelf.notes)
        assertTrue("live" in shelf.notes)
        assertFalse("old" in shelf.synced)
    }

    @Test
    fun labelsMatchTheDesk() {
        assertEquals("Shopping", Note("a", body = "\n## - Shopping\nmilk", created = 1, modified = 1).label)
        assertEquals("New Note", Note("a", body = " \n#\n", created = 1, modified = 1).label)
        assertEquals("Title", Note("a", title = "  Title ", body = "x", created = 1, modified = 1).label)
        assertEquals(80, Note("a", title = "😀".repeat(100), created = 1, modified = 1).label.codePointCount(0, 160))
    }

    @Test
    fun theStoredFormRoundTrips() {
        val shelf = shelfWith(note("n", "body", 5, 3).copy(pinned = true, title = "T"))
        shelf.snippets["s"] = Snippet("s", "text", "title", 7)
        shelf.snippets["d"] = Snippet("d", "", modified = 8, deleted = true)
        val back = Shelf.fromStored(JSONObject(shelf.toStored().toString()))
        assertEquals(shelf.notes, back.notes)
        assertEquals(shelf.snippets, back.snippets)
        assertEquals(shelf.synced, back.synced)
    }

    @Test
    fun peerBoundsAreEnforcedOnReceipt() {
        fun items(count: Int, from: Int = 0) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SNIPPETS).apply {
            this["op"] = "items"
            this["items"] = JSONArray().apply { (from until from + count).forEach { put(Shelf.toJson(Snippet("s$it", "t", modified = 1))) } }
        }
        // Items per packet.
        val shelf = Shelf()
        shelf.handle(ShelfKind.SNIPPETS, items(Shelf.MAX_ITEMS_PER_PACKET + 1))
        assertEquals(Shelf.MAX_ITEMS_PER_PACKET, shelf.snippets.size)
        // Stored snippets.
        var at = shelf.snippets.size
        while (at < Shelf.MAX_STORED_SNIPPETS + 10) {
            shelf.handle(ShelfKind.SNIPPETS, items(Shelf.MAX_ITEMS_PER_PACKET, at))
            at += Shelf.MAX_ITEMS_PER_PACKET
        }
        assertEquals(Shelf.MAX_STORED_SNIPPETS, shelf.snippets.size)
        // Stored notes: new ids stop one short (a conflict's copy may still be added).
        val notes = Shelf()
        (0 until Shelf.MAX_STORED_NOTES).forEach { notes.notes["n$it"] = note("n$it", "x", 1, 0) }
        val one = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_NOTES).apply {
            this["op"] = "items"
            this["items"] = JSONArray().put(Shelf.toJson(note("new", "x", 1, 0)))
        }
        assertFalse(notes.handle(ShelfKind.NOTES, one).second)
        // Index entries read.
        val index = JSONArray().apply { (0..Shelf.MAX_INDEX_ENTRIES).forEach { put(JSONObject().put("id", "i$it").put("modified", 1)) } }
        assertEquals(Shelf.MAX_INDEX_ENTRIES, Shelf().wanted(ShelfKind.NOTES, index).size)
        // Bounds per item: id length, snippet bytes, title length.
        assertFalse(Shelf.validSnippet(Snippet("x".repeat(Shelf.MAX_ID_CHARS + 1), "t", modified = 1)))
        assertFalse(Shelf.validSnippet(Snippet("s", "x".repeat(Shelf.MAX_SNIPPET_BYTES + 1), modified = 1)))
        assertFalse(Shelf.validSnippet(Snippet("s", "t", "x".repeat(Shelf.MAX_SNIPPET_TITLE_CHARS + 1), modified = 1)))
        assertFalse(Shelf.validNote(Note("n", title = "x".repeat(Shelf.MAX_NOTE_TITLE_CHARS + 1), created = 1, modified = 1)))
        assertTrue(Shelf.validNote(Note("n", body = "x".repeat(Shelf.MAX_NOTE_BYTES), created = 1, modified = 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNewerStoredSchemaIsRefusedNotMisread() {
        Shelf.fromStored(JSONObject().put("version", Shelf.STORED_VERSION + 1))
    }

    /** As the peer receives it: serialized and parsed. */
    private fun wire(packet: NetworkPacket) = NetworkPacket.unserialize(packet.serialize())
}
