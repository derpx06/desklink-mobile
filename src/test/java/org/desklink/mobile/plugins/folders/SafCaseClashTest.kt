/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

import android.app.Application
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A document provider that ignores capitals when it names files, like the phone's own storage: a second file whose name
 * differs from an existing one only in capitals is quietly called "name (1)". The Bucket must never end up with that file.
 */
@RunWith(RobolectricTestRunner::class)
class SafCaseClashTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A content provider that speaks the documents protocol (the framework's DocumentsProvider cannot run under Robolectric). */
    class Fake : android.content.ContentProvider() {
        override fun onCreate() = true
        private fun file(id: String) = if (id == "root") root else File(root, id)
        private val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
        private fun row(c: MatrixCursor, id: String) {
            val f = file(id)
            c.newRow().add(Document.COLUMN_DOCUMENT_ID, id).add(Document.COLUMN_DISPLAY_NAME, f.name)
                .add(Document.COLUMN_MIME_TYPE, if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
                .add(Document.COLUMN_SIZE, f.length()).add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
                .add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_DIR_SUPPORTS_CREATE)
        }
        override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor {
            val c = MatrixCursor(projection ?: cols)
            if (uri.pathSegments.contains("children")) {
                val parent = DocumentsContract.getDocumentId(uri)
                file(parent).listFiles().orEmpty().forEach { row(c, if (parent == "root") it.name else "$parent/${it.name}") }
            } else {
                row(c, DocumentsContract.getDocumentId(uri))
            }
            return c
        }
        /** The name this "file system" would give [wanted] in [dir]: unique ignoring case. */
        private fun free(dir: File, wanted: String): String {
            var name = wanted
            var n = 1
            while (dir.listFiles().orEmpty().any { it.name.equals(name, ignoreCase = true) }) {
                val dot = wanted.lastIndexOf('.').takeIf { it > 0 } ?: wanted.length
                name = wanted.substring(0, dot) + " (${n++})" + wanted.substring(dot)
            }
            return name
        }
        private fun docUri(id: String) = DocumentsContract.buildDocumentUri("org.desklink.test.saf", id)
        override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
            val uri = extras!!.getParcelable<Uri>("uri")!!
            val id = DocumentsContract.getDocumentId(uri)
            val out = android.os.Bundle()
            when (method) {
                "android:createDocument" -> {
                    val dir = file(id)
                    val name = free(dir, extras.getString(Document.COLUMN_DISPLAY_NAME)!!)
                    val f = File(dir, name)
                    if (extras.getString("mime_type") == Document.MIME_TYPE_DIR) f.mkdirs() else f.createNewFile()
                    out.putParcelable("uri", docUri(if (id == "root") name else "$id/$name"))
                }
                "android:renameDocument" -> {
                    val f = file(id)
                    val name = free(f.parentFile!!, extras.getString(Document.COLUMN_DISPLAY_NAME)!!)
                    f.renameTo(File(f.parentFile, name))
                    out.putParcelable("uri", docUri((id.substringBeforeLast('/', "") + "/" + name).trimStart('/')))
                }
                "android:deleteDocument" -> file(id).deleteRecursively()
            }
            return out
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file(DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.parseMode(mode))
        override fun getType(uri: Uri) = null
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
        override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, args: Array<String>?) = 0
        companion object { lateinit var root: File }
    }

    private lateinit var tree: SafTree

    @Before
    fun setUp() {
        Fake.root = tmp.newFolder("saf")
        Robolectric.setupContentProvider(Fake::class.java, "org.desklink.test.saf")
        val app = ApplicationProvider.getApplicationContext<Application>()
        tree = SafTree(app.contentResolver, DocumentsContract.buildTreeDocumentUri("org.desklink.test.saf", "root"))
    }

    @Test
    fun aFileDifferingOnlyInCapitalsIsNeverCreatedUnderAnotherName() {
        assertTrue(tree.place("A.txt", "first".byteInputStream(), null) != null)
        val placed = tree.place("a.txt", "second".byteInputStream(), null)
        assertNull("not synced", placed)
        assertEquals(setOf("a.txt"), tree.caseClashes)
        assertEquals("nothing else appeared: no 'a (1).txt' to upload back", listOf("A.txt"), tree.names(tree.find("")!!).sorted())
    }

    @Test
    fun aProviderThatRenamesOnItsOwnIsCaughtAfterTheFactAndTheStrayDeleted() {
        // The listing does not show the clash (a stale view), the provider renames anyway: the result is checked.
        assertTrue(tree.place("B.txt", "first".byteInputStream(), null) != null)
        tree.checkBeforeCreating = false // as if the listing had not shown it
        assertNull(tree.place("b.txt", "second".byteInputStream(), null))
        assertEquals(setOf("b.txt"), tree.caseClashes)
        assertFalse(File(Fake.root, "b (1).txt").exists())
        assertTrue(File(Fake.root, "B.txt").exists())
    }

    @Test
    fun theSameNameStillReplacesAndAPlainNewNamePlaces() {
        val first = tree.place("c.txt", "one".byteInputStream(), null)!!
        assertEquals(3L, first.size)
        val again = tree.place("c.txt", "three".byteInputStream(), first)!!
        assertEquals(5L, again.size)
        assertTrue(tree.caseClashes.isEmpty())
        assertEquals(listOf("c.txt"), tree.names(tree.find("")!!).sorted())
    }
}
