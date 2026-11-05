/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesModelTest {
    private fun note(id: String, body: String, title: String = id) = Note(id = id, title = title, body = body, created = 1, modified = 1)

    private val list = "Plan\n- [x] Ship\n- [ ] Tag\n* [X] Notes\nplain"

    @Test fun checklistReadsBoxesWithTheirLines() {
        assertEquals(
            listOf(CheckLine(1, "Ship", true), CheckLine(2, "Tag", false), CheckLine(3, "Notes", true)),
            NotesModel.checklist(list),
        )
    }

    @Test fun tickingOneLineChangesOnlyThatLine() {
        assertEquals("Plan\n- [x] Ship\n- [x] Tag\n* [X] Notes\nplain", NotesModel.setChecked(list, 2, true))
        assertEquals("Plan\n- [ ] Ship\n- [ ] Tag\n* [X] Notes\nplain", NotesModel.setChecked(list, 1, false))
        assertEquals("a line that is not a box is left alone", list, NotesModel.setChecked(list, 4, true))
        assertEquals("out of range is left alone", list, NotesModel.setChecked(list, 40, true))
    }

    @Test fun twoBoxesMakeAChecklistOneDoesNot() {
        assertTrue(NotesModel.isChecklist(note("a", list)))
        assertFalse(NotesModel.isChecklist(note("b", "- [ ] only one\nprose")))
    }

    @Test fun searchMatchesTitleBodyAndSnippetText() {
        assertTrue(NotesModel.matches(note("a", "hello World"), "world"))
        assertTrue(NotesModel.matches(note("a", "x"), ""))
        assertFalse(NotesModel.matches(note("a", "x"), "zzz"))
        assertTrue(NotesModel.matches(Snippet("s", "git push origin main", modified = 1), "PUSH"))
    }

    @Test fun checklistsAreFullWidthAndOthersPairUp() {
        val notes = listOf(note("p1", "x"), note("p2", "y"), note("c", list), note("p3", "z"))
        val blocks = NotesModel.blocks(notes, 2)
        assertEquals(3, blocks.size)
        assertEquals(listOf("p1", "p2"), (blocks[0] as NotesModel.Block.Row).notes.map { it.id })
        assertEquals("c", (blocks[1] as NotesModel.Block.Checklist).note.id)
        assertEquals(listOf("p3"), (blocks[2] as NotesModel.Block.Row).notes.map { it.id })
        assertEquals("one column at a large font", 4, NotesModel.blocks(notes, 1).size)
    }

    @Test fun anEmptyBoxIsNotListed() {
        val body = "- [ ] \n- [x] Real\n- [ ]\n- [ ] Also"
        assertEquals(listOf(CheckLine(1, "Real", true), CheckLine(3 + 0, "Also", false)).map { it.label }, NotesModel.checklist(body).map { it.label })
        assertEquals(3, NotesModel.checklist(body).last().line)
    }
}
