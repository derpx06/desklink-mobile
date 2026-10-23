/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** A Keystore that cannot answer right now must leave the sealed notes exactly where they are. */
@RunWith(RobolectricTestRunner::class)
class NotesStoreUnavailableTest {
    @Test
    fun aKeystoreThatCannotAnswerLeavesTheFileAloneAndTheNextCallTriesAgain() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // The application's own start-up preload may have cached an empty shelf already: start from "not loaded".
        NotesStore::class.java.getDeclaredField("shelf").apply { isAccessible = true }.set(NotesStore, null)
        val sealed = File(context.filesDir, "notes/shelf.sealed").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(64) { it.toByte() }) }
        // Robolectric has no AndroidKeyStore: opening fails with a KeyStoreException, which is a "not yet", not "lost".
        val notes = NotesStore.notes(context)
        assertEquals("reads see an empty shelf meanwhile", emptyList<Any>(), notes)
        assertTrue(NotesStore.unavailable.value)
        assertTrue("the sealed file is still there", sealed.exists())
        assertEquals("nothing was set aside", emptyList<String>(), sealed.parentFile!!.listFiles().orEmpty().map { it.name }.filter { it.contains("unreadable") })
        // An edit made meanwhile is dropped, never written over the sealed file.
        NotesStore.newNote(context, "x", "y")
        assertEquals(64L, sealed.length())
        // And it is asked again next time (the state is not cached).
        NotesStore.notes(context)
        assertTrue(NotesStore.unavailable.value)
        assertFalse(File(context.filesDir, "notes/shelf.sealed.unreadable").exists())
    }
}
