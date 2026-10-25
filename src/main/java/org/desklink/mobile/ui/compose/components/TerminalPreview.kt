/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.components

import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.plugins.terminal.TerminalScreen
import org.desklink.mobile.ui.compose.DeskLinkType
import org.desklink.mobile.ui.compose.tokens
import org.desklink.mobile.webrtc.terminal.TerminalLinkState
import org.desklink.mobile.webrtc.terminal.TerminalProtocol

/** A terminal session as a preview tile shows it. */
@Immutable
data class SessionPreview(
    val id: String,
    val name: String,
    /** ">_", "CC" or "CX". */
    val badge: String,
    /** Running, and the link to the computer is up. */
    val live: Boolean,
    /** The last lines on its screen; empty when this phone has not opened the session. */
    val lines: List<String>,
    val size: String,
)

object SessionPreviews {
    /** Where the terminal screen keeps the names people give sessions (see TerminalActivity). */
    private const val LABEL_PREFS = org.desklink.mobile.helpers.PerPeerState.LABELS

    /**
     * The sessions on [deviceId], read without side effects: only screens this phone already has
     * are read, and nothing is attached or created. Empty unless the feature transport is up.
     */
    fun of(context: Context, deviceId: String, lineCount: Int = 5): List<SessionPreview> {
        val app = DeskLinkApplication.getInstance()
        if (app.getDevice(deviceId)?.hasActiveWebRtcFeatureTransport != true) return emptyList()
        val client = app.terminalClient(deviceId) ?: return emptyList()
        val state = client.state.value
        val labels = context.getSharedPreferences(LABEL_PREFS, Context.MODE_PRIVATE)
        return state.sessions.filter { !it.gone }.map { session ->
            val screen = client.screenIfOpen(session.id) as? TerminalScreen
            val lines = screen?.let { s ->
                s.tailText(s.rows).lines().map(String::trimEnd).dropLastWhile(String::isBlank).takeLast(lineCount)
            }.orEmpty()
            SessionPreview(
                id = session.id,
                name = labels.getString(org.desklink.mobile.helpers.PerPeerState.labelKey(deviceId, session.id), null)
                    ?: agentName(context, session.title)
                    ?: session.title.ifBlank { session.id.take(8) },
                badge = badgeOf(session.title),
                live = session.exitStatus == null && state.link == TerminalLinkState.CONNECTED,
                lines = lines,
                size = context.getString(R.string.terminal_session_size, session.cols, session.rows),
            )
        }
    }

    fun badgeOf(title: String) = when (title) {
        TerminalProtocol.LAUNCHER_CLAUDE -> "CC"
        TerminalProtocol.LAUNCHER_CODEX -> "CX"
        else -> ">_"
    }

    fun agentName(context: Context, title: String): String? = when (title) {
        TerminalProtocol.LAUNCHER_CLAUDE -> context.getString(R.string.terminal_launcher_claude)
        TerminalProtocol.LAUNCHER_CODEX -> context.getString(R.string.terminal_launcher_codex)
        else -> null
    }
}

private val TileText = Color(0xFFE6E6E6)
private val TileDim = Color(0xFF8A8D96)
private val TileHairline = Color(0xFF262931)

/** A dark terminal tile: title, agent chip, live dot, and the last lines fading in at the top. */
@Composable
fun TerminalTile(
    preview: SessionPreview,
    modifier: Modifier = Modifier,
    menu: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val t = tokens
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = t.terminalBg,
        contentColor = TileText,
        border = BorderStroke(1.dp, TileHairline),
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabelChip(preview.badge)
                Spacer(Modifier.width(8.dp))
                Text(
                    preview.name,
                    style = DeskLinkType.cardTitle,
                    color = TileText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(preview.size, style = DeskLinkType.monoSmall, color = TileDim)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(7.dp).background(if (preview.live) t.mint else TileDim, CircleShape))
                menu?.invoke()
            }
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 76.dp)
                    // Older lines fade out toward the top, like a terminal scrolling up.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Black), blendMode = BlendMode.DstIn)
                    },
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (preview.lines.isEmpty()) {
                    Text(stringResource(R.string.terminal_preview_open), style = DeskLinkType.monoSmall, color = TileDim, maxLines = 1)
                } else {
                    preview.lines.forEach { line ->
                        Text(line, style = DeskLinkType.monoSmall, color = TileText, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false)
                    }
                }
            }
        }
    }
}

/** No sessions yet: a dark tile with a blinking coral cursor and the three ways to start one. */
@Composable
fun EmptyTerminalTile(launchers: List<Pair<String, () -> Unit>>, enabled: Boolean, modifier: Modifier = Modifier) {
    val t = tokens
    val blink by rememberInfiniteTransition(label = "cursor").animateFloat(
        1f, 0f, infiniteRepeatable(tween(530), RepeatMode.Reverse), label = "blink",
    )
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = t.terminalBg,
        contentColor = TileText,
        border = BorderStroke(1.dp, TileHairline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$", style = DeskLinkType.mono, color = t.mint)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(width = 8.dp, height = 15.dp).alpha(if (blink > 0.5f) 1f else 0f).background(t.coral))
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.terminal_empty_hint), style = DeskLinkType.cardTitle, color = TileText)
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                launchers.forEach { (label, start) ->
                    OutlinedButton(
                        onClick = start,
                        enabled = enabled,
                        border = BorderStroke(1.dp, Color(0xFF33363F)),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = TileText, disabledContentColor = TileDim),
                    ) { Text(label) }
                }
            }
        }
    }
}

/** The home card's strip: each session as a small mono chip on terminal black. */
@Composable
fun SessionStrip(sessions: List<SessionPreview>, modifier: Modifier = Modifier) {
    val t = tokens
    FlowRow(
        modifier
            .fillMaxWidth()
            .background(t.terminalBg, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        sessions.take(4).forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (s.live) t.mint else TileDim, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("${s.badge}  ${s.name}", style = DeskLinkType.monoSmall, color = TileText, maxLines = 1)
            }
        }
        if (sessions.size > 4) Text("+${sessions.size - 4}", style = DeskLinkType.monoSmall, color = TileDim)
    }
}
