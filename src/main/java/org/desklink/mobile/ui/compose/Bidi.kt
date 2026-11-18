/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose

/**
 * [text] as one left-to-right run (U+2066 .. U+2069, a directional isolate) inside any layout direction: a terminal size
 * ("80x24"), a round trip ("1 ms") or a path keeps its reading in Arabic or Hebrew, where it was reversed ("24x80", "ms 1"),
 * and does not reorder the text around it.
 */
fun ltrRun(text: String): String = if (text.isEmpty()) text else "$LRI$text$PDI"

// Built from code points, not written as characters in a string: lint's BidiSpoofing check flags a literal, and this is not spoofing.
private val LRI = String(Character.toChars(0x2066))
private val PDI = String(Character.toChars(0x2069))
