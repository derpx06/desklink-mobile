/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

/**
 * Text from the manifest is PLAIN TEXT (docs/UPDATES.md 2.2). A string is taken as it is or not at all: the code points of the
 * FROZEN list in fixtures/README.md (not a Unicode-version table: platforms disagree) and `<` `>` `&` make it invalid, and the
 * length limit counts Unicode scalars (code points; a surrogate pair is one, `e` + U+0301 is two). It must be shown with a plain
 * `Text(string)`: never an AnnotatedString, Html.fromHtml or Linkify built from it.
 */
internal object PlainText {
    // Cc, Zl/Zp, Cs and the listed Cf code points; everything else (unassigned, private use, U+00A0, U+3000) is allowed.
    private val FORBIDDEN: List<IntRange> = listOf(
        0x0000..0x001F, 0x007F..0x009F, 0x2028..0x2029, 0xD800..0xDFFF,
        0x00AD..0x00AD, 0x0600..0x0605, 0x061C..0x061C, 0x06DD..0x06DD, 0x070F..0x070F, 0x0890..0x0891, 0x08E2..0x08E2,
        0x180E..0x180E, 0x200B..0x200F, 0x202A..0x202E, 0x2060..0x2064, 0x2066..0x206F, 0xFEFF..0xFEFF, 0xFFF9..0xFFFB,
        0x110BD..0x110BD, 0x110CD..0x110CD, 0x13430..0x13440, 0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0001..0xE0001, 0xE0020..0xE007F,
        '<'.code..'<'.code, '>'.code..'>'.code, '&'.code..'&'.code,
    )

    /** [s] when it is 1..[max] scalars with none of the forbidden code points, else null. */
    fun valid(s: Any?, max: Int): String? {
        if (s !is String || s.isEmpty() || s.codePointCount(0, s.length) > max) return null
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (FORBIDDEN.any { cp in it }) return null
        }
        return s
    }
}
