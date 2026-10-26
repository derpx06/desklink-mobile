/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class AppLogTest {
    private val dir = Files.createTempDirectory("applog").toFile()

    @Before fun setUp() = AppLog.useDir(dir)

    @After fun tearDown() { AppLog.useDir(null); dir.deleteRecursively() }

    @Test
    fun theExportOfAReleaseBuildHasTheEventsEvenThoughEveryLogLineIsStripped() {
        AppLog.event("link", "feature-ready ${AppLog.id("abcdef0123456789")} via 192.168.1.9:1716")
        AppLog.event("transfer", "send completed")
        val out = ByteArrayOutputStream()
        LogExport.export("DeskLink 1.3.0", logcat = emptySequence(), output = out) // release: no logcat lines at all
        val text = out.toString(Charsets.UTF_8)
        assertTrue(text.contains("feature-ready abcdef01"))
        assertTrue(text.contains("transfer send completed"))
        assertFalse("addresses are redacted", text.contains("192.168.1.9"))
        assertTrue(text.startsWith("DeskLink 1.3.0"))
    }

    @Test
    fun theLogIsBoundedAndKeepsTheNewestEvents() {
        repeat(20_000) { AppLog.event("fill", "event number $it with some padding to take room") }
        val size = dir.listFiles().orEmpty().sumOf { it.length() }
        assertTrue("within the budget: $size", size <= AppLog.MAX_BYTES)
        assertTrue(AppLog.lines().last().contains("event number 19999"))
    }
}
