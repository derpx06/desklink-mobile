/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notes

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/**
 * F6 N1's markdown-lite, for reading a note: `#`/`##` headings, `**bold**`, `*italic*`, `` `code` ``,
 * `- `/`* `/`1. ` lists and `- [ ]`/`- [x]` checkboxes. Anything else is plain text, as the desk shows it.
 */
object MarkdownLite {
    private val inline = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\*([^*\s][^*]*?)\*""")

    fun render(body: String): AnnotatedString = buildAnnotatedString {
        body.split('\n').forEachIndexed { index, raw ->
            if (index > 0) append('\n')
            val line = raw.removeSuffix("\r")
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length
            when {
                trimmed.startsWith("## ") -> heading(trimmed.removePrefix("## "), 18)
                trimmed.startsWith("# ") -> heading(trimmed.removePrefix("# "), 22)
                CHECKBOX.matches(trimmed) -> {
                    val done = trimmed[3].lowercaseChar() == 'x'
                    append(" ".repeat(indent) + if (done) "☑ " else "☐ ")
                    val rest = trimmed.drop(6)
                    if (done) pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    inline(rest)
                    if (done) pop()
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    append(" ".repeat(indent) + "•  ")
                    inline(trimmed.substring(2))
                }
                else -> inline(line)
            }
        }
    }

    private val CHECKBOX = Regex("""[-*] \[[ xX]] .*|[-*] \[[ xX]]""")

    private fun AnnotatedString.Builder.heading(text: String, size: Int) {
        pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = size.sp))
        inline(text)
        pop()
    }

    private fun AnnotatedString.Builder.inline(text: String) {
        var at = 0
        for (match in inline.findAll(text)) {
            append(text, at, match.range.first)
            val (bold, code, italic) = match.destructured
            when {
                bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold), bold)
                code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace), code)
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic), italic)
            }
            at = match.range.last + 1
        }
        append(text, at, text.length)
    }

    private fun AnnotatedString.Builder.withStyle(style: SpanStyle, text: String) {
        pushStyle(style)
        append(text)
        pop()
    }
}
