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

    @Test
    fun theVersionIs130AndHasItsStoreChangelog() {
        val gradle = project("build.gradle.kts").readText()
        assertTrue("versionName = \"1.3.0\"" in gradle)
        val code = Regex("versionCode = (\\d+)").find(gradle)!!.groupValues[1]
        assertTrue("monotonic after the first 20000", code.toInt() > 20000)
        assertTrue("changelog $code.txt", project("fastlane/metadata/android/en-US/changelogs/$code.txt").readText().startsWith("DeskLink 1.3"))
        assertTrue("CHANGELOG.md has 1.3.0", "## 1.3.0" in project("CHANGELOG.md").readText())
    }

    @Test
    fun theStoreAndPrivacyTextsDoNotPromiseRemovedFeatures() {
        for (path in listOf("PRIVACY.md", "docs/PLAY_DATA_SAFETY.md", "fastlane/metadata/android/en-US/full_description.txt", "README.md")) {
            val text = project(path).readText()
            assertFalse("$path mentions browsing the phone's storage", Regex("browse (the )?phone's (files|storage)", RegexOption.IGNORE_CASE).containsMatchIn(text.replace("does not let the computer browse its storage", "")))
        }
        assertFalse("the manifest permission is not claimed", "MANAGE_EXTERNAL_STORAGE" in project("PRIVACY.md").readText())
    }
}
