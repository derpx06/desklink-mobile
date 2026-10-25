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
}
