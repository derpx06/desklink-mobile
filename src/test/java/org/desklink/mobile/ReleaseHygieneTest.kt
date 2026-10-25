/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What a release build must and must not do, read from the project files that decide it. */
class ReleaseHygieneTest {
    private fun project(path: String) = listOf(File(path), File("desklink-mobile/$path")).first { it.exists() }

    @Test
    fun releaseBuildsStripEveryLogLineButErrors() {
        val rules = project("proguard-rules.pro").readText()
        for (level in listOf("d", "v", "i", "w")) assertTrue("Log.$level is stripped", "public static int $level(...);" in rules)
        assertFalse("errors stay", "public static int e(...);" in rules)
    }

    @Test
    fun theWifiLockIsPartOfActiveWork() {
        assertTrue(BackgroundService.isActiveWork(false, false, false, false, gliding = true))
        assertFalse("merely being connected is not work", BackgroundService.isActiveWork(false, false, false, false))
        assertEquals(true, BackgroundService.isActiveWork(true, false, false, false))
    }
}
