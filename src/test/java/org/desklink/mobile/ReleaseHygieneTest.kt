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
    fun theWifiLockIsPartOfActiveWork() {
        assertTrue(BackgroundService.isActiveWork(false, false, false, false, gliding = true))
        assertFalse("merely being connected is not work", BackgroundService.isActiveWork(false, false, false, false))
        assertEquals(true, BackgroundService.isActiveWork(true, false, false, false))
    }

    @Test
    fun withTheScreenOffOnlyAMovingFileHoldsTheWakeLock() {
        assertFalse("a terminal left attached", BackgroundService.isActiveWork(true, false, false, false, screenOn = false))
        assertFalse("the desk's screen", BackgroundService.isActiveWork(false, true, false, false, screenOn = false))
        assertFalse("this screen shown", BackgroundService.isActiveWork(false, false, true, false, screenOn = false))
        assertFalse("glide", BackgroundService.isActiveWork(false, false, false, false, gliding = true, screenOn = false))
        assertTrue("a file moving", BackgroundService.isActiveWork(false, false, false, true, screenOn = false))
    }

    @Test
    fun theVersionIsOneThingEverywhereItIsWritten() {
        val gradle = project("build.gradle.kts").readText()
        val name = Regex("versionName = \"([^\"]+)\"").find(gradle)!!.groupValues[1]
        assertEquals("the first public beta", "0.3.0", name)
        val code = Regex("versionCode = (\\d+)").find(gradle)!!.groupValues[1]
        assertTrue("never lower than the first build (20000) or the first beta (20300)", code.toInt() >= 20300)
        val changelog = project("fastlane/metadata/android/en-US/changelogs/$code.txt").readText()
        assertTrue("changelog $code.txt names the version", changelog.startsWith("DeskLink " + name.substringBeforeLast('.')))
        assertTrue("Play 'What's new' is 500 characters at most (${changelog.length})", changelog.length <= 500)
        assertTrue("Play short description is 80 characters at most", project("fastlane/metadata/android/en-US/short_description.txt").readText().trim().length <= 80)
        val top = project("CHANGELOG.md").readLines().first { it.startsWith("## ") }
        assertTrue("CHANGELOG.md top heading is $name: $top", top.startsWith("## $name"))
    }

    @Test
    fun theStoreAndPrivacyTextsDoNotPromiseRemovedFeatures() {
        for (path in listOf("PRIVACY.md", "docs/PLAY_DATA_SAFETY.md", "fastlane/metadata/android/en-US/full_description.txt", "README.md")) {
            val text = project(path).readText()
            assertFalse("$path mentions browsing the phone's storage", Regex("browse (the )?phone's (files|storage)", RegexOption.IGNORE_CASE).containsMatchIn(text.replace("does not let the computer browse its storage", "")))
        }
        assertFalse("the manifest permission is not claimed", "MANAGE_EXTERNAL_STORAGE" in project("PRIVACY.md").readText())
    }

    @Test
    fun lintFailsOnNewErrorsAgainstABaseline() {
        val gradle = project("build.gradle.kts").readText()
        assertTrue("abortOnError = true" in gradle)
        assertTrue("lint-baseline.xml" in gradle)
        assertTrue(project("lint-baseline.xml").length() > 0)
    }

    @Test
    fun theWelcomeScreenSaysWhereToGetTheComputersApp() {
        assertTrue("https://github.com/derpx06/DeskLink/releases" == org.desklink.mobile.ui.compose.screen.pairing.DESKTOP_APP_URL)
    }

    @Test
    fun aSendWaitingForTheComputerHoldsNoWakeLockAndAStalledOneLetsGo() {
        assertTrue(BackgroundService.bytesMoving(finished = false, resuming = false, updatedAt = 1_000, now = 60_000))
        assertFalse("resuming: waiting for the laptop to wake", BackgroundService.bytesMoving(false, true, 1_000, 2_000))
        assertFalse("finished", BackgroundService.bytesMoving(true, false, 1_000, 2_000))
        assertFalse("no progress for the whole lease", BackgroundService.bytesMoving(false, false, 0, BackgroundService.PROGRESS_LEASE_MS + 1))
    }

    @Test
    fun granting_notification_buttons_are_held_back_on_the_lock_screen() {
        assertTrue(org.desklink.mobile.helpers.LockedActions.refuse(locked = true, grants = true))
        assertFalse(org.desklink.mobile.helpers.LockedActions.refuse(locked = false, grants = true))
        assertFalse("declining stays possible", org.desklink.mobile.helpers.LockedActions.refuse(locked = true, grants = false))
    }

    @Test
    fun releaseBuildsLogNothingAndNothingPrintsAStackTraceAround() {
        val rules = java.io.File("proguard-rules.pro").readText()
        listOf("d", "v", "i", "w", "e").forEach { assertTrue("Log.$it is stripped in release", rules.contains("public static int $it(...);")) }
        val offenders = java.io.File("src/main/java").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { it.readText().contains("printStackTrace(") }.map { it.name }.toList()
        assertEquals("printStackTrace bypasses the stripping", emptyList<String>(), offenders)
    }

    /** versionCode is independent of versionName and only ever goes up (the first build was 20000, the first beta 20300). */
    @Test
    fun versionCodeIsIndependentOfTheNameAndNeverGoesDown() {
        val gradle = project("build.gradle.kts").readText()
        val code = Regex("""versionCode = (\d+)""").find(gradle)!!.groupValues[1].toInt()
        assertTrue("versionCode $code is below the first beta's 20300", code >= 20300)
        assertTrue("the documented rule", "independent of versionName" in project("docs/RELEASE.md").readText())
    }
}
