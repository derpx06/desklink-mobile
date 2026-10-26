/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteDraftsTest {
    @Test
    fun anOverCapBodySurvivesARotationAndIsGoneOnceHandled() {
        val key = NoteDrafts.key("note-1")
        val big = "x".repeat(Shelf.MAX_NOTE_BYTES + 10)
        assertEquals("and so the editor would not save it", false, Shelf.syncable(big))
        NoteDrafts.put(key, NoteDrafts.Draft("T", big, true, base = 7))
        // The editor is recreated: it asks again and gets the same text.
        assertEquals(NoteDrafts.Draft("T", big, true, base = 7), NoteDrafts.get(key))
        NoteDrafts.clear(key)
        assertNull(NoteDrafts.get(key))
        assertEquals("a note not yet saved has its own key", "new", NoteDrafts.key(null))
    }

    @Test
    fun aDraftMadeFromAnOlderVersionIsDroppedWhenTheNoteChangedMeanwhile() {
        val key = NoteDrafts.key("note-2")
        NoteDrafts.put(key, NoteDrafts.Draft("T", "old text", false, base = 100))
        assertNull("the desk edited it (modified 200)", NoteDrafts.valid(key, storedModified = 200))
        assertNull("and it is gone, not offered again", NoteDrafts.get(key))
    }
}
