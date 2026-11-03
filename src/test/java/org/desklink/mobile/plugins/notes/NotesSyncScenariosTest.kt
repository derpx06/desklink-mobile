/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notes

import org.desklink.mobile.NetworkPacket
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Notes sync as two shelves exchanging the real packets: the situations that used to leave "(conflicted copy)" notes for no
 * reason, and the real conflict that should leave exactly one. The same table as the desk's `notes/sync_scenarios.rs`.
 */
@RunWith(RobolectricTestRunner::class)
class NotesSyncScenariosTest {
    private enum class Side { DESK, PHONE;
        val other get() = if (this == DESK) PHONE else DESK
    }

    private class World {
        val desk = Shelf()
        val phone = Shelf()
        var linked = false
        private var clock = 1_000L

        fun shelf(side: Side) = if (side == Side.DESK) desk else phone

        fun connect() {
            linked = true
            for (side in Side.entries) deliver(side, shelf(side).indexPackets(ShelfKind.NOTES))
        }

        fun disconnect() {
            linked = false
        }

        fun deliver(from: Side, packets: List<NetworkPacket>) {
            val queue = ArrayDeque(packets.map { from to it })
            while (queue.isNotEmpty()) {
                val (sender, packet) = queue.removeFirst()
                val to = sender.other
                val (answers, _) = shelf(to).handle(ShelfKind.NOTES, packet)
                queue.addAll(answers.map { to to it })
            }
        }

        fun create(side: Side, body: String): String {
            val id = "n${shelf(side).notes.size}-${side.name}"
            clock += 10
            shelf(side).notes[id] = Note(id, body = body, created = clock, modified = clock)
            return id
        }

        /** A local edit (an autosave); not sent until [push]. */
        fun edit(side: Side, id: String, body: String) {
            val shelf = shelf(side)
            val before = shelf.notes.getValue(id)
            clock += 10
            shelf.notes[id] = Shelf.edited(before, before.copy(body = body), shelf.synced[id], clock)
        }

        fun delete(side: Side, id: String) {
            val shelf = shelf(side)
            val before = shelf.notes.getValue(id)
            clock += 10
            shelf.notes[id] = Shelf.edited(before, before.copy(deleted = true, title = "", body = ""), shelf.synced[id], clock)
        }

        fun push(side: Side, vararg ids: String) {
            if (!linked) return
            deliver(side, Shelf.itemsPackets(ShelfKind.NOTES, shelf(side).itemValues(ShelfKind.NOTES, ids.toList())))
        }

        fun live(side: Side) = shelf(side).notes.values.filter { !it.deleted }
        fun copies(side: Side) = live(side).count { Shelf.isCopy(it) }

        fun assertSameNotes() {
            fun view(s: Shelf) = s.notes.values.map { listOf(it.id, it.title, it.body, it.pinned, it.deleted, it.modified) }
            assertEquals("the two sides differ", view(desk), view(phone))
        }

        fun assertNoCopies() = Side.entries.forEach { assertEquals("$it has a conflicted copy", 0, copies(it)) }
    }

    @Test
    fun aOneSidedEditNeverMakesACopy() {
        for (creator in Side.entries) for (editor in Side.entries) {
            val w = World()
            val id = w.create(creator, "first")
            w.connect()
            w.edit(editor, id, "second")
            w.push(editor, id)
            w.assertNoCopies()
            w.assertSameNotes()
        }
    }

    @Test
    fun anEchoOfOurOwnEditIsNotASecondEdit() {
        val w = World()
        val id = w.create(Side.DESK, "a")
        w.connect()
        w.edit(Side.DESK, id, "b")
        w.push(Side.DESK, id)
        w.push(Side.PHONE, id)
        w.push(Side.PHONE, id)
        w.assertNoCopies()
        w.assertSameNotes()
    }

    @Test
    fun theSameEditOnBothSidesIsOneNote() {
        val w = World()
        val id = w.create(Side.DESK, "base")
        w.connect()
        w.disconnect()
        w.edit(Side.DESK, id, "same text")
        w.edit(Side.PHONE, id, "same text")
        w.connect()
        w.assertNoCopies()
        w.assertSameNotes()
    }

