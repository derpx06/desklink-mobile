/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairs the shared components actually draw (ui/compose/Buttons.kt and components/Controls.kt), in both themes:
 * text at 4.5:1, large text and icons at 3:1 (WCAG 1.4.3 / 1.4.11). UiTokensTest covers the screens' own pairs.
 */
class TokenContrastTest {
    private fun ratio(fg: Color, bg: Color): Double {
        val a = fg.compositeOver(bg).luminance() + 0.05
        val b = bg.luminance() + 0.05
        return maxOf(a, b) / minOf(a, b)
    }

    private val themes = listOf("light" to LightTokens, "dark" to DarkTokens)

    private fun check(min: Double, pairs: (DeskLinkColors) -> List<Triple<String, Color, Color>>) {
        for ((theme, t) in themes) for ((what, fg, bg) in pairs(t)) {
            val r = ratio(fg, bg)
            assertTrue("$theme $what is ${"%.2f".format(r)}:1, needs $min", r >= min)
        }
    }

    @Test
    fun textOnEverySurfaceIsAa() = check(4.5) { t ->
        listOf(
            Triple("text on bg", t.text, t.bg),
            Triple("text on surface", t.text, t.surface),
            Triple("text on surface2", t.text, t.surface2),
            Triple("textDim on bg", t.textDim, t.bg),
            Triple("textDim on surface", t.textDim, t.surface),
            Triple("textDim on surface2", t.textDim, t.surface2),
            Triple("text on accentContainer", t.text, t.accentContainer),
            // PrimaryButton, TonalButton, SecondaryButton, DeskTextButton.
            Triple("onAccent on accent", t.onAccent, t.accent),
            Triple("onAccentContainer on accentContainer", t.onAccentContainer, t.accentContainer),
            Triple("accentText on bg", t.accentText, t.bg),
            Triple("accentText on surface", t.accentText, t.surface),
            // SegmentedControl: the unselected label on the track (the fill over the window), the selected one on the card.
            Triple("textDim on segment track", t.textDim, t.fill.compositeOver(t.bg)),
            Triple("text on segment track", t.text, t.fill.compositeOver(t.bg)),
            Triple("text on selected segment", t.text, t.surface),
            // DeskFilterChip, AssistChip.
            Triple("textDim on unselected chip", t.textDim, t.bg),
            Triple("onAccentContainer on selected chip", t.onAccentContainer, t.accentContainer),
            Triple("text on assist chip", t.text, t.surface),
            // ActionTile: label and hint on the primary fill, on the card.
            Triple("onAccent on primary tile", t.onAccent, t.accent),
            // ChecklistRow: "Allow" on the tonal fill, "On" in mint on the card.
            Triple("onAccentContainer on Allow", t.onAccentContainer, t.accentContainer),
            Triple("mintText on surface", t.mintText, t.surface),
            // CheckItem: a done line is drawn at 75 % of the container's text colour.
            Triple("done check item", t.onAccentContainer.copy(alpha = 0.75f), t.accentContainer),
            // StatusPill: its word in the status colour on that colour's 12 % tint, over the card.
            *listOf("mintText" to t.mintText, "accent" to t.accent, "amber" to t.amber, "textDim" to t.textDim).flatMap { (name, c) ->
                val word = if (name == "amber") t.text else c // the pill writes amber's word in the text colour
                // Over the card only: on the bare window mintText is 4.2:1 in light, so the pill is always placed on a card.
                listOf(Triple("$name on its pill over surface", word, c.copy(alpha = 0.12f).compositeOver(t.surface)))
            }.toTypedArray(),
            // SegmentedControl, selected: dark draws onAccentContainer on the accent container.
            Triple("onAccentContainer on selected segment", t.onAccentContainer, t.accentContainer),
            // Snackbar.
            Triple("onInverse on inverse", t.onInverse, t.inverse),
            Triple("snackbar action on inverse", if (t.isDark) LightTokens.accentText else DarkTokens.accentText, t.inverse),
        )
    }

    @Test
    fun largeTextAndIconsAreAtLeastThree() = check(3.0) { t ->
        listOf(
            // The 22 dp glyph of a ChecklistRow, GroupRow and the like.
            Triple("textDim icon on surface", t.textDim, t.surface),
            Triple("accent icon on surface", t.accent, t.surface),
            Triple("accent icon on bg", t.accent, t.bg),
            Triple("onAccentContainer icon on accentContainer", t.onAccentContainer, t.accentContainer),
            Triple("onAccent icon on accent", t.onAccent, t.accent),
            // StatusDot colours, drawn next to a word.
            Triple("mint dot on surface", t.mint, t.surface),
            Triple("mint dot on bg", t.mint, t.bg),
            Triple("amber dot on surface", t.amber, t.surface),
            Triple("red dot on surface", t.red, t.surface),
            Triple("coral dot on surface", t.coral, t.surface),
            // An offline glyph and a disabled icon: faint, but never under 3.
            Triple("textFaint on surface", t.textFaint, t.surface),
            // A ring around an undone CheckItem is drawn at 70 %.
            Triple("check ring", t.onAccentContainer.copy(alpha = 0.7f), t.accentContainer),
            // The progress fill on its track.
            Triple("progress fill on track", t.accent, t.surface2),
            // Not checked, on purpose: the design's off-switch track (`sw`, 18 % black / 20 % white) is 1.5:1 on the card, under
            // WCAG 1.4.11's 3:1. The colour stays as designed; wave 6 decides on an outline (docs/UI-REDESIGN.md asks for #7A7A82).
        )
    }

    /** In dark the card colour is 1.1:1 against the track; the selected segment is the accent container with an accent edge. */
    @Test
    fun theSelectedSegmentShowsAgainstTheTrackInDark() {
        val t = DarkTokens
        val track = t.fill.compositeOver(t.bg)
        val edge = ratio(t.accentText, track)
        assertTrue("selected segment edge is ${"%.2f".format(edge)}:1 against the track, needs 3", edge >= 3.0)
        assertTrue("the old card-coloured segment was ${"%.2f".format(ratio(t.surface, track))}:1", ratio(t.surface, track) < 1.5)
    }
}
