/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notes

/** One `- [ ]` / `- [x]` line of a note: where it is (line index of the body), what it says, whether it is ticked. */
data class CheckLine(val line: Int, val label: String, val done: Boolean)

/** Pure helpers behind the Notes tab: checklists, search and the card layout. */
object NotesModel {
    private val CHECK = Regex("""^\s*[-*] \[([ xX])] ?(.*)$""")

    fun checklist(body: String): List<CheckLine> =
        body.split('\n').mapIndexedNotNull { i, raw ->
            CHECK.matchEntire(raw.removeSuffix("\r"))?.let { CheckLine(i, it.groupValues[2].trim(), it.groupValues[1] != " ") }
        }

    /** [body] with the checkbox on [line] set to [done]; unchanged when that line is not a checkbox. */
    fun setChecked(body: String, line: Int, done: Boolean): String {
        val lines = body.split('\n').toMutableList()
        val raw = lines.getOrNull(line) ?: return body
        val m = Regex("""^(\s*[-*] \[)([ xX])(].*)$""", RegexOption.DOT_MATCHES_ALL).matchEntire(raw) ?: return body
        lines[line] = m.groupValues[1] + (if (done) "x" else " ") + m.groupValues[3]
        return lines.joinToString("\n")
    }

    /** A note with checkboxes reads as a checklist card; a lone `- [ ]` among prose does not. */
    fun isChecklist(note: Note) = checklist(note.body).size >= 2

    fun matches(note: Note, query: String) = query.isBlank() || note.title.contains(query, true) || note.body.contains(query, true)

    fun matches(snippet: Snippet, query: String) = query.isBlank() || snippet.title.contains(query, true) || snippet.text.contains(query, true)

    /** The list's layout: a checklist is a full-width card, other notes sit [columns] to a row. */
    sealed interface Block {
        data class Checklist(val note: Note) : Block
        data class Row(val notes: List<Note>) : Block
    }

    fun blocks(notes: List<Note>, columns: Int): List<Block> {
        val out = mutableListOf<Block>()
        val run = mutableListOf<Note>()
        fun flush() { run.chunked(columns.coerceAtLeast(1)).forEach { out += Block.Row(it) }; run.clear() }
        notes.forEach { n -> if (isChecklist(n)) { flush(); out += Block.Checklist(n) } else run += n }
        flush()
        return out
    }
}
