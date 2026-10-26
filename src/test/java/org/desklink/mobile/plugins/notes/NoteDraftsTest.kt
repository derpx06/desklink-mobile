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
        NoteDrafts.put(key, NoteDrafts.Draft("T", big, true))
        // The editor is recreated: it asks again and gets the same text.
        assertEquals(NoteDrafts.Draft("T", big, true), NoteDrafts.get(key))
        NoteDrafts.clear(key)
        assertNull(NoteDrafts.get(key))
        assertEquals("a note not yet saved has its own key", "new", NoteDrafts.key(null))
    }
}
