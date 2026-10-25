/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** W7 "notes ancestors" and the other note data fixes (audit data S2). */
@RunWith(RobolectricTestRunner::class)
class NotesAncestorsTest {
    private fun edit(shelf: Shelf, id: String, body: String, now: Long): Note {
        val before = shelf.notes.getValue(id)
        return Shelf.edited(before, before.copy(body = body), shelf.synced[id], now).also { shelf.notes[id] = it }
    }

    /** What travels: the JSON of the note, read back by the other side. */
    private fun wire(note: Note) = Shelf.noteFrom(Shelf.toJson(note))!!

    @Test
    fun twoEditsInARowReachThePeerWithoutAConflictedCopy() {
        val mine = Shelf()
        val theirs = Shelf()
        mine.notes["n"] = Note("n", body = "", created = 1, modified = 10)
        theirs.mergeNote(wire(mine.notes.getValue("n"))) // the note was created and pushed
        // Three edits in a row, none acknowledged (nothing tells `mine` that `theirs` has them).
        for ((i, text) in listOf("one", "two", "three").withIndex()) {
            val sent = wire(edit(mine, "n", text, 100L + i * 100))
            assertEquals("edit ${i + 1} continues what the peer holds", Merged.TAKEN, theirs.mergeNote(sent))
        }
        assertEquals(listOf("n"), theirs.notes.keys.toList())
        assertEquals("three", theirs.notes.getValue("n").body)
    }

    @Test
    fun theFirstEditOfAFreshNoteIsNotARivalOfItsEmptyOrigin() {
        val mine = Shelf()
        val theirs = Shelf()
        mine.notes["n"] = Note("n", created = 1, modified = 10)
        theirs.mergeNote(wire(mine.notes.getValue("n")))
        assertEquals(Merged.TAKEN, theirs.mergeNote(wire(edit(mine, "n", "first words", 50))))
        assertEquals(1, theirs.notes.size)
    }

    @Test
    fun anOlderVersionArrivingLateIsKeptOutNotACopy() {
        val mine = Shelf()
        mine.notes["n"] = Note("n", body = "v1", created = 1, modified = 10)
        val theirs = Shelf()
        theirs.mergeNote(wire(mine.notes.getValue("n")))
        val v2 = wire(edit(mine, "n", "v2", 20))
        val v3 = wire(edit(mine, "n", "v3", 30))
        assertEquals(Merged.TAKEN, theirs.mergeNote(v3)) // v3 first (v2 reordered behind it)
        assertEquals("the late v2 is an ancestor of v3", Merged.KEPT_OURS, theirs.mergeNote(v2))
        assertEquals(1, theirs.notes.size)
    }

    @Test
    fun ancestorsAreBoundedAndOptionalOnTheWire() {
        var note = Note("n", created = 1, modified = 1)
        repeat(20) { i -> note = Shelf.edited(note, note.copy(body = "b$i"), null, 100L + i) }
        assertEquals(Shelf.MAX_ANCESTORS, note.ancestors.size)
        assertTrue(Shelf.toJson(Note("n", created = 1, modified = 1)).has("ancestors").not())
        assertEquals("an old peer's note has none", emptyList<Long>(), Shelf.noteFrom(Shelf.toJson(Note("n", created = 1, modified = 5)))!!.ancestors)
    }

    @Test
    fun aSharedTextIsCutToWhatSyncsAndSaidSo() {
        val big = "x".repeat(25 * 1024)
        val (cut, wasCut) = NotesStore.fit(big)
        assertTrue(wasCut)
        assertTrue("always syncable: ${cut.toByteArray().size}", Shelf.syncable(cut))
        assertTrue(cut.toByteArray().size <= Shelf.MAX_NOTE_BYTES)
        val (same, notCut) = NotesStore.fit("short")
        assertEquals("short", same)
        assertFalse(notCut)
        // Escapes count too: quotes become two bytes in JSON.
        val quotes = "\"".repeat(15 * 1024)
        assertTrue(Shelf.syncable(NotesStore.fit(quotes).first))
        // A character is never cut in half.
        val emoji = "😀".repeat(8000)
        val fitted = NotesStore.fit(emoji).first
        assertTrue(Shelf.syncable(fitted) && fitted.length % 2 == 0)
    }

    @Test
    fun snippetsAreStoredUpToTheSameCapAsTheDesk() {
        assertEquals(1000, Shelf.MAX_STORED_SNIPPETS)
        assertEquals(500, Shelf.MAX_SNIPPETS)
    }
}
