/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * One word for one thing, in both apps (the desk pins the same list in `the_words_follow_the_glossary`): "Try again",
 * "Touchpad", "computer" (never "desk" for the machine; "Desk screen" is a feature's name), "Show phone screen".
 */
class GlossaryTest {
    private val offGlossary = listOf(
        "Retry" to "Try again",
        "Trackpad" to "Touchpad",
        "PC" to "computer",
        "Show Phone Screen" to "Show phone screen",
        "Desk sound" to "Computer sound",
        "Lock desk" to "Lock computer",
        "Ring desk" to "Ring computer",
        "Turn Off" to "Turn off",
        "Handoff" to "Hand off",
        "Hand-off" to "Hand off",
    )

    private fun hasWord(text: String, word: String) = Regex("""(?<![\p{L}\p{N}-])${Regex.escape(word)}(?![\p{L}\p{N}-])""").containsMatchIn(text)

    /** Every string a person reads, from the default (English) resources, as (name, text): keyword lists are not shown. */
    private fun shown(): List<Pair<String, String>> =
        File("src/main/res/values").listFiles { file -> file.name.startsWith("strings") }!!.flatMap { file ->
            Regex("""<(?:string|item)(?:\s+name="([^"]*)")?([^>]*)>([^<]*)<""").findAll(file.readText())
                .filter { "translatable=\"false\"" !in it.groupValues[2] }
                .map { it.groupValues[1] to it.groupValues[3] }
                .toList()
        }

    /**
     * The words of the machinery, which the desk's `assert_plain` bans from its first run and guide too. Credits keep
     * upstream's words; the relay's own lines show only on a relay link, which 1.3 never makes (RELAY_ENABLED is off).
     */
    private val jargon = listOf("WebRTC", "peer", "handover", "mDNS", "TLS", "daemon", "packet", "plugin", "codec", "protocol", "GStreamer", "LAN", "certificate", "successfully", "Please", "relay", "Relay")
    private val relayOnly = setOf("remote_path_turn", "home_connected_relay", "home_connected_turn", "home_short_relay", "home_trying_paths", "home_check_away_relay_on")

    @Test
    fun noUserStringUsesJargon() {
        val off = shown().filter { (name, text) ->
            name !in relayOnly && !name.endsWith("_task") && name != "everyone_else" && jargon.any { hasWord(text, it) }
        }
        assertEquals(emptyList<Pair<String, String>>(), off)
    }

    @Test
    fun theWordsFollowTheGlossary() {
        val off = shown().flatMap { (file, text) ->
            offGlossary.filter { (word, _) -> hasWord(text, word) }.map { (word, say) -> "$file: \"$text\" says \"$word\", say \"$say\"" }
        }
        assertEquals(emptyList<String>(), off)
    }

    @Test
    fun theComputerIsNeverCalledTheDesk() {
        val off = shown().filter { (_, text) -> hasWord(text.replace("Desk screen", "").replace("your desk", ""), "desk") || hasWord(text, "Desk") && !text.contains("Desk screen") }
        assertEquals(emptyList<Pair<String, String>>(), off)
    }
}
