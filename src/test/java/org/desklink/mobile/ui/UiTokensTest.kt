/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.desklink.mobile.MockSharedPreference
import org.desklink.mobile.ui.compose.DarkTokens
import org.desklink.mobile.ui.compose.DeskLinkColors
import org.desklink.mobile.ui.compose.LightTokens
import org.desklink.mobile.ui.compose.components.LinkGlyphState
import org.desklink.mobile.ui.compose.components.linkGlyphState
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.webrtc.WebRtcConnectionPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTokensTest {
    private fun contrast(fg: Color, bg: Color): Double {
        val a = fg.compositeOver(bg).luminance() + 0.05
        val b = bg.luminance() + 0.05
        return maxOf(a, b) / minOf(a, b)
    }

    /** Every pairing the screens draw text in: (what, text, background). */
    private fun textPairs(t: DeskLinkColors): List<Triple<String, Color, Color>> {
        val backgrounds = listOf("bg" to t.bg, "surface" to t.surface, "surface2" to t.surface2)
        return backgrounds.flatMap { (name, bg) ->
            listOf(
                Triple("text on $name", t.text, bg),
                Triple("textDim on $name", t.textDim, bg),
                Triple("accentText on $name", t.accentText, bg),
            )
        } + listOf(
            // Status words ("Allowed", "3 need your OK") sit on the window or a card only.
            Triple("mintText on bg", t.mintText, t.bg),
            Triple("mintText on surface", t.mintText, t.surface),
            Triple("amber on bg", t.amber, t.bg),
            // Unpair and errors: on the window or a card, never on a sheet.
            Triple("red on bg", t.red, t.bg),
            Triple("red on surface", t.red, t.surface),
            Triple("amber on surface", t.amber, t.surface),
            Triple("onAccent on accent", t.onAccent, t.accent),
            Triple("onAccentContainer on accentContainer", t.onAccentContainer, t.accentContainer),
            Triple("text on accentContainer", t.text, t.accentContainer),
            Triple("coralText on coralContainer", t.coralText, t.coralContainer),
            Triple("coralText on surface", t.coralText, t.surface),
            Triple("text on coralContainer", t.text, t.coralContainer),
            Triple("onInverse on inverse", t.onInverse, t.inverse),
            Triple("terminalFg on terminalBg", t.terminalFg, t.terminalBg),
            // The terminal tiles' dim lines and "Running on …".
            Triple("terminal dim on terminalBg", Color(0xFF8B9099), t.terminalBg),
            Triple("text on fill over surface", t.text, t.fill.compositeOver(t.surface)),
        )
    }

    @Test
    fun everyTextPairMeetsAa() {
        for ((theme, t) in listOf("light" to LightTokens, "dark" to DarkTokens)) {
            for ((what, fg, bg) in textPairs(t)) {
                val ratio = contrast(fg, bg)
                assertTrue("$theme $what is ${"%.2f".format(ratio)}:1", ratio >= 4.5)
            }
        }
    }

    @Test
    fun fixedOverlayPairsMeetAa() {
        // The desk screen's amber notice and coral End, and the presenter's Next card.
        assertTrue(contrast(Color(0xFF2A1D00), Color(0xFFF5B84A)) >= 4.5)
        assertTrue(contrast(Color(0xFF2A0D06), Color(0xFFFF7C66)) >= 4.5)
        assertTrue(contrast(Color(0xFF14204F), Color(0xFFA9B8FF)) >= 4.5)
        assertTrue(contrast(Color(0xFFF2F2F3), Color(0xEB141416).compositeOver(Color.White)) >= 4.5)
    }

    @Test
    fun linkGlyphFollowsThePath() {
        assertEquals(LinkGlyphState.OFFLINE, linkGlyphState(DeviceConnection.OFFLINE, WebRtcConnectionPath.LAN))
        assertEquals(LinkGlyphState.CONNECTING, linkGlyphState(DeviceConnection.CONNECTING))
        assertEquals(LinkGlyphState.DIRECT, linkGlyphState(DeviceConnection.LOCAL))
        assertEquals(LinkGlyphState.DIRECT, linkGlyphState(DeviceConnection.CONNECTED))
        assertEquals(LinkGlyphState.RELAY, linkGlyphState(DeviceConnection.AWAY))
        // TURN is dashed even over a LAN bootstrap; a direct path is solid even via the relay.
        assertEquals(LinkGlyphState.RELAY, linkGlyphState(DeviceConnection.LOCAL, WebRtcConnectionPath.TURN))
        assertEquals(LinkGlyphState.DIRECT, linkGlyphState(DeviceConnection.AWAY, WebRtcConnectionPath.DIRECT))
        assertEquals(LinkGlyphState.DIRECT, linkGlyphState(DeviceConnection.AWAY, WebRtcConnectionPath.LAN))
    }

}
