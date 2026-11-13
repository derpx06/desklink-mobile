/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.settings

import org.desklink.mobile.ui.compose.screen.settings.SettingsEntry
import org.desklink.mobile.ui.compose.screen.settings.SettingsIndex
import org.desklink.mobile.ui.compose.screen.settings.SettingsPage
import org.desklink.mobile.ui.compose.screen.settings.SettingsSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Settings search index against the rows SettingsTab.kt builds: a row on a page that the search cannot find (or an index row
 * with no row behind it) fails here. Also pins that every row title the page had before the redesign is still present.
 */
class SettingsSearchTest {
    private val root = File("src/main").takeIf { it.exists() } ?: File("desklink-mobile/src/main")
    private val source = root.resolve("java/org/desklink/mobile/ui/compose/screen/settings/SettingsTab.kt").readText()
    private val index = root.resolve("java/org/desklink/mobile/ui/compose/screen/settings/SettingsSearch.kt").readText()

    private fun arguments(text: String, open: Int): List<String> {
        var depth = 0
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = open
        while (true) {
            val c = text[i]
            if (c in "([{") depth++
            if (c in ")]}") { depth--; if (depth == 0) { out += current.toString(); return out } }
            if (c == ',' && depth == 1) { out += current.toString(); current.clear() } else if (i != open) current.append(c)
            i++
        }
    }

    private val groups = mapOf(
        "ConnectionGroup" to "CONNECTION", "NotificationsGroup" to "NOTIFICATIONS", "ClipboardGroup" to "CLIPBOARD", "CallsGroup" to "CALLS",
        "SoundGroup" to "SOUND", "ControlGroup" to "CONTROL", "FilesGroup" to "FILES", "PrivacyGroup" to "PRIVACY",
        "AppearanceGroup" to "APPEARANCE", "AdvancedGroup" to "ADVANCED",
    )

    private fun rowsOf(function: String): List<String> {
        val start = source.indexOf("fun $function(")
        val body = source.substring(start, source.indexOf("\nprivate fun ", start + 1).takeIf { it > 0 } ?: source.length)
        return Regex("""\b(GroupRow|DeskSwitch|UnpairRow|GalleryRows)\(""").findAll(body).flatMap { call ->
            when (call.groupValues[1]) {
                "UnpairRow" -> return@flatMap listOf("settings_unpair_row")
                "GalleryRows" -> return@flatMap listOf("settings_gallery_send", "settings_gallery_screenshots", "settings_gallery_camera")
            }
            val args = arguments(body, call.range.last)
            val title = args.getOrNull(if (call.groupValues[1] == "DeskSwitch") 3 else 0) ?: return@flatMap emptyList()
            listOfNotNull(Regex("""R\.string\.(\w+)""").find(title)?.groupValues?.get(1))
        }.filter { it != "feature_stopped_title" }.toList() // shown only after a crash
    }

    private fun indexed(page: String) =
        Regex("""Row\(SettingsPage\.$page, R\.string\.(\w+)""").findAll(index).map { it.groupValues[1] }.toList()

    @Test fun everyRowOfEveryPageIsInTheSearchIndex() {
        groups.forEach { (function, page) -> assertEquals(page, rowsOf(function), indexed(page)) }
    }

    @Test fun theRowsThePagesHadBeforeAreStillThere() {
        val all = groups.values.flatMap(::indexed)
        listOf(
            "settings_phone_name", "settings_connect_on_startup", "settings_keep_awake", "home_pair_another", "settings_phone_notifications",
            "settings_desk_shows_notifications", "settings_command_alerts", "settings_phone_clipboard", "settings_desk_clipboard", "home_send_clipboard",
            "settings_calls_on", "settings_desk_sound", "settings_phone_media", "settings_glide", "settings_bucket_with", "settings_downloads_folder",
            "settings_contacts_share", "settings_session_password", "settings_unlock_nearby", "device_encryption", "settings_unpair_row",
            "settings_theme", "setting_dynamic_colors", "setting_advanced_network", "trusted_networks", "settings_permissions", "about",
        ).forEach { assertTrue("$it is searchable", it in all) }
    }

    @Test fun searchNeedsEveryWordAndIgnoresCase() {
        val p = SettingsPage.CLIPBOARD
        val entries = listOf(SettingsEntry(p, "Share this phone's clipboard", "Copies go to arch-desk"), SettingsEntry(p, "Pointer speed"))
        assertEquals(listOf(entries[0]), SettingsSearch.search(entries, "CLIPBOARD arch"))
        assertEquals(listOf(entries[1]), SettingsSearch.search(entries, "  speed "))
        assertTrue(SettingsSearch.search(entries, "clipboard speed").isEmpty())
        assertTrue(SettingsSearch.search(entries, "   ").isEmpty())
    }

    @Test fun rowsThatExistOnlyWithAComputerAreNotSearchableWithout() {
        fun titles(hasDesk: Boolean) = groups.values.flatMap { SettingsPage.valueOf(it).let { page -> SettingsIndex.rowsFor(page, hasDesk) } }.map { it.title }
        val keepAwake = org.desklink.mobile.R.string.settings_keep_awake
        assertTrue(keepAwake in titles(true))
        assertTrue(keepAwake !in titles(false))
        assertTrue("phone-only rows stay", org.desklink.mobile.R.string.settings_phone_name in titles(false))
    }

    /**
     * No build has a row that turns the relay or TURN on or off, or sets their servers: BuildConfig.RELAY_ENABLED in code is
     * the only control. Read from the settings sources, so it holds whatever the switch is set to.
     */
    @Test fun noSettingsRowControlsTheRelayOrTurn() {
        val ui = root.resolve("java/org/desklink/mobile/ui").walk().filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        for (key in listOf("RelaySettings.KEY_ENABLED", "RelaySettings.KEY_URL", "RelaySettings.KEY_USE_TURN", "RelaySettings.KEY_FORCE", "KEY_WEBRTC_TURN_SERVERS_PREFERENCE", "DeskSettingsPlugin.RELAY")) {
            assertTrue("$key is not touched by the UI", key !in ui)
        }
        for (page in groups.keys) assertTrue("$page has no relay row", rowsOf(page).none { "relay" in it || "use_turn" in it || "turn_servers" in it })
    }
}
