/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.settings

import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.ui.compose.screen.settings.SettingsIndex
import org.desklink.mobile.ui.compose.screen.settings.SettingsPage
import org.desklink.mobile.ui.compose.screen.settings.SettingsSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The search finds a setting by what people call it, not only by the words of its title. */
@RunWith(RobolectricTestRunner::class)
class SettingsKeywordsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val entries = SettingsIndex.entries(context, "manas-82xf", SettingsPage.entries)

    private fun found(query: String) = SettingsSearch.search(entries, query).map { it.title }

    @Test fun glideAndItsNeighboursFindTheControlRow() {
        for (q in listOf("glide", "mouse", "keyboard", "edge", "share mouse", "control", "pointer")) {
            assertTrue("\"$q\" finds Let manas-82xf control this phone: ${found(q)}", "Let manas-82xf control this phone" in found(q))
        }
    }

    @Test fun commonSynonymsFindTheirSettings() {
        assertTrue("Share this phone's clipboard" in found("paste"))
        assertTrue("Fingerprint before terminals" in found("fingerprint") || found("fingerprint").isNotEmpty())
        assertTrue(found("wallpaper").isNotEmpty())
        assertTrue(found("microphone").isNotEmpty())
        assertTrue(found("dark").isNotEmpty())
        assertTrue(found("ssid").isNotEmpty())
        assertTrue(found("forget").isNotEmpty())
    }

    @Test fun everyRowHasSynonymsAndTheyAreNotShown() {
        assertTrue("a row without keywords: " + SettingsIndex.rows.filter { it.keywords == null }.map { it.title }, SettingsIndex.rows.all { it.keywords != null })
        // Keywords are searched, never part of the title or the line shown under it.
        assertEquals("Let manas-82xf control this phone", entries.first { it.keywords.contains("synergy") }.title)
    }
}
