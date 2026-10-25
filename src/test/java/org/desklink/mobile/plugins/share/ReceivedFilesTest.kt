/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/** The single copy that puts a verified file where the person finds it. */
@RunWith(RobolectricTestRunner::class)
class ReceivedFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val destination get() = DeviceStorageDestination(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun aFileIsCopiedWholeKeepsItsTimeAndANameInUseGetsANumber() {
        val source = tmp.newFile("part").apply { writeBytes(ByteArray(3 * 1024 * 1024 + 17) { (it * 7).toByte() }) }
        val dir = tmp.newFolder("Download")
        val first = destination.viaFile(dir, source, "report.pdf", 1_700_000_000_000, "application/pdf")
        val second = destination.viaFile(dir, source, "report.pdf", null, "application/pdf")
        assertEquals("report.pdf", first.name)
        assertEquals("report (2).pdf", second.name)
        assertArrayEquals(source.readBytes(), File(dir, "report.pdf").readBytes())
        assertEquals(1_700_000_000_000, File(dir, "report.pdf").lastModified())
        assertEquals(source.length(), first.size)
    }

    @Test
    fun aFolderThatCannotBeUsedIsAnErrorAndLeavesNothingBehind() {
        val source = tmp.newFile("part2").apply { writeBytes(ByteArray(100)) }
        val notAFolder = tmp.newFile("occupied")
        assertThrows(IOException::class.java) { destination.viaFile(File(notAFolder, "inside"), source, "a.txt", null, "text/plain") }
        assertFalse(File(notAFolder, "inside").exists())
    }

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun unpublished() = File(app.filesDir, "webrtc-unpublished").apply { mkdirs() }

    @Test
    fun aKeptFileWhoseRenameFailsLeavesNoRecordBehind() {
        unpublished().listFiles().orEmpty().forEach { it.delete() }
        val missing = File(tmp.root, "no-such-part")
        assertEquals(null, UnpublishedFiles.keep(app, missing, "a.bin", null, null, "desk"))
        assertEquals("no record without a file", emptyList<String>(), unpublished().list().orEmpty().toList())
    }

    @Test
    fun pruneDeletesStrayPartsAndRecordsOlderThanADayButNotFreshOnes() {
        val dir = unpublished().also { d -> d.listFiles().orEmpty().forEach { it.delete() } }
        val now = System.currentTimeMillis()
        val strayPart = File(dir, "aaaa.part").apply { writeBytes(ByteArray(10)); setLastModified(now - 2 * 24 * 3600 * 1000L) }
        val strayJson = File(dir, "bbbb.json").apply { writeText("{}"); setLastModified(now - 2 * 24 * 3600 * 1000L) }
        val freshPart = File(dir, "cccc.part").apply { writeBytes(ByteArray(10)) } // a keep in progress
        val kept = UnpublishedFiles.keep(app, tmp.newFile("p").apply { writeBytes(ByteArray(5)) }, "k.bin", null, null, "desk")!!
        UnpublishedFiles.prune(app, now)
        assertFalse(strayPart.exists())
        assertFalse(strayJson.exists())
        assertEquals(true, freshPart.exists())
        assertEquals("a complete record is kept for a week", listOf(kept.id), UnpublishedFiles.list(app).map { it.id })
        UnpublishedFiles.prune(app, now + 8 * 24 * 3600 * 1000L)
        assertEquals(emptyList<String>(), UnpublishedFiles.list(app).map { it.id })
    }

    @Test
    fun aChosenFolderThatIsGoneIsNotUsableSoTheScreenAndPublishSayDownloads() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().putBoolean("share_destination_custom", false).commit()
        assertFalse("nothing chosen: nothing to fall back from", ShareDestination.customFolderUsable(app))
        prefs.edit().putBoolean("share_destination_custom", true)
            .putString("share_destination_folder_uri", "content://com.android.externalstorage.documents/tree/gone%3AFolder").commit()
        assertFalse("chosen but not there: the destination is Downloads, and it says so", ShareDestination.customFolderUsable(app))
        assertTrue("and the room is that of the phone's own storage (a real number, not unlimited)", destination.freeBytes() < Long.MAX_VALUE)
        prefs.edit().clear().commit()
    }

    @Test
    fun namesAreUniqueWithTheLastExtensionKept() {
        val dir = tmp.newFolder("names")
        File(dir, "a.tar.gz").writeText("x")
        File(dir, "a.tar (2).gz").writeText("x")
        assertEquals("a.tar (3).gz", DeviceStorageDestination.uniqueIn(dir, "a.tar.gz"))
        assertEquals("fresh.txt", DeviceStorageDestination.uniqueIn(dir, "fresh.txt"))
        assertEquals("bad_name_file", DeviceStorageDestination.uniqueIn(dir, "..."))
    }
}
