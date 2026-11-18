/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose

/**
 * Directional isolates for text that is not ours (a computer's name, a folder) or that must keep its left-to-right reading
 * (a terminal size "80x24", a round trip "1 ms") inside any layout direction.
 *
 * The input first loses every embedding, override and isolate control (U+202A..202E, U+2066..2069): a name holding a PDI
 * would close our isolate early and let what follows be reordered by a later RLO. Built from code points, not written as
 * characters in a string: lint's BidiSpoofing check flags a literal, and this is not spoofing.
 */
private fun cp(code: Int) = String(Character.toChars(code))

private val LRI = cp(0x2066)
private val FSI = cp(0x2068)
private val PDI = cp(0x2069)

/** [text] without any bidirectional embedding, override or isolate control. */
fun withoutBidiControls(text: String): String = text.filter { it.code !in 0x202A..0x202E && it.code !in 0x2066..0x2069 }

/** [text] as one left-to-right run: "80x24" and "1 ms" read right in Arabic, and do not reorder what is around them. */
fun ltrRun(text: String): String = withoutBidiControls(text).let { if (it.isEmpty()) it else "$LRI$it$PDI" }

/** [text] as one run in its own direction (a name that may be Arabic or Latin), cut off from what is around it. */
fun isolatedRun(text: String): String = withoutBidiControls(text).let { if (it.isEmpty()) it else "$FSI$it$PDI" }

/** The header's detail line: each part its own left-to-right run, joined by a separator no part can fake (parts are never split). */
fun detailLine(parts: List<String>): String = parts.filter { it.isNotEmpty() }.joinToString(" · ") { ltrRun(it) }
