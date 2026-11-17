/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateMiscTest {
    @Test fun installSourceByInstaller() {
        assertEquals(InstallSource.GOOGLE_PLAY, InstallSource.classify("com.android.vending"))
        for (f in listOf("org.fdroid.fdroid", "org.fdroid.basic", "com.machiav3lli.fdroid", "eu.bubu1.fdroidclassic")) assertEquals(InstallSource.FDROID, InstallSource.classify(f))
        for (g in listOf(null, "com.google.android.packageinstaller", "com.android.packageinstaller")) assertEquals(InstallSource.GITHUB, InstallSource.classify(g))
        assertEquals(InstallSource.OTHER_STORE, InstallSource.classify("com.sec.android.app.samsungapps"))
    }

    @Test fun skewIsMajorMinorOnlyAndNeverAnError() {
        assertEquals(UpdateSkew.PeerOlder("0.2.1"), UpdateSkew.of("0.3.0", "0.2.1"))
        assertEquals(UpdateSkew.PeerNewer("0.4.0"), UpdateSkew.of("0.3.0-debug", "0.4.0"))
        assertEquals(UpdateSkew.PeerOlder("0.9.0"), UpdateSkew.of("1.0.0", "0.9.0"))
        assertEquals(UpdateSkew.None, UpdateSkew.of("0.3.0", "0.3.9")) // a patch is nothing
        for (bad in listOf(null, "", "banana", "1")) assertEquals(UpdateSkew.None, UpdateSkew.of("0.3.0", bad))
        assertEquals(UpdateSkew.None, UpdateSkew.of(null, "0.3.0"))
        
        assertEquals(16, (UpdateSkew.of("0.3.0", "2147483647.2147483647.2147483647") as UpdateSkew.PeerNewer).peer.length)
    }

    @Test fun aReleaseConfigurationNeverTrustsTheTestKeys() {
        val release = UpdateKeys.forBuild(debug = false)
        assertTrue(release.keys.none { it.startsWith("test") })
        val testKeys = DebugKeys.KEYS.values.map { UpdateVerifier.base64Url(it)!!.toList() }
        assertTrue(release.values.none { it.toList() in testKeys })
        assertEquals(setOf("test1", "test2"), UpdateKeys.forBuild(debug = true).keys.filter { it.startsWith("test") }.toSet())
        // The compiled real slots, when filled, are 32-byte keys.
        for (k in listOf(UpdateKeys.K1, UpdateKeys.K2)) assertTrue(k.isEmpty() || UpdateVerifier.base64Url(k)?.size == 32)
    }

    @Test fun theUpdateModuleIsALeaf() {
        val banned = Regex("""import org\.desklink\.mobile\.(Device|DeviceInfo|PairingHandler|QrPairing|webrtc|plugins|backends|session|transport|signaling|protocol)\b""")
        File("src/main/java/org/desklink/mobile/update").listFiles()!!.filter { it.extension == "kt" }.forEach {
            assertFalse("${it.name} reaches into the product", banned.containsMatchIn(it.readText()))
            assertFalse("${it.name} reads an identifier", Regex("""deviceId|certificate|hostname|Build\.(SERIAL|MODEL)|ANDROID_ID""").containsMatchIn(it.readText().lineSequence().filter { l -> !l.trimStart().startsWith("*") && !l.trimStart().startsWith("//") && !l.trimStart().startsWith("/*") }.joinToString("\n")))
        }
    }

    @Test fun nothingOutsideTheModuleCallsIntoItExceptTheNamedHooks() {
        // Full relative paths, so a same-named file elsewhere does not pass. Add an entry when a phase-2 hook lands.
        val allowed = setOf("BackgroundService.kt", "ui/MainActivity.kt", "ui/compose/screen/settings/SettingsTab.kt")
        val root = File("src/main/java/org/desklink/mobile")
        val users = File("src/main/java").walkTopDown().filter { it.extension == "kt" || it.extension == "java" }
            .filter { it.readText().contains("org.desklink.mobile.update") && it.relativeTo(root).path.startsWith("update/").not() && !it.relativeTo(root).path.startsWith("ui/compose/update/") }
            .map { it.relativeTo(root).path }.toSet()
        assertTrue("unexpected callers of the update module: ${users - allowed}", allowed.containsAll(users))
    }

    @Test fun updateUiExistsOnlyInThePopupAndSettings() {
        // The sheet is hosted once by MainActivity; the controls are one section of About. No Home card, tab, list, badge or notification.
        val root = File("src/main/java/org/desklink/mobile")
        val names = Regex("UpdatePromptHost|UpdatesSettings\\b|UpdateSheet|UpdateIllustrationCard|UpdateNoteRow|UpdateUi\\b")
        val users = File("src/main/java").walkTopDown().filter { it.extension == "kt" || it.extension == "java" }
            .filter { names.containsMatchIn(it.readText()) }.map { it.relativeTo(root).path }.filter { !it.startsWith("ui/compose/update/") }.toSet()
        assertEquals(setOf("ui/MainActivity.kt"), users) // About only takes a slot lambda
        for (dir in listOf("ui/compose/screen/home", "ui/compose/screen/tabs", "ui/compose/components")) {
            File(root, dir).walkTopDown().filter { it.extension == "kt" }.forEach { assertFalse("${it.name} draws update UI", it.readText().contains("R.string.update_")) }
        }
    }

    @Test fun releaseSourcesCarryNoTestKeys() {
        assertFalse(File("src/main").walkTopDown().filter { it.isFile }.any { f -> DebugKeys.KEYS.values.any { f.readText().contains(it) } })
    }

    @Test fun theUtf8DecoderReportsOverlongAndCesu() {
        // The strict decoder is what StrictJson relies on: overlong forms and CESU-8 surrogates are errors, not replacement characters.
        for (bad in listOf(byteArrayOf(0xC0.toByte(), 0x80.toByte()), byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), byteArrayOf(0xF8.toByte(), 0x88.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()))) {
            assertNull(StrictJson.parse(byteArrayOf('"'.code.toByte()) + bad + byteArrayOf('"'.code.toByte())))
        }
        assertEquals("é", StrictJson.parse("\"é\"".toByteArray()))
    }

    @Test fun noNotificationNoNewPermission() {
        val src = File("src/main/java/org/desklink/mobile/update").listFiles()!!.joinToString { it.readText() }
        assertFalse(src.contains("NotificationCompat") || src.contains("NotificationChannel"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("REQUEST_INSTALL_PACKAGES"))
        assertFalse(File("build.gradle.kts").readText().contains("play-core") || File("gradle/libs.versions.toml").readText().contains("play-core"))
    }
}
