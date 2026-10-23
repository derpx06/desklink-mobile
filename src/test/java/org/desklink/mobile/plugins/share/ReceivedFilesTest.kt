/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
        assertEquals("report (1).pdf", second.name)
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

    @Test
    fun namesAreUniqueWithTheLastExtensionKept() {
        val dir = tmp.newFolder("names")
        File(dir, "a.tar.gz").writeText("x")
        File(dir, "a.tar (1).gz").writeText("x")
        assertEquals("a.tar (2).gz", DeviceStorageDestination.uniqueIn(dir, "a.tar.gz"))
        assertEquals("fresh.txt", DeviceStorageDestination.uniqueIn(dir, "fresh.txt"))
        assertEquals("bad_name_file", DeviceStorageDestination.uniqueIn(dir, "..."))
    }
}