    @Test
    fun reconnectingWithUnchangedNotesChangesNothing() {
        val w = World()
        val one = w.create(Side.DESK, "one")
        val two = w.create(Side.PHONE, "two")
        w.connect()
        w.edit(Side.PHONE, one, "one!")
        w.push(Side.PHONE, one)
        w.assertSameNotes()
        val before = w.desk.notes.toMap()
        repeat(5) {
            w.disconnect()
            w.connect()
        }
        assertEquals(before, w.desk.notes.toMap())
        assertEquals(2, w.desk.notes.size)
        assertTrue(two in w.desk.notes)
        w.assertSameNotes()
    }

    /** The main cause: more autosaves than a note can name as ancestors (8) after the peer last held a version. */
    @Test
    fun manyAutosavesAfterThePeerLastTookAVersionAreStillOneSided() {
        for (creator in Side.entries) for (editor in Side.entries) {
            val offline = World()
            var id = offline.create(creator, "v0")
            offline.connect()
            offline.edit(editor, id, "v1")
            offline.push(editor, id)
            offline.disconnect()
            for (n in 2..14) offline.edit(editor, id, "v$n")
            offline.connect()
            offline.assertNoCopies()
            offline.assertSameNotes()
            assertEquals("v14", offline.live(creator.other).single().body)

            val typing = World()
            id = typing.create(creator, "v0")
            typing.connect()
            typing.edit(editor, id, "v1")
            typing.push(editor, id)
            for (n in 2..14) typing.edit(editor, id, "v$n")
            typing.push(editor, id)
            typing.assertNoCopies()
            typing.assertSameNotes()
        }
    }

    @Test
    fun editWhileTheOtherSideIsOfflineThenReconnect() {
        for (editor in Side.entries) {
            val w = World()
            val id = w.create(editor, "kept")
            w.connect()
            w.disconnect()
            w.edit(editor, id, "changed while away")
            w.connect()
            w.assertNoCopies()
            w.assertSameNotes()
        }
    }

    @Test
    fun aRealConflictLeavesExactlyOneCopyAndStaysOne() {
        val w = World()
        val id = w.create(Side.DESK, "base")
        w.connect()
        w.disconnect()
        w.edit(Side.DESK, id, "desk says")
        w.edit(Side.PHONE, id, "phone says")
        w.connect()
        for (side in Side.entries) assertEquals("$side", 1, w.copies(side))
        w.assertSameNotes()
        val bodies = w.live(Side.DESK).map { it.body }
        assertTrue("desk says" in bodies && "phone says" in bodies)
        repeat(5) {
            w.disconnect()
            w.connect()
            w.push(Side.DESK, id)
            w.push(Side.PHONE, id)
        }
        for (side in Side.entries) assertEquals("$side", 2, w.live(side).size)
        w.assertSameNotes()
    }

    @Test
    fun deletesAndEdits() {
        // A delete after many edits is not undone by the older version.
        var w = World()
        var id = w.create(Side.DESK, "v0")
        w.connect()
        w.edit(Side.PHONE, id, "v1")
        w.push(Side.PHONE, id)
        w.disconnect()
        for (n in 2..12) w.edit(Side.PHONE, id, "v$n")
        w.delete(Side.PHONE, id)
        w.connect()
        assertTrue(w.live(Side.DESK).isEmpty() && w.live(Side.PHONE).isEmpty())
        w.assertSameNotes()

        // A delete against a concurrent edit: the edit stays, with no copy.
        w = World()
        id = w.create(Side.DESK, "v0")
        w.connect()
        w.disconnect()
        w.delete(Side.DESK, id)
        w.edit(Side.PHONE, id, "kept")
        w.connect()
        w.assertNoCopies()
        assertEquals(1, w.live(Side.DESK).size)
        assertEquals("kept", w.live(Side.PHONE).single().body)
        w.assertSameNotes()
    }

