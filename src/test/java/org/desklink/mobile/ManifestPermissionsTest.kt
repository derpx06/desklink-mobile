/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What the shipped (release) manifest asks for: no log reading and no overlay, which only hand-installed debug builds declare. */
class ManifestPermissionsTest {
    private fun manifest(path: String) = listOf(File(path), File("desklink-mobile/$path")).first { it.exists() }.readText()

    @Test
    fun theMainManifestDoesNotAskForLogsOrOverlays() {
        val main = manifest("src/main/AndroidManifest.xml")
        for (name in listOf("READ_LOGS", "SYSTEM_ALERT_WINDOW")) assertFalse("$name in the release manifest", name in main)
    }

    @Test
    fun theDebugManifestKeepsThemForTheAdvancedWorkaround() {
        val debug = manifest("src/debug/AndroidManifest.xml")
        assertTrue("READ_LOGS" in debug && "SYSTEM_ALERT_WINDOW" in debug)
    }

    @Test
    fun noPermissionNothingUsesAndStorageOnlyWhereItIsStillNeeded() {
        val main = manifest("src/main/AndroidManifest.xml")
        assertFalse("DOWNLOAD_WITHOUT_NOTIFICATION" in main)
        assertTrue("android.permission.WRITE_EXTERNAL_STORAGE\" android:maxSdkVersion=\"29\"" in main)
        assertTrue("android.permission.READ_EXTERNAL_STORAGE\" android:maxSdkVersion=\"29\"" in main)
        assertFalse("BLUETOOTH" in main)
    }

    /** The data-safety doc lists what was removed: none of it may be back in the manifest (it was, once: a full-screen intent). */
    @Test
    fun nothingThePlayDocSaysWasRemovedIsInTheManifest() {
        val doc = listOf(File("docs/PLAY_DATA_SAFETY.md"), File("desklink-mobile/docs/PLAY_DATA_SAFETY.md")).first { it.exists() }.readText()
        val removed = doc.substringAfter("- Removed:").substringBefore("\n\n")
        val names = Regex("`([A-Z_]{6,})`").findAll(removed).map { it.groupValues[1] }.toList()
        assertTrue("the Removed list names permissions: $names", "USE_FULL_SCREEN_INTENT" in names && names.size >= 3)
        val main = manifest("src/main/AndroidManifest.xml")
        for (name in names) assertFalse("$name is listed as removed in PLAY_DATA_SAFETY.md but is in the manifest", "android.permission.$name" in main)
    }

    @Test
    fun doNotDisturbAccessCanBeGrantedBecauseTheMarkerPermissionIsDeclared() {
        assertTrue("android.permission.ACCESS_NOTIFICATION_POLICY" in manifest("src/main/AndroidManifest.xml"))
    }
}
