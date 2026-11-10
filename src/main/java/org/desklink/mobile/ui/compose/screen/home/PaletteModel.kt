/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.screen.home

import java.text.Normalizer

/** The groups of the "Find anything" screen, in the order they are listed. */
enum class PaletteGroup { ACTIONS, FILES, NOTES, SNIPPETS, SETTINGS, COMPUTERS }

/**
 * One thing the palette can open or do. [id] says which ("action:ring", "note:<id>", "settings:<route>"): the screen
 * maps it to a call, this file never does. [icon] is a drawable id, carried only so the list can be drawn.
 * [hint] is the dim text at the end of the row; [keywords] are extra words that find it ("clipboard" finds "Send clipboard"
 * by "copy").
 */
data class PaletteItem(
    val id: String,
    val group: PaletteGroup,
    val title: String,
    val icon: Int = 0,
    val hint: String = "",
    val keywords: String = "",
    /** Why it cannot run now: it is listed dimmed with its "Needs …" as the hint, and choosing it says how to fix it. */
    val blocked: Blocker? = null,
)

data class PaletteSection(val group: PaletteGroup, val items: List<PaletteItem>)

/** Ranking and grouping for the palette: pure, so a test can say what a query finds without drawing anything. */
object PaletteModel {
    /** Rows per group before anything is typed (actions show all of them: they are the point of the screen). */
    const val IDLE_PER_GROUP = 3

    /** Rows per group for a query. */
    const val FOUND_PER_GROUP = 6

    /** Lower-case, accents removed, so "Café" is found by "cafe". */
    private val MARKS = Regex("\\p{M}+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val BLANKS = Regex("\\s+")

    internal fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

    /**
     * How well [item] matches every word of the query: 0 the title starts with it, 1 a word of the title does, 2 the title
     * contains it, 3 the hint or keywords do. The sum over the words; null when any word matches nothing.
     */
    internal fun score(item: PaletteItem, words: List<String>): Int? {
        val title = fold(item.title)
        val titleWords = title.split(NOT_WORD).filter { it.isNotEmpty() }
        val rest = fold(item.hint + " " + item.keywords)
        var total = 0
        for (word in words) {
            total += when {
                title.startsWith(word) -> 0
                titleWords.any { it.startsWith(word) } -> 1
                word in title -> 2
                word in rest -> 3
                else -> return null
            }
        }
        return total
    }

    /**
     * The sections for [query]: groups in [PaletteGroup] order, each ranked (best first, the given order among equals) and
     * cut to [FOUND_PER_GROUP]. A blank query lists every action and [IDLE_PER_GROUP] of each other group. Empty groups are
     * left out, so an empty list means "Nothing matches".
     */
    fun search(items: List<PaletteItem>, query: String): List<PaletteSection> {
        val words = fold(query).split(BLANKS).filter { it.isNotEmpty() }
        return PaletteGroup.entries.mapNotNull { group ->
            val inGroup = items.filter { it.group == group }
            val shown = if (words.isEmpty()) {
                if (group == PaletteGroup.ACTIONS) inGroup else inGroup.take(IDLE_PER_GROUP)
            } else {
                inGroup.mapIndexedNotNull { index, item -> score(item, words)?.let { Triple(it, index, item) } }
                    .sortedWith(compareBy({ it.first }, { it.second }))
                    .take(FOUND_PER_GROUP)
                    .map { it.third }
            }
            shown.takeIf { it.isNotEmpty() }?.let { PaletteSection(group, it) }
        }
    }

    /** Ids of the actions. */
    object Action {
        const val SEND_FILES = "action:send-files"
        const val SEND_CLIPBOARD = "action:send-clipboard"
        const val SEND_LINK = "action:send-link"
        const val DESK_SCREEN = "action:desk-screen"
        const val RING = "action:ring"
        const val PING = "action:ping"
        const val LOCK = "action:lock"
        const val UNLOCK = "action:unlock"
        const val SHARE_SCREEN = "action:share-screen"
        const val NEW_TERMINAL = "action:new-terminal"
        const val OPEN_TERMINALS = "action:terminals"
        const val NEW_NOTE = "action:new-note"
        const val OPEN_BUCKET = "action:bucket"
        const val PAIR_ANOTHER = "action:pair-another"
    }

    /**
     * The actions, in the order they are listed: all of them, always. One the computer cannot take now is listed dimmed with
     * why ([HomeDerive.blocker]), never left out, so what DeskLink can do is always findable.
     */
    @Suppress("UNUSED_PARAMETER")
    fun offeredActions(s: HomeState): List<String> = listOf(
        Action.SEND_FILES, Action.SEND_CLIPBOARD, Action.SEND_LINK, Action.DESK_SCREEN, Action.NEW_TERMINAL,
        Action.OPEN_TERMINALS, Action.NEW_NOTE, Action.OPEN_BUCKET,
        Action.RING, Action.PING, Action.LOCK, Action.UNLOCK, Action.SHARE_SCREEN,
        Action.PAIR_ANOTHER,
    )
}