    @Test
    fun aCopyThatWasEditedOrDeletedIsNotWrittenOverByTheSameConflict() {
        val w = World()
        val id = w.create(Side.DESK, "base")
        w.connect()
        w.disconnect()
        w.edit(Side.DESK, id, "desk says")
        w.edit(Side.PHONE, id, "phone says")
        w.connect()
        val copy = w.live(Side.PHONE).single { Shelf.isCopy(it) }
        // The person edits the copy on the phone, and deletes the desk's.
        w.edit(Side.PHONE, copy.id, "my own words")
        w.push(Side.PHONE, copy.id)
        w.delete(Side.DESK, copy.id)
        // The same siblings meet again (a re-fetch after a reconnect): the loser is the copy's `modified`.
        fun again(shelf: Shelf) = shelf.notes.getValue(id).copy(modified = copy.id.substringAfter('~').toLong(), body = "something else", parent = 1, ancestors = emptyList())
        w.phone.mergeNote(again(w.phone))
        assertEquals("an edited copy stays as edited", "my own words", w.phone.notes.getValue(copy.id).body)
        w.desk.mergeNote(again(w.desk))
        assertTrue("a deleted copy is not brought back", w.desk.notes.getValue(copy.id).deleted)
        w.disconnect()
        w.connect()
        assertTrue(w.desk.notes.getValue(copy.id).deleted)
    }

    @Test
    fun twoEditsInTheSameMillisecondAreSiblingsNotTheSameVersion() {
        val w = World()
        val id = w.create(Side.DESK, "base")
        w.phone.notes[id] = w.desk.notes.getValue(id)
        for ((shelf, body) in listOf(w.desk to "desk", w.phone to "phone")) {
            val n = shelf.notes.getValue(id)
            shelf.notes[id] = n.copy(body = body, modified = n.modified + 5, ancestors = listOf(n.created))
        }
        // (The index lists only `modified`, so a tie is met when an edit is pushed.)
        w.linked = true
        w.push(Side.DESK, id)
        w.push(Side.PHONE, id)
        for (side in Side.entries) assertEquals("$side", 1, w.copies(side))
        w.assertSameNotes()
    }

    @Test
    fun rapidAutosavesWhileASnapshotArrives() {
        val w = World()
        val id = w.create(Side.DESK, "v0")
        w.connect()
        for (n in 1..20) {
            w.edit(Side.DESK, id, "v$n")
            w.push(Side.DESK, id)
            if (n % 5 == 0) w.deliver(Side.PHONE, w.phone.indexPackets(ShelfKind.NOTES))
        }
        w.assertNoCopies()
        w.assertSameNotes()
    }

    @Test
    fun acksAreIgnoredByWhatDoesNotKnowThemAndNeverMoveSyncedBack() {
        val shelf = Shelf()
        shelf.notes["n"] = Note("n", body = "a", created = 1, modified = 10)
        val second = Shelf.edited(shelf.notes.getValue("n"), shelf.notes.getValue("n").copy(body = "b"), null, 20).also { shelf.notes["n"] = it }
        assertTrue(shelf.acknowledge("n", second.modified))
        assertFalse("never backwards", shelf.acknowledge("n", 10))
        assertFalse("not a version of ours", shelf.acknowledge("n", 12345))
        assertFalse(shelf.acknowledge("nope", second.modified))
        assertEquals(20L, shelf.synced["n"])
        val ack = NetworkPacket(ShelfKind.SNIPPETS.packetType).apply {
            this["op"] = "ack"
            this["items"] = JSONArray().put(JSONObject().put("id", "n").put("modified", 10))
        }
        assertEquals(emptyList<NetworkPacket>() to false, shelf.handle(ShelfKind.SNIPPETS, ack))
    }

    // ---- removing duplicate copies -------------------------------------------------------------------

    private fun copyOf(base: Note, title: String, body: String, modified: Long) =
        base.copy(id = "${base.id}~$modified", title = "$title ${Shelf.COPY_SUFFIX}", body = body, modified = modified, parent = modified)

    private fun liveTexts(shelf: Shelf) = shelf.notes.values.filter { !it.deleted }.map { it.label to it.body }

