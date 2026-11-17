/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

/**
 * Text from the manifest is PLAIN TEXT (docs/UPDATES.md 2.2). A string is taken as it is or not at all: control and format
 * characters (newline, tab, bidi, zero width, BOM), surrogates, line/paragraph separators and `<` `>` `&` make it invalid, and
 * the length limit counts Unicode scalars (a surrogate pair is one), not UTF-16 units. It must be shown with a plain
 * `Text(string)`: never an AnnotatedString, Html.fromHtml or Linkify built from it.
 */
internal object PlainText {
    /** [s] when it is 1..[max] scalars of allowed characters, else null. */
    fun valid(s: Any?, max: Int): String? {
        if (s !is String || s.isEmpty() || s.codePointCount(0, s.length) > max) return null
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '<'.code || cp == '>'.code || cp == '&'.code || forbidden(cp)) return null
        }
        return s
    }

    private fun forbidden(cp: Int) = when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> false
    }
}
