/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notes

import org.desklink.mobile.NetworkPacket
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    private enum class Side(val id: String) { DESK("desk"), PHONE("phone");
        val other get() = if (this == DESK) PHONE else DESK
    }

    private fun wire(packet: NetworkPacket) = NetworkPacket.unserialize(packet.serialize())

    /** Carries [packets] from [x] (called [xId] by [y]) to [y] and everything that comes back, until quiet. */
    private fun pump(x: Shelf, xId: String, y: Shelf, yId: String, packets: List<NetworkPacket>, dropAcks: Boolean = false) {
        val queue = ArrayDeque(packets.map { true to it })
        while (queue.isNotEmpty()) {
            val (toY, packet) = queue.removeFirst()
            // What crosses is what the wire carries.
            val on = wire(packet)
            val (answers, _) = if (toY) y.handle(ShelfKind.NOTES, on, xId) else x.handle(ShelfKind.NOTES, on, yId)
            queue.addAll(answers.filter { !(dropAcks && it.getStringOrNull("op") == "ack") }.map { !toY to it })
        }
    }

    private inner class World {
        val desk = Shelf()
        val phone = Shelf()
        var linked = false
        var dropAcks = false
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
            pump(shelf(from), from.id, shelf(from.other), from.other.id, packets, dropAcks)
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
            shelf.notes[id] = Shelf.edited(before, before.copy(body = body), shelf.baseOf(id), clock, shelf.mustKeep(id))
        }

        fun delete(side: Side, id: String) {
            val shelf = shelf(side)
            val before = shelf.notes.getValue(id)
            clock += 10
            shelf.notes[id] = Shelf.edited(before, before.copy(deleted = true, title = "", body = ""), shelf.baseOf(id), clock, shelf.mustKeep(id))
        }

        fun push(side: Side, vararg ids: String) {
            if (!linked) return
            // What a push records.
            shelf(side).sentTo(ShelfKind.NOTES, ids.toList(), side.other.id)
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

    // ---- two peers, lost and late acknowledgements --------------------------------------------------

    private var tick = 10_000L

    private fun nextTick(): Long {
        tick += 10
        return tick
    }

    private fun editBody(shelf: Shelf, id: String, body: String) {
        val before = shelf.notes.getValue(id)
        shelf.notes[id] = Shelf.edited(before, before.copy(body = body), shelf.baseOf(id), nextTick(), shelf.mustKeep(id))
    }

    private fun syncWith(desk: Shelf, phone: Shelf, phoneId: String) {
        pump(desk, "desk", phone, phoneId, desk.indexPackets(ShelfKind.NOTES))
        pump(phone, phoneId, desk, "desk", phone.indexPackets(ShelfKind.NOTES))
    }

    private fun pushTo(desk: Shelf, phone: Shelf, phoneId: String, id: String) {
        desk.sentTo(ShelfKind.NOTES, listOf(id), phoneId)
        pump(desk, "desk", phone, phoneId, Shelf.itemsPackets(ShelfKind.NOTES, desk.itemValues(ShelfKind.NOTES, listOf(id))))
    }

    private fun copiesIn(shelf: Shelf) = shelf.notes.values.count { !it.deleted && Shelf.isCopy(it) }

    private fun newNote(shelf: Shelf, id: String, body: String): String {
        val t = nextTick()
        shelf.notes[id] = Note(id, body = body, created = t, modified = t)
        return id
    }

    @Test
    fun aPhoneAwayThroughManyEditsIsNotARivalWhenAnotherPhoneAcknowledged() {
        val desk = Shelf()
        val a = Shelf()
        val b = Shelf()
        val id = newNote(desk, "n", "v0")
        syncWith(desk, a, "a")
        syncWith(desk, b, "b")
        // B is away; A takes and acknowledges every edit.
        for (n in 1..12) {
            editBody(desk, id, "v$n")
            pushTo(desk, a, "a", id)
        }
        assertEquals("v12", a.notes.getValue(id).body)
        syncWith(desk, b, "b")
        for ((name, shelf) in listOf("desk" to desk, "a" to a, "b" to b)) {
            assertEquals(name, 0, copiesIn(shelf))
            assertEquals(name, "v12", shelf.notes.getValue(id).body)
        }
        // The other way round: A away, B present, then A edits from its old version while the desk moved on: one real conflict.
        for (n in 13..24) {
            editBody(desk, id, "v$n")
            pushTo(desk, b, "b", id)
        }
        editBody(a, id, "a says")
        syncWith(desk, a, "a")
        syncWith(desk, b, "b")
        assertEquals(1, copiesIn(desk))
        assertEquals(1, copiesIn(a))
        assertEquals(1, copiesIn(b))
    }

    @Test
    fun aLostAcknowledgementAndManyEditsAreStillOneSided() {
        val w = World()
        val id = w.create(Side.DESK, "v0")
        w.connect()
        // The phone takes v1; its acknowledgement never arrives.
        w.dropAcks = true
        w.edit(Side.DESK, id, "v1")
        w.push(Side.DESK, id)
        w.dropAcks = false
        assertEquals("v1", w.phone.notes.getValue(id).body)
        w.disconnect()
        for (n in 2..12) w.edit(Side.DESK, id, "v$n")
        w.connect()
        w.assertNoCopies()
        w.assertSameNotes()
        assertEquals("v12", w.live(Side.PHONE).single().body)
    }

    @Test
    fun anAcknowledgementDoesNotHideARealConcurrentEdit() {
        val w = World()
        val id = w.create(Side.DESK, "base")
        w.connect()
        w.edit(Side.DESK, id, "d1")
        w.push(Side.DESK, id)
        w.disconnect()
        w.edit(Side.DESK, id, "d2")
        w.edit(Side.PHONE, id, "p1")
        w.connect()
        for (side in Side.entries) assertEquals("$side", 1, w.copies(side))
        w.assertSameNotes()
    }

    @Test
    fun aLateAcknowledgementNamesAnOlderVersionOfTheChainAndConflictsStayReal() {
        val desk = Shelf()
        val phone = Shelf()
        val id = newNote(desk, "n", "base")
        syncWith(desk, phone, "p")
        editBody(desk, id, "d1")
        val d1 = desk.notes.getValue(id).modified
        desk.sentTo(ShelfKind.NOTES, listOf(id), "p")
        val items = Shelf.itemsPackets(ShelfKind.NOTES, desk.itemValues(ShelfKind.NOTES, listOf(id)))
        // The phone takes d1 and answers; the answer is held back.
        val ack = phone.handle(ShelfKind.NOTES, wire(items[0]), "desk").first.last()
        assertEquals("ack", ack.getStringOrNull("op"))
        editBody(desk, id, "d2")
        // The late acknowledgement arrives: the phone holds d1 (not d2).
        desk.handle(ShelfKind.NOTES, wire(ack), "p")
        assertEquals(d1, desk.held.getValue(id).getValue("p"))
        // The phone edits from d1 while the desk made d2: a real conflict.
        editBody(phone, id, "p1")
        syncWith(desk, phone, "p")
        assertEquals(1, copiesIn(desk))
        assertEquals(1, copiesIn(phone))
        // Never backwards.
        val before = desk.held.getValue(id).toMap()
        val stale = NetworkPacket(ShelfKind.NOTES.packetType).apply {
            this["op"] = "ack"
            this["items"] = JSONArray().put(JSONObject().put("id", id).put("modified", d1 - 1))
        }
        desk.handle(ShelfKind.NOTES, wire(stale), "p")
        assertEquals(before, desk.held.getValue(id).toMap())
    }

    @Test
    fun theAcknowledgementTravelsTheRealHandlePath() {
        val desk = Shelf()
        val phone = Shelf()
        val id = newNote(desk, "n", "hello")
        val modified = desk.notes.getValue(id).modified
        val items = Shelf.itemsPackets(ShelfKind.NOTES, desk.itemValues(ShelfKind.NOTES, listOf(id)))
        val (answers, changed) = phone.handle(ShelfKind.NOTES, wire(items[0]), "desk")
        assertTrue(changed)
        val ack = answers.single { it.getStringOrNull("op") == "ack" }
        assertEquals(ShelfKind.NOTES.packetType, ack.type)
        val entry = ack.getJSONArray("items")!!.getJSONObject(0)
        assertEquals(id, entry.getString("id"))
        assertEquals(modified, entry.getLong("modified"))
        assertTrue(desk.held.isEmpty())
        val (back, deskChanged) = desk.handle(ShelfKind.NOTES, wire(ack), "phone")
        assertTrue(back.isEmpty() && deskChanged)
        assertEquals(modified, desk.held.getValue(id).getValue("phone"))
        val (again, changedAgain) = desk.handle(ShelfKind.NOTES, wire(ack), "phone")
        assertTrue(again.isEmpty() && !changedAgain)
        // A snippet shelf never acknowledges.
        val snippet = NetworkPacket(ShelfKind.SNIPPETS.packetType).apply {
            this["op"] = "items"
            this["items"] = JSONArray().put(JSONObject().put("id", "s").put("text", "t").put("modified", 5))
        }
        assertTrue(phone.handle(ShelfKind.SNIPPETS, wire(snippet), "desk").first.none { it.getStringOrNull("op") == "ack" })
    }

    @Test
    fun whatAPeerHeldIsForgottenWhenItIsUnpaired() {
        val desk = Shelf()
        val phone = Shelf()
        val id = newNote(desk, "n", "x")
        syncWith(desk, phone, "p")
        assertTrue(id in desk.held || id in desk.sent)
        desk.forgetPeer("p")
        assertTrue(desk.held.isEmpty() && desk.sent.isEmpty())
    }

    // ---- an editor over a note that changed underneath it --------------------------------------------

    @Test
    fun textTypedOverANoteDeletedUnderneathIsKeptAndBringsItBack() {
        val shelf = Shelf()
        val id = newNote(shelf, "n", "mine")
        val loaded = shelf.notes.getValue(id).modified
        // The desk deletes it while the editor is open.
        shelf.applyEdit(id, null, nextTick()) { it.copy(deleted = true) }
        val (ids, modified) = shelf.applyEdit(id, loaded, nextTick()) { it.copy(body = "typed after") }
        assertEquals(listOf(id), ids)
        assertNotNull(modified)
        val note = shelf.notes.getValue(id)
        assertTrue(!note.deleted && note.body == "typed after")
        assertEquals("no junk 'New Note (conflicted copy)'", 1, shelf.notes.size)
        // Deleting an already deleted note stays a deletion and changes nothing.
        shelf.applyEdit(id, null, nextTick()) { it.copy(deleted = true) }
        assertNull(shelf.applyEdit(id, null, nextTick()) { it.copy(deleted = true) }.second)
        assertTrue(shelf.notes.getValue(id).deleted)
    }

    @Test
    fun savingAnUnchangedEditorOverANewerVersionChangesNothing() {
        val shelf = Shelf()
        val id = newNote(shelf, "n", "same")
        val loaded = shelf.notes.getValue(id).modified
        shelf.applyEdit(id, null, nextTick()) { it.copy(body = "desk's") }
        val before = shelf.notes.toMap()
        val (ids, modified) = shelf.applyEdit(id, loaded, nextTick()) { it }
        assertTrue(ids.isEmpty() && modified == null)
        assertEquals(before, shelf.notes.toMap())
    }

    @Test
    fun typingOverAVersionThatLandedKeepsItAsOneCopyAndReportsTheNewVersion() {
        val shelf = Shelf()
        val id = newNote(shelf, "n", "mine")
        val loaded = shelf.notes.getValue(id).modified
        val landed = shelf.notes.getValue(id).copy(body = "desk", parent = loaded, modified = loaded + 10, ancestors = listOf(loaded))
        assertEquals(Merged.TAKEN, shelf.mergeNote(landed))
        val (ids, modified) = shelf.applyEdit(id, loaded, nextTick()) { it.copy(body = "typed") }
        assertEquals(2, ids.size)
        assertEquals(shelf.notes.getValue(id).modified, modified)
        // Saving again with the version that save left does not make another.
        val (again, _) = shelf.applyEdit(id, modified, nextTick()) { it.copy(body = "typed more") }
        assertEquals(listOf(id), again)
        assertEquals(1, shelf.notes.values.count { Shelf.isCopy(it) })
    }

    // ---- empty notes, pins, titles ---------------------------------------------------------------------

    @Test
    fun anEmptyNeverEditedNoteIsNotInTheIndexUntilItIsWrittenIn() {
        val shelf = Shelf()
        val blank = newNote(shelf, "blank", "")
        val spaces = newNote(shelf, "spaces", " \n")
        val written = newNote(shelf, "written", "text")
        fun listed() = shelf.indexPackets(ShelfKind.NOTES).flatMap { p ->
            val items = p.getJSONArray("items")!!
            (0 until items.length()).map { items.getJSONObject(it).getString("id") }
        }
        assertEquals(listOf(written), listed())
        assertEquals(1, shelf.itemValues(ShelfKind.NOTES, listOf(blank)).size)
        shelf.applyEdit(blank, null, nextTick()) { it.copy(body = "now it has words") }
        assertTrue(blank in listed())
        assertFalse(spaces in listed())
        // One that was written in and emptied again is a real note and is listed.
        shelf.applyEdit(blank, null, nextTick()) { it.copy(body = "") }
        assertTrue(blank in listed())
    }

    @Test
    fun aPinnedDuplicatePinsTheNoteThatStays() {
        val shelf = Shelf()
        val base = Note("n", body = "Plan\nbody", created = 1, modified = 10)
        shelf.notes["n"] = base
        val copy = copyOf(base, "Plan", "Plan\nbody", 51).copy(pinned = true)
        shelf.notes[copy.id] = copy
        val changed = shelf.removeDuplicates(false, 500)
        assertTrue(copy.id in changed && "n" in changed)
        assertTrue(shelf.notes.getValue(copy.id).deleted)
        assertTrue("the pin moved to the survivor", shelf.notes.getValue("n").pinned)
        // A chain: the pin ends on what stays.
        val chain = Shelf()
        chain.notes["n"] = base
        val first = copyOf(base, "Plan", "Plan\nbody", 61)
        val second = copyOf(base, "Plan", "Plan\nbody", 62).copy(pinned = true)
        chain.notes[first.id] = first
        chain.notes[second.id] = second
        chain.removeDuplicates(false, 500)
        assertTrue(chain.notes.getValue("n").pinned)
    }

    @Test
    fun titlesAreComparedWholeAndTrimmedAlikeOnBothSides() {
        // A 100-character title and a copy that only kept its first 80: not duplicates.
        val shelf = Shelf()
        val base = Note("n", title = "a".repeat(100), body = "body", created = 1, modified = 10)
        shelf.notes["n"] = base
        val copy = copyOf(base, "a".repeat(80), "body", 71)
        shelf.notes[copy.id] = copy
        assertTrue(shelf.removeDuplicates(true, 500).isEmpty())
        // The trimmed characters are an explicit set (the desk trims the same).
        fun note(title: String, body: String) = Note("x", title = title, body = body, created = 1, modified = 1)
        assertEquals("Hi", note("\u00A0Hi\u3000", "").label)
        assertEquals("# Head", note("", "\u00A0# Head \u2028\nrest").label)
        assertEquals("item", note("", "\r\n  \n- item").label)
        assertEquals("U+0085 is not trimmed", "\u0085x\u0085", note("\u0085x\u0085", "").label)
    }

    @Test
    fun bothSidesClearingTheSameDuplicatesAtOnceAgree() {
        val w = World()
        val id = w.create(Side.DESK, "Plan\nbody")
        w.connect()
        val base = w.desk.notes.getValue(id)
        for (m in 81L..86L) copyOf(base, "Plan", "Plan\nbody", m).let { w.desk.notes[it.id] = it; w.phone.notes[it.id] = it }
        val unique = copyOf(base, "Plan", "Plan\nbody v2", 87)
        w.desk.notes[unique.id] = unique
        w.phone.notes[unique.id] = unique
        w.disconnect()
        // Each side tidies on its own, a moment apart.
        assertEquals(6, w.desk.removeDuplicates(false, 100_000).size)
        assertEquals(6, w.phone.removeDuplicates(false, 100_050).size)
        w.connect()
        for (side in Side.entries) {
            assertEquals("$side", 2, w.live(side).size)
            assertTrue(w.live(side).any { it.id == unique.id })
        }
        w.assertSameNotes()
        w.disconnect()
        w.connect()
        w.assertSameNotes()
        assertEquals(2, w.live(Side.DESK).size)
    }

    @Test
    fun findingDuplicatesInABigShelfIsQuick() {
        val shelf = Shelf()
        for (n in 0 until 200) {
            val base = Note("note$n", body = "note$n " + "word ".repeat(400), created = 1, modified = 10)
            shelf.notes[base.id] = base
            if (n < 150) for (m in listOf(1000L + n, 2000L + n)) copyOf(base, base.label, base.body, m).let { shelf.notes[it.id] = it }
        }
        assertEquals(500, shelf.notes.size)
        val start = System.nanoTime()
        val doomed = shelf.duplicateCopies(true)
        val tidied = shelf.removeDuplicates(false, 5000)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(300, doomed.size)
        assertEquals(300, tidied.size)
        assertTrue("took $ms ms", ms < 900)
    }
}
