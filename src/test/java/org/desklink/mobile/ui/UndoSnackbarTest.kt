/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.desklink.mobile.plugins.notes.NotesModel
import org.desklink.mobile.ui.compose.components.showUndo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two deletions in a row: the Undo on screen is the newest one's, and the older one is gone, never queued behind it. */
class UndoSnackbarTest {
    @Test fun theSecondDeletionReplacesTheFirstsUndo() = runBlocking {
        val host = SnackbarHostState()
        val restored = mutableListOf<String>()
        val a = async(start = CoroutineStart.UNDISPATCHED) { host.showUndo("Note deleted: A", "Undo") { restored += "A" } }
        assertEquals("Note deleted: A", host.currentSnackbarData?.visuals?.message)
        val b = async(start = CoroutineStart.UNDISPATCHED) { host.showUndo("Note deleted: B", "Undo") { restored += "B" } }
        withTimeout(5_000) { a.await() }
        assertEquals("only B's is showing", "Note deleted: B", host.currentSnackbarData?.visuals?.message)
        host.currentSnackbarData?.performAction()
        withTimeout(5_000) { b.await() }
        assertEquals("tapping Undo restores B, and A is not restored", listOf("B"), restored)
    }

    @Test fun aSingleDeletionStillUndoes() = runBlocking {
        val host = SnackbarHostState()
        var done = false
        val a = async(start = CoroutineStart.UNDISPATCHED) { host.showUndo("Snippet deleted: x", "Undo") { done = true } }
        host.currentSnackbarData?.performAction()
        withTimeout(5_000) { a.await() }
        assertTrue(done)
    }

    @Test fun theMessageNamesWhatWasDeleted() {
        assertEquals("Shopping", NotesModel.undoName("Shopping", "milk"))
        assertEquals("milk and eggs", NotesModel.undoName("  ", "\n milk and eggs\nmore"))
        assertEquals("a".repeat(27) + "…", NotesModel.undoName("a".repeat(40), ""))
        assertEquals("", NotesModel.undoName("", ""))
    }
}
