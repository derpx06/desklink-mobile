/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.components

import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkType
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.tokens

// ---- surfaces -------------------------------------------------------------------------------------

/**
 * The DeskLink card: the surface token, a 1px hairline, radius 16; a soft shadow in light only
 * (dark separates by surface steps). Pressed, the hairline turns accent over 120 ms.
 */
@Composable
fun DeskCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = tokens.surface,
    border: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = tokens
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val edge by animateColorAsState(border ?: if (pressed) t.accent else t.hairline, tween(120), label = "cardEdge")
    val lifted = if (t.isDark) modifier else modifier.shadow(6.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.05f), spotColor = Color.Black.copy(alpha = 0.08f))
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = color, border = BorderStroke(1.dp, edge), interactionSource = interaction, modifier = lifted) {
            Column(content = content)
        }
    } else {
        Surface(shape = shape, color = color, border = BorderStroke(1.dp, edge), modifier = lifted) { Column(content = content) }
    }
}

/** A divider between rows inside a card. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(tokens.hairline))
}

/** A glyph on the raised surface in a squircle with a hairline: devices, empty states. */
@Composable
fun GlyphSquircle(@DrawableRes icon: Int, modifier: Modifier = Modifier, size: Dp = 44.dp, dimmed: Boolean = false) {
    val t = tokens
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier
            .size(size)
            .background(t.surface2, shape)
            .border(1.dp, t.hairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(if (icon > 0) icon else R.drawable.ic_device_desktop_32dp),
            contentDescription = null,
            tint = if (dimmed) t.textFaint else t.text,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** An accent glyph on the accent's 14% tint: feature cards. */
@Composable
fun AccentSquircle(@DrawableRes icon: Int, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val t = tokens
    Box(
        modifier
            .size(size)
            .background(t.accent.copy(alpha = 0.14f), MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = t.accent, modifier = Modifier.size(size * 0.56f))
    }
}

// ---- status ---------------------------------------------------------------------------------------

/** The path a connected device is reached by, in the words both apps use; null when there is none to name. */
@Composable
fun connectionPath(connection: DeviceConnection): String? = when (connection) {
    DeviceConnection.LOCAL -> stringResource(R.string.connection_local)
    DeviceConnection.AWAY -> stringResource(R.string.remote_path_direct)
    else -> null
}

private fun DeviceConnection.isConnected() =
    this == DeviceConnection.LOCAL || this == DeviceConnection.AWAY || this == DeviceConnection.CONNECTED

/** "Connected" / "Connecting…" / "Offline" / "Wants to pair", and its color. */
@Composable
fun statusOf(connection: DeviceConnection, wantsToPair: Boolean = false): Pair<String, Color> {
    val t = tokens
    return when {
        wantsToPair -> stringResource(R.string.connection_wants_to_pair) to t.amber
        connection.isConnected() -> stringResource(R.string.connection_connected) to t.mint
        connection == DeviceConnection.CONNECTING -> stringResource(R.string.connection_connecting) to t.accent
        connection == DeviceConnection.UNAVAILABLE -> stringResource(R.string.connection_unavailable) to t.amber
        else -> stringResource(R.string.connection_offline) to t.textFaint
    }
}

/** The "● Connected" pill: the status color's dot and text on its 12% tint. */
@Composable
fun StatusPill(connection: DeviceConnection, modifier: Modifier = Modifier, wantsToPair: Boolean = false) {
    val (label, color) = statusOf(connection, wantsToPair)
    val tint by animateColorAsState(color, tween(150), label = "pill")
    Row(
        modifier
            .background(tint.copy(alpha = 0.12f), CircleShape)
            .padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(tint, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/** The mono status line of a device row: "● Connected · Local network · 82%". */
@Composable
fun MonoStatusLine(
    connection: DeviceConnection,
    meta: List<String>,
    modifier: Modifier = Modifier,
    wantsToPair: Boolean = false,
) {
    val (label, color) = statusOf(connection, wantsToPair)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(
            (listOf(label) + meta).joinToString(" · "),
            style = DeskLinkType.mono,
            color = tokens.textDim,
            maxLines = 2,
        )
    }
}

@Composable
fun batteryText(percent: Int?, charging: Boolean): String? =
    percent?.let { stringResource(if (charging) R.string.battery_percent_charging else R.string.battery_percent, it) }

// ---- structure ------------------------------------------------------------------------------------

/** A section's title (13/600, dim, sentence case) with an optional action on the right. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 28.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = DeskLinkType.section,
            color = tokens.textDim,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        action()
    }
}

/** A small mono label chip: an agent's ">_", "CC", "CX". */
@Composable
fun LabelChip(text: String, modifier: Modifier = Modifier, onDark: Boolean = true) {
    val t = tokens
    Text(
        text,
        style = DeskLinkType.monoSmall,
        color = if (onDark) Color(0xFFB8BBC4) else t.textDim,
        modifier = modifier
            .background(if (onDark) Color.White.copy(alpha = 0.08f) else t.surface2, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Empty and error states: an icon in a raised squircle, a 17/600 title, a dim line, one action. */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlyphSquircle(icon, size = 56.dp)
        Spacer(Modifier.height(20.dp))
        Text(title, style = DeskLinkType.stateTitle, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = tokens.textDim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        action()
    }
}

/** A soft moving sheen for loading placeholders; still when the system turns animations off. */
fun Modifier.shimmer(): Modifier = composed {
    val t = tokens
    val context = LocalContext.current
    // Still when "Remove animations" (animator duration scale 0) is on.
    val enabled = remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
    if (!enabled) return@composed background(t.surface2)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    background(
        Brush.linearGradient(
            listOf(t.surface2, t.hairline, t.surface2),
            start = Offset(x * 900f - 300f, 0f),
            end = Offset(x * 900f, 300f),
        ),
    )
}
