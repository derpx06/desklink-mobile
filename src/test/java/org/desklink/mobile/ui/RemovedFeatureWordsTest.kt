/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** No string a person can read may still name a feature 1.3 removed. */
class RemovedFeatureWordsTest {
    private val forbidden = Regex(
        "drawing pad|webcam|presenter|tv remote|automation|keystroke|quick command|device controls|run command[,.]|run command and",
        RegexOption.IGNORE_CASE,
    )

    @Test
    fun noUserVisibleStringNamesARemovedFeature() {
        val res = File("src/main/res")
        val hits = res.walkTopDown().filter { it.isFile && it.name.startsWith("strings") && it.extension == "xml" }.flatMap { file ->
            Regex("""<(?:string|item)[^>]*>([^<]*)<""").findAll(file.readText()).mapNotNull { m ->
                m.groupValues[1].takeIf { forbidden.containsMatchIn(it) }?.let { "${file.parentFile.name}/${file.name}: $it" }
            }
        }.toList()
        assertEquals("removed features still named: $hits", emptyList<String>(), hits)
    }

    @Test
    fun theManifestNamesNoRemovedFeature() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        listOf("runcommand", "digitizer", "presenter", "bigscreen", "automations", "camera.CameraService", "SendKeystrokes", "RemoteKeyboard")
            .forEach { assertEquals(it, false, manifest.contains(it, ignoreCase = true)) }
    }
}
