/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.components

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.tokens
import org.desklink.mobile.webrtc.WebRtcConnectionPath

/** How the link glyph's connector is drawn. */
enum class LinkGlyphState {
    /** Solid: the same network, or a direct path. */
    DIRECT,

    /** Dashed: through the relay or a TURN server. */
    RELAY,

    /** Pulsing: a link exists, the feature transport is not up yet. */
    CONNECTING,

    /** Broken: not reachable. */
    OFFLINE,
}

/**
 * The connector for [connection]. The live WebRTC [path] wins once it is known: TURN is dashed
 * whatever the bootstrap link was, and a direct path is solid even when the relay introduced it.
 */
fun linkGlyphState(connection: DeviceConnection, path: WebRtcConnectionPath? = null): LinkGlyphState = when (connection) {
    DeviceConnection.OFFLINE -> LinkGlyphState.OFFLINE
    DeviceConnection.CONNECTING -> LinkGlyphState.CONNECTING
    else -> when (path) {
        WebRtcConnectionPath.TURN -> LinkGlyphState.RELAY
        WebRtcConnectionPath.LAN, WebRtcConnectionPath.DIRECT -> LinkGlyphState.DIRECT
        null -> if (connection == DeviceConnection.AWAY) LinkGlyphState.RELAY else LinkGlyphState.DIRECT
    }
}

/** Whether the person asked for less motion ("Remove animations": animator duration scale 0). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * The computer (a square), the connector and the phone (a pill), 26×11 at [scale] 1. Decorative
 * unless [contentDescription] is given: next to a text label the label says it already.
 */
@Composable
fun LinkGlyph(state: LinkGlyphState, modifier: Modifier = Modifier, scale: Float = 1f, contentDescription: String? = null) {
    val t = tokens
    val reduced = rememberReducedMotion()
    val pulse = if (state == LinkGlyphState.CONNECTING && !reduced) {
        val alpha by rememberInfiniteTransition(label = "link").animateFloat(
            1f, 0.25f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "pulse",
        )
        alpha
    } else {
        1f
    }
    val online = state == LinkGlyphState.DIRECT || state == LinkGlyphState.RELAY
    val computer = if (state == LinkGlyphState.OFFLINE) t.textFaint else t.text
    val phone = if (online) t.mint else t.textFaint
    val line = when (state) {
        LinkGlyphState.DIRECT, LinkGlyphState.RELAY -> t.mint
        LinkGlyphState.CONNECTING -> t.accent
        LinkGlyphState.OFFLINE -> t.textFaint
    }
    val semantics = if (contentDescription != null) Modifier.clearAndSetSemantics { this.contentDescription = contentDescription } else Modifier.clearAndSetSemantics { }
    Canvas(modifier.size(26.dp * scale, 11.dp * scale).then(semantics)) {
        val u = size.height / 11f
        val mid = size.height / 2f
        drawRoundRect(computer, Offset(0f, mid - 4.5f * u), Size(9f * u, 9f * u), CornerRadius(2.5f * u))
        val stroke = 1.5f * u
        val from = 9f * u
        val to = 20f * u
        when (state) {
            LinkGlyphState.RELAY -> drawLine(line, Offset(from, mid), Offset(to, mid), stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5f * u, 1.75f * u)))
            LinkGlyphState.OFFLINE -> {
                val third = (to - from) * 0.35f
                drawLine(line, Offset(from, mid), Offset(from + third, mid), stroke)
                drawLine(line, Offset(to - third, mid), Offset(to, mid), stroke)
            }
            else -> drawLine(line.copy(alpha = pulse), Offset(from, mid), Offset(to, mid), stroke)
        }
        drawRoundRect(phone, Offset(20f * u, 0f), Size(6f * u, 11f * u), CornerRadius(2f * u))
    }
}

/** The coral dot wherever a session is live (6dp). Decorative: its row says what is live. */
@Composable
fun PresenceDot(modifier: Modifier = Modifier, size: Dp = 6.dp, color: Color = tokens.coral) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** The small laptop of the device chip and the computer rows: a screen over a base, 21×14. */
@Composable
fun LaptopGlyph(color: Color, modifier: Modifier = Modifier) {
    Column(modifier.clearAndSetSemantics { }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(17.dp, 11.dp).border(1.6.dp, color, RoundedCornerShape(2.5.dp)))
        Box(Modifier.offset(y = 1.dp).size(21.dp, 2.dp).background(color, RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)))
    }
}

/** The status card's computer: a lit screen in a [frame] bezel on a base, 96×56. */
@Composable
fun LaptopIllustration(frame: Color, modifier: Modifier = Modifier) {
    Column(modifier.clearAndSetSemantics { }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(76.dp, 50.dp)
                .background(Brush.linearGradient(listOf(Color(0xFF4A5B86), Color(0xFF151A28))), RoundedCornerShape(7.dp))
                .border(3.dp, frame, RoundedCornerShape(7.dp)),
        )
        Box(Modifier.offset(y = (-1).dp).size(96.dp, 6.dp).background(frame, RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)))
    }
}

/** The design's switch: accent track when on, the neutral `sw` track and no outline when off. */
@Composable
fun deskSwitchColors(): SwitchColors {
    val t = tokens
    return SwitchDefaults.colors(
        checkedThumbColor = t.onAccent,
        checkedTrackColor = t.accent,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = Color.White,
        uncheckedTrackColor = t.switchOff,
        uncheckedBorderColor = Color.Transparent,
    )
}
