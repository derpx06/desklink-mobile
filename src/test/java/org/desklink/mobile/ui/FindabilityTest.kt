/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.screen.home.ComputerRow
import org.desklink.mobile.ui.compose.screen.home.Everything
import org.desklink.mobile.ui.compose.screen.home.HomeState
import org.desklink.mobile.ui.compose.screen.home.PaletteModel
import org.desklink.mobile.ui.compose.screen.home.buildItems
import org.desklink.mobile.ui.compose.screen.settings.SettingsIndex
import org.desklink.mobile.ui.compose.screen.settings.SettingsPage
import org.desklink.mobile.ui.compose.screen.settings.SettingsSearch
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Anything DeskLink does is found by typing plain words: every feature of "Everything DeskLink does" and every Settings row, by
 * its own name and by the everyday words people use for it, in Home's search and in the Settings search.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FindabilityTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val state = HomeState("d", "arch-desk", DeviceConnection.LOCAL, listOf(ComputerRow("d", "arch-desk", DeviceConnection.LOCAL)))
    private val items = buildItems(context, state, emptyList(), emptyList()).items
    private fun text(id: Int) = context.getString(id, "arch-desk")

    /** The words people use for each feature (its id), beyond its name. */
    private val synonyms = mapOf(
        "send-files" to listOf("photos", "pictures", "gallery", "upload", "share", "send"),
        "send-link" to listOf("url", "website", "web page"),
        "bucket" to listOf("bucket", "shared folder", "files"),
        "desk-screen" to listOf("remote", "control computer", "touchpad", "mouse"),
        "extend" to listOf("extend", "second display", "monitor"),
        "share-screen" to listOf("mirror", "cast", "screen"),
        Everything.GLIDE to listOf("glide", "mouse", "pointer", "touchpad"),
        "terminals" to listOf("shell", "command line", "console"),
        "lock" to listOf("lock"),
        "unlock" to listOf("unlock", "login", "password"),
        Everything.NOTIFICATIONS to listOf("notifications", "alerts", "messages"),
        Everything.DESK_NOTIFICATIONS to listOf("alerts", "notifications"),
        Everything.CALLS to listOf("calls", "phone", "dial", "ringing"),
        Everything.CONTACTS to listOf("contacts", "people", "address book"),
        "clipboard" to listOf("clipboard", "copy paste", "copy"),
        "notes" to listOf("notes", "memo", "write"),
        Everything.MEDIA to listOf("music", "player", "song", "pause"),
        "sound" to listOf("audio", "microphone", "speaker"),
        "ring" to listOf("find my", "lost", "find"),
        "ping" to listOf("nudge", "hello"),
        "tiles" to listOf("widgets", "quick settings", "shortcut"),
        "pair" to listOf("add computer", "qr code", "connect"),
        "guide" to listOf("help", "how to"),
    )

    /** Settings search: everyday words and the row (title id) they must find. */
    private val settingsWords = listOf(
        "photos" to org.desklink.mobile.R.string.settings_gallery_send, "pictures" to org.desklink.mobile.R.string.settings_gallery_send,
        "gallery" to org.desklink.mobile.R.string.settings_gallery_send, "mouse" to org.desklink.mobile.R.string.settings_glide,
        "touchpad" to org.desklink.mobile.R.string.settings_glide, "glide" to org.desklink.mobile.R.string.settings_glide,
        "mirror" to org.desklink.mobile.R.string.settings_session_gate, "cast" to org.desklink.mobile.R.string.settings_session_gate,
        "unlock" to org.desklink.mobile.R.string.settings_unlock_nearby, "lock" to org.desklink.mobile.R.string.settings_unlock_nearby,
        "clipboard" to org.desklink.mobile.R.string.settings_phone_clipboard, "copy paste" to org.desklink.mobile.R.string.settings_phone_clipboard,
        "contacts" to org.desklink.mobile.R.string.settings_contacts_share, "calls" to org.desklink.mobile.R.string.settings_calls_on,
        "phone" to org.desklink.mobile.R.string.settings_calls_on, "notifications" to org.desklink.mobile.R.string.settings_phone_notifications,
        "alerts" to org.desklink.mobile.R.string.settings_phone_notifications, "files" to org.desklink.mobile.R.string.settings_downloads_folder,
        "send" to org.desklink.mobile.R.string.settings_gallery_send, "share" to org.desklink.mobile.R.string.settings_bucket_with,
        "bucket" to org.desklink.mobile.R.string.settings_bucket_with, "music" to org.desklink.mobile.R.string.settings_phone_media,
        "screen" to org.desklink.mobile.R.string.settings_session_gate, "widgets" to org.desklink.mobile.R.string.surfaces_add_tiles,
    )

    private fun paletteFinds(query: String, targets: Collection<String>) =
        PaletteModel.search(items, query).flatMap { it.items }.any { it.id in targets }

    @Test
    fun homeSearchFindsEveryFeatureAndSettingByNameAndEverydayWords() {
        val misses = mutableListOf<String>()
        var checks = 0
        fun check(ok: Boolean, what: String) { checks++; if (!ok) misses += what }
        for (f in Everything.features) {
            val targets = f.palette + f.open + ("feature:" + f.id)
            val name = text(f.name)
            check(paletteFinds(name, targets), "feature \"$name\" by its name")
            val words = synonyms[f.id]
            check(words != null, "feature ${f.id} has everyday words in this test")
            words?.forEach { check(paletteFinds(it, targets), "feature \"$name\" by \"$it\"") }
        }
        for (row in SettingsIndex.rows) {
            val title = text(row.title)
            check(PaletteModel.search(items, title).flatMap { it.items }.any { it.title == title }, "setting \"$title\" by its name")
        }
        println("home search: found ${checks - misses.size} of $checks")
        assertTrue("home search misses ${misses.size} of $checks:\n" + misses.joinToString("\n"), misses.isEmpty())
    }

    @Test
    fun settingsSearchFindsEveryRowByNameAndAWordForIt() {
        val entries = SettingsIndex.entries(context, "arch-desk", SettingsPage.entries)
        val misses = mutableListOf<String>()
        var checks = 0
        fun check(ok: Boolean, what: String) { checks++; if (!ok) misses += what }
        for (row in SettingsIndex.rows) {
            val title = text(row.title)
            check(SettingsSearch.search(entries, title).any { it.title == title }, "\"$title\" by its name")
            val word = row.keywords?.let(::text)?.split(" ")?.firstOrNull { it.isNotBlank() }
            check(word != null && SettingsSearch.search(entries, word).any { it.title == title }, "\"$title\" by a word for it ($word)")
        }
        for ((query, title) in settingsWords) {
            check(SettingsSearch.search(entries, query).any { it.title == text(title) }, "\"$query\" finds \"${text(title)}\"")
        }
        println("settings search: found ${checks - misses.size} of $checks")
        assertTrue("settings search misses ${misses.size} of $checks:\n" + misses.joinToString("\n"), misses.isEmpty())
    }
}