    @Test
    fun duplicateCopiesGoAndCopiesWithTheirOwnTextStay() {
        val shelf = Shelf()
        val base = Note("n", body = "Shopping\nmilk", created = 1, modified = 10)
        shelf.notes["n"] = base
        for ((m, title, body) in listOf(
            Triple(11L, "Shopping", "Shopping\nmilk"),
            Triple(12L, "Shopping", "Shopping\nmilk"),
            Triple(13L, "Shopping", "Shopping\nmilk!"),
            Triple(14L, "Other title", "Shopping\nmilk"),
        )) copyOf(base, title, body, m).let { shelf.notes[it.id] = it }
        val removed = shelf.removeDuplicates(false, 500)
        assertEquals(2, removed.size)
        assertTrue(shelf.notes.getValue("n~11").deleted && shelf.notes.getValue("n~12").deleted)
        assertFalse("differs by a character", shelf.notes.getValue("n~13").deleted)
        assertFalse("another title", shelf.notes.getValue("n~14").deleted)
        assertTrue(("Shopping" to "Shopping\nmilk") in liveTexts(shelf))
        assertTrue(shelf.removeDuplicates(true, 600).isEmpty())
        val tombstone = shelf.notes.getValue("n~11")
        assertTrue(tombstone.title.isEmpty() && tombstone.body.isEmpty() && tombstone.modified > 11)
    }

    @Test
    fun theManualActionAlsoRemovesACopyEqualToAnyOtherNoteButNeverTheLastOne() {
        val shelf = Shelf()
        shelf.notes["a"] = Note("a", body = "same words", created = 1, modified = 10)
        val b = Note("b", body = "unrelated", created = 1, modified = 10)
        shelf.notes["b"] = b
        for (c in listOf(copyOf(b, "same words", "same words", 21), copyOf(b, "lone", "only here", 22), copyOf(b, "lone", "only here", 23))) shelf.notes[c.id] = c
        assertEquals(1, shelf.removeDuplicates(false, 500).size)
        assertEquals(listOf("b~21"), shelf.removeDuplicates(true, 600))
        val texts = liveTexts(shelf)
        assertTrue(texts.any { it.second == "same words" })
        assertEquals("the last one stays", 1, texts.count { it.second == "only here" })
    }

    @Test
    fun aCopyWhoseOriginalWasDeletedIsTheOnlyTextLeftAndStays() {
        val shelf = Shelf()
        val base = Note("n", body = "words", created = 1, modified = 10)
        shelf.notes["n"] = base
        copyOf(base, "words", "words", 31).let { shelf.notes[it.id] = it }
        shelf.notes["n"] = Shelf.edited(base, base.copy(deleted = true, title = "", body = ""), null, 40)
        assertTrue(shelf.removeDuplicates(false, 500).isEmpty())
        assertTrue(shelf.removeDuplicates(true, 500).isEmpty())
        assertEquals(listOf("words"), liveTexts(shelf).map { it.second })
    }

    @Test
    fun removedDuplicatesSyncAsDeletesAndDoNotComeBack() {
        val w = World()
        val id = w.create(Side.DESK, "Plan\nbody")
        w.connect()
        val base = w.desk.notes.getValue(id)
        for (m in 41L..45L) copyOf(base, "Plan", "Plan\nbody", m).let { w.desk.notes[it.id] = it; w.phone.notes[it.id] = it }
        val unique = copyOf(base, "Plan", "Plan\nbody v2", 46)
        w.desk.notes[unique.id] = unique
        w.phone.notes[unique.id] = unique
        val removed = w.desk.removeDuplicates(true, 100_000)
        assertEquals(5, removed.size)
        w.push(Side.DESK, *removed.toTypedArray())
        w.disconnect()
        w.connect()
        for (side in Side.entries) {
            assertEquals("$side: the note and the one different copy", 2, w.live(side).size)
            assertNotNull(w.live(side).firstOrNull { it.id == unique.id })
        }
        w.assertSameNotes()
        assertTrue(w.phone.removeDuplicates(false, 200_000).isEmpty())
    }
}
