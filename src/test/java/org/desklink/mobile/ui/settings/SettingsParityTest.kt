/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The phone's Settings against the agreed list in docs/protocol/stageD-protocol.md ("W7 settings parity"): the ten group titles and,
 * per group, the rows (a row is one GroupRow or DeskSwitch; the title is its first argument) in order. A row added, renamed or
 * moved to another group fails here until the contract and the desk's list are changed with it.
 */
class SettingsParityTest {
    private val root = File("src/main").takeIf { it.exists() } ?: File("desklink-mobile/src/main")

    private val strings: Map<String, String> = root.resolve("res/values").listFiles { f -> f.name.endsWith(".xml") }.orEmpty().flatMap { file ->
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText())
            .map { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("&amp;", "&") }.toList()
    }.toMap()

    private fun english(name: String) = strings.getValue(name).replace(Regex("%\\d[$]s"), "<computer>")

    private val source = root.resolve("java/org/desklink/mobile/ui/compose/screen/settings/SettingsTab.kt").readText()

    /** The top-level arguments of the call whose "(" is at [open]. */
    private fun arguments(text: String, open: Int): List<String> {
        var depth = 0
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = open
        while (true) {
            val c = text[i]
            if (c in "([{") depth++
            if (c in ")]}") {
                depth--
                if (depth == 0) { out += current.toString(); return out }
            }
            if (c == ',' && depth == 1) { out += current.toString(); current.clear() } else if (!(i == open)) current.append(c)
            i++
        }
    }

    private fun rows(function: String): List<String> {
        val start = source.indexOf("fun $function(")
        val body = source.substring(start, source.indexOf("\nprivate fun ", start + 1).takeIf { it > 0 } ?: source.length)
        return Regex("""\b(GroupRow|DeskSwitch|UnpairRow|GalleryRows)\(""").findAll(body).flatMap { call ->
            if (call.groupValues[1] == "UnpairRow") return@flatMap listOf(english("settings_unpair_row"))
            // GalleryRows is its own function: the switch, then the two folders it watches.
            if (call.groupValues[1] == "GalleryRows") return@flatMap listOf("settings_gallery_send", "settings_gallery_screenshots", "settings_gallery_camera").map(::english)
            val args = arguments(body, call.range.last)
            val title = args.getOrNull(if (call.groupValues[1] == "DeskSwitch") 3 else 0) ?: return@flatMap emptyList()
            listOfNotNull(Regex("""R\.string\.(\w+)""").find(title)?.let { english(it.groupValues[1]) })
        }.toList()
    }

    private fun groupTitles(): List<String> =
        Regex("""\w+\("(\w+)", R\.string\.(\w+),""").findAll(source.substringAfter("enum class SettingsPage")).map { english(it.groupValues[2]) }.toList()

    @Test
    fun theGroupTitlesAreTheDesksTenInOrder() {
        assertEquals(
            listOf("Connection", "Notifications", "Clipboard", "Calls", "Sound", "Control", "Files", "Privacy & security", "Appearance", "Advanced"),
            groupTitles(),
        )
    }

    @Test
    fun eachGroupHoldsTheRowsTheContractGivesIt() {
        assertEquals(listOf("Phone name", "Connect on startup", "Keep <computer> awake while connected", "Pair another computer"), rows("ConnectionGroup"))
        assertEquals(
            listOf(
                "Send this phone's notifications to <computer>", "Choose apps", "<computer> shows them", "Show private contents on <computer>",
                "Show <computer>'s notifications here", "Command alerts", "Show ongoing items on <computer>", "Share Do Not Disturb with <computer>", "Select a ringtone", "Persistent notification",
            ),
            rows("NotificationsGroup"),
        )
        assertEquals(
            listOf("Share this phone's clipboard", "Offer to send copied text to <computer>", "Receive <computer>'s clipboard", "Send clipboard to <computer>"),
            rows("ClipboardGroup").filter { !it.startsWith("Last sent") && !it.startsWith("Nothing sent") && !it.startsWith("Not ") },
        )
        assertEquals(listOf("Calls on <computer>", "Show caller on <computer>"), rows("CallsGroup"))
        assertEquals(
            listOf("Play <computer>'s sound here", "Let <computer> play sound here", "<computer> may use the microphone without asking", "Show this phone's media on <computer>"),
            rows("SoundGroup"),
        )
        assertEquals(
            listOf("Let <computer> control this phone", "Pointer speed", "Reverse scroll direction"),
            rows("ControlGroup"),
        )
        assertEquals(
            listOf("Bucket with <computer>", "Downloads folder", "Use the Downloads folder again", "Send new screenshots and photos to <computer>", "Screenshots folder", "Camera folder", "Share contacts with <computer>"),
            rows("FilesGroup"),
        )
        assertEquals(
            listOf("Ask before terminal and screen access", "Fingerprint before terminals", "Unlock with this phone", "Encryption", "Privacy policy", "Unpair <computer>…"),
            rows("PrivacyGroup"),
        )
        assertEquals(listOf("Style", "Use wallpaper colours"), rows("AppearanceGroup"))
        assertEquals(
            listOf(
                "Something stopped. Tap to restart", "Use the relay from this phone", "Reach <computer> from anywhere", "Advanced network",
                "Trusted networks", "Add devices by IP", "Permissions", "Quick Settings tiles", "Export DeskLink logs", "About",
            ),
            rows("AdvancedGroup").filter { it != "Style" },
        )
    }

    @Test
    fun unpairIsTheLastRowOfPrivacyAndSecurity() {
        assertEquals("Unpair <computer>…", rows("PrivacyGroup").last())
    }

    // The desk's own list (workspace protocol/testdata/desklink_v9/settings_rows.json, copied here).
    private val desk = org.json.JSONObject(javaClass.getResourceAsStream("/shared/settings_rows.json")!!.bufferedReader().readText()).getJSONArray("groups")
    private val phoneGroups = listOf("ConnectionGroup", "NotificationsGroup", "ClipboardGroup", "CallsGroup", "SoundGroup", "ControlGroup", "FilesGroup", "PrivacyGroup", "AppearanceGroup", "AdvancedGroup")

    @Test
    fun theDesksGroupsAreThePhonesGroupsInTheSameOrder() {
        assertEquals((0 until desk.length()).map { desk.getJSONObject(it).getString("title") }, groupTitles())
    }

    @Test
    fun theRowsTheContractCallsTheSameAreTheSameWordsOnBothAndUnpairIsLastOnBoth() {
        val same = mapOf(
            "Notifications" to listOf("Command alerts"), "Files" to listOf("Bucket with {phone}", "Downloads folder"),
            "Privacy & security" to listOf("Ask before terminal and screen access", "Unpair {phone}…"), "Appearance" to listOf("Style"),
        )
        for (i in 0 until desk.length()) {
            val group = desk.getJSONObject(i)
            val title = group.getString("title")
            val phoneRows = rows(phoneGroups[i])
            same[title]?.forEach { row -> assertEquals("$title: $row", true, row.replace("{phone}", "<computer>") in phoneRows) }
        }
        val deskPrivacy = desk.getJSONObject(7).getJSONArray("rows")
        assertEquals(deskPrivacy.getString(deskPrivacy.length() - 1).replace("{phone}", "<computer>"), rows("PrivacyGroup").last())
    }
}
