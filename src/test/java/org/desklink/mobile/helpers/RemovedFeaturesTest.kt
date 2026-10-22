/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemovedFeaturesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun onlyTheRemovedFeaturesFilesAreDeleted() {
        val dir = temp.newFolder("files")
        val stale = RemovedFeatures.FILES.map { File(dir, it).apply { writeText("rules") } }
        // Pairing and everything else the app keeps stays exactly where it is.
        val kept = listOf("bucket.json", "notes.json", "trusted.json").map { File(dir, it).apply { writeText("keep") } }

        val deleted = RemovedFeatures.deleteFiles(dir)

        assertEquals(RemovedFeatures.FILES.toSet(), deleted.toSet())
        stale.forEach { assertFalse(it.name, it.exists()) }
        kept.forEach { assertTrue(it.name, it.exists()) }
    }

    @Test
    fun deletingTwiceOrWithNothingThereIsHarmless() {
        val dir = temp.newFolder("empty")
        assertEquals(emptyList<String>(), RemovedFeatures.deleteFiles(dir))
        File(dir, "automations.json").writeText("x")
        assertEquals(listOf("automations.json"), RemovedFeatures.deleteFiles(dir))
        assertEquals(emptyList<String>(), RemovedFeatures.deleteFiles(dir))
        assertEquals(emptyList<String>(), RemovedFeatures.deleteFiles(File(dir, "missing")))
    }

    @Test
    fun staleKeysAreTheRemovedSettingsAndPerDeskCommandLists() {
        val all = listOf(
            "commands_preference_abc123", "commands_preference_", "bigscreen_show_home", "pref_sendkeystrokes_enabled",
            "compose_send_input_cache", "digitizer_hide_draw_button",
            // Kept: pairing, the desk screen, scroll, and anything else.
            "mousepad_scroll_direction", "mousepad_sensitivity_key", "paired_devices", "commands", "abc_commands_preference_x",
        )
        assertEquals(
            listOf(
                "commands_preference_abc123", "commands_preference_", "bigscreen_show_home", "pref_sendkeystrokes_enabled",
                "compose_send_input_cache", "digitizer_hide_draw_button",
            ),
            RemovedFeatures.staleKeys(all),
        )
    }

    @Test
    fun noKeptSettingIsListedForDeletion() {
        val kept = listOf("mousepad_scroll_direction", "mousepad_sensitivity_key", "findmyphone_ringtone", "share_destination_custom", "share_destination_folder_uri")
        assertEquals(emptyList<String>(), RemovedFeatures.staleKeys(kept))
    }
}
