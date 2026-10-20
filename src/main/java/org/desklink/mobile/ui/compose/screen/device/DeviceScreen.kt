/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.device

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.desklink.mobile.PairingHandler.PairState
import org.desklink.mobile.R
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.ui.compose.DeskLinkType
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.components.AccentSquircle
import org.desklink.mobile.ui.compose.components.DeskCard
import org.desklink.mobile.ui.compose.components.EmptyState
import org.desklink.mobile.ui.compose.components.EmptyTerminalTile
import org.desklink.mobile.ui.compose.components.GlyphSquircle
import org.desklink.mobile.ui.compose.components.Hairline
import org.desklink.mobile.ui.compose.components.SectionHeader
import org.desklink.mobile.ui.compose.components.SessionPreview
import org.desklink.mobile.ui.compose.components.StatusPill
import org.desklink.mobile.ui.compose.components.TerminalTile
import org.desklink.mobile.ui.compose.components.batteryText
import org.desklink.mobile.ui.compose.components.connectionPath
import org.desklink.mobile.ui.compose.components.connectionStatsLabel
import org.desklink.mobile.ui.compose.components.rememberConnectionStats
import org.desklink.mobile.ui.compose.components.rememberHaptics
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.tokens

/** One feature: what it says, its glyph, whether it opens a screen, and its one-line description. */
data class FeatureRow(
    val title: String,
    @DrawableRes val icon: Int,
    val opensScreen: Boolean,
    val onClick: (android.app.Activity) -> Unit,
    val description: String? = null,
)

/** What is running right now between the two devices, for the banner that can end it. */
data class ActiveSession(val screen: Boolean, val terminals: Int)

data class DeviceScreenState(
    val deviceId: String,
    val name: String,
    @DrawableRes val icon: Int,
    val pairState: PairState,
    val reachable: Boolean,
    val connection: DeviceConnection,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val verificationKey: String? = null,
    val pairMessage: String? = null,
    /** "3F2A 91C0…", the start of the computer's certificate fingerprint. */
    val fingerprint: String? = null,
    /** The pill buttons under the name: Ring, Lock, Ping, Send files. */
    val quickActions: List<FeatureRow> = emptyList(),
    val features: List<FeatureRow> = emptyList(),
    /** Whether the terminal channel is up, so sessions can be listed and started. */
    val terminalReady: Boolean = false,
    val sessions: List<SessionPreview> = emptyList(),
    /** Agent CLIs the computer can start besides a shell, as (name, command). */
    val launchers: List<Pair<String, String>> = emptyList(),
    val needPermissions: List<Plugin> = emptyList(),
    val optionalPermissions: List<Plugin> = emptyList(),
    val activeSession: ActiveSession? = null,
    val isRefreshing: Boolean = false,
)

class DeviceScreenActions(
    val onRow: (FeatureRow) -> Unit,
    val onOpenSession: (String) -> Unit,
    /** Null starts the login shell; otherwise the launcher's command. */
    val onNewSession: (String?) -> Unit,
    val onRequiredPermission: (Plugin) -> Unit,
    val onOptionalPermission: (Plugin) -> Unit,
    val onRequestPairing: () -> Unit,
    val onAcceptPairing: () -> Unit,
    val onRejectPairing: () -> Unit,
    val onCancelPairing: () -> Unit,
    val onRefresh: () -> Unit,
    val onEncryptionInfo: () -> Unit,
    val onFeatureSettings: () -> Unit,
    val onUnpair: () -> Unit,
    val onEndSession: () -> Unit,
    /** Whether the hero (and so the device name) has scrolled out of view. */
    val onHeroHidden: (Boolean) -> Unit,
)

@Composable
fun DeviceScreen(state: DeviceScreenState, actions: DeviceScreenActions) {
    if (state.pairState != PairState.Paired) {
        PairingContent(state, actions)
        return
    }
    val scroll = rememberScrollState()
    val heroPx = with(LocalDensity.current) { 180.dp.toPx() }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value > heroPx }.distinctUntilChanged().collect(actions.onHeroHidden)
    }
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .nestedScroll(rememberNestedScrollInteropConnection())
                .verticalScroll(scroll)
                .padding(bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            HeroBand(state, actions)
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (state.reachable) {
                    state.activeSession?.let { ActiveSessionBanner(it, actions.onEndSession) }
                    if (state.terminalReady) TerminalsSection(state, actions)
                    if (state.features.isNotEmpty()) {
                        SectionHeader(stringResource(R.string.device_section_features))
                        FeatureGrid(state.features, actions.onRow)
                    }
                    PermissionSection(R.string.device_section_permissions, state.needPermissions, actions.onRequiredPermission)
                    PermissionSection(R.string.device_section_optional_permissions, state.optionalPermissions, actions.onOptionalPermission)
                    ConnectionSection(state, actions)
                } else {
                    EmptyState(
                        icon = R.drawable.sym_cloud_off,
                        title = stringResource(R.string.device_offline_title),
                        body = stringResource(R.string.device_offline_body),
                    ) {
                        FilledTonalButton(onClick = actions.onRefresh) { Text(stringResource(R.string.device_try_again)) }
                    }
                }
                ManageSection(state, actions)
            }
        }
    }
}

// ---- hero ---------------------------------------------------------------------------------------

/**
 * The hero card, as on the desktop: glyph, name, status pill and mono meta over a very soft glow
 * of the accent, and the quick actions as round icon buttons.
 */
@Composable
private fun HeroBand(state: DeviceScreenState, actions: DeviceScreenActions) {
    val t = tokens
    val glow = t.accent.copy(alpha = if (t.isDark) 0.10f else 0.06f)
    val stats = if (state.reachable) rememberConnectionStats(state.deviceId) else null
    val haptic = rememberHaptics()
    DeskCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            listOf(glow, Color.Transparent),
                            center = Offset(size.width * 0.15f, size.height * 0.2f),
                            radius = size.width * 0.8f,
                        ),
                    )
                }
                .padding(20.dp),
        ) {
            GlyphSquircle(state.icon, size = 56.dp, dimmed = !state.reachable)
            Spacer(Modifier.height(16.dp))
            Text(state.name, style = DeskLinkType.hero)
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                StatusPill(state.connection)
                val meta = listOfNotNull(
                    stats?.let { connectionStatsLabel(it) } ?: connectionPath(state.connection),
                    batteryText(state.batteryPercent, state.charging),
                )
                if (meta.isNotEmpty() && state.reachable) Text(meta.joinToString(" · "), style = DeskLinkType.mono, color = t.textDim)
            }
            if (state.reachable && state.quickActions.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.quickActions.forEach { row ->
                        Box(
                            Modifier
                                .size(44.dp)
                                .background(t.surface2, CircleShape)
                                .border(1.dp, t.hairline, CircleShape)
                                .clip(CircleShape)
                                .clickable(onClickLabel = row.title) { haptic(HapticFeedbackType.ContextClick); actions.onRow(row) }
                                .semantics { contentDescription = row.title },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(row.icon), contentDescription = null, tint = t.text, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---- the terminal-first section -------------------------------------------------------------------

@Composable
private fun TerminalsSection(state: DeviceScreenState, actions: DeviceScreenActions) {
    SectionHeader(stringResource(R.string.terminal_section)) {
        NewSessionButton(state.launchers, actions.onNewSession)
    }
    val shell = stringResource(R.string.terminal_launch_shell)
    when (state.sessions.size) {
        0 -> EmptyTerminalTile(
            launchers = state.launchers.map { (name, command) -> name to { actions.onNewSession(command) } } + (shell to { actions.onNewSession(null) }),
            enabled = true,
        )
        1 -> TerminalTile(state.sessions.first(), Modifier.fillMaxWidth()) { actions.onOpenSession(state.sessions.first().id) }
        else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.sessions, key = { it.id }) { preview ->
                TerminalTile(preview, Modifier.width(264.dp)) { actions.onOpenSession(preview.id) }
            }
        }
    }
}

/** "New session" starts a shell; the arrow beside it offers the agents the computer has. */
@Composable
private fun NewSessionButton(launchers: List<Pair<String, String>>, onNewSession: (String?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val t = tokens
    val haptic = rememberHaptics()
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(t.surface2)
            .border(1.dp, t.hairline, RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.terminal_new_session),
            style = MaterialTheme.typography.labelLarge,
            color = t.text,
            modifier = Modifier
                .clickable { haptic(HapticFeedbackType.ContextClick); onNewSession(null) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        if (launchers.isNotEmpty()) {
            Box(Modifier.width(1.dp).height(20.dp).background(t.hairlineStrong))
            Box {
                Icon(
                    painterResource(R.drawable.sym_expand_more),
                    contentDescription = stringResource(R.string.terminal_new_session_choose),
                    tint = t.text,
                    modifier = Modifier
                        .clickable { menu = true }
                        .padding(horizontal = 8.dp, vertical = 10.dp)
                        .size(20.dp),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.terminal_launch_shell)) }, onClick = { menu = false; onNewSession(null) })
                    launchers.forEach { (name, command) ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { menu = false; onNewSession(command) })
                    }
                }
            }
        }
    }
}

// ---- features -----------------------------------------------------------------------------------

/** The features as cards, two to a row, every card in a row as tall as the tallest. */
@Composable
private fun FeatureGrid(features: List<FeatureRow>, onRow: (FeatureRow) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        features.chunked(2).forEach { pair ->
            Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { FeatureCard(it, Modifier.weight(1f).fillMaxHeight(), onRow) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FeatureCard(row: FeatureRow, modifier: Modifier, onRow: (FeatureRow) -> Unit) {
    val haptic = rememberHaptics()
    DeskCard(onClick = { haptic(HapticFeedbackType.ContextClick); onRow(row) }, modifier = modifier) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AccentSquircle(row.icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.title, style = DeskLinkType.cardTitle, maxLines = 2)
                row.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = tokens.textDim, maxLines = 2)
                }
            }
        }
    }
}

// ---- active session -----------------------------------------------------------------------------

/** What is running now, with the one way to stop it. */
@Composable
private fun ActiveSessionBanner(session: ActiveSession, onEnd: () -> Unit) {
    val t = tokens
    var confirm by remember { mutableStateOf(false) }
    val parts = buildList {
        if (session.screen) add(stringResource(R.string.active_session_screen))
        if (session.terminals > 0) add(pluralStringResource(R.plurals.active_session_terminals, session.terminals, session.terminals))
    }
    Row(
        Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .background(t.accent.copy(alpha = 0.10f), MaterialTheme.shapes.medium)
            .border(1.dp, t.accent.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccentSquircle(R.drawable.sym_cast)
        Spacer(Modifier.width(12.dp))
        Text(parts.joinToString(" · "), style = DeskLinkType.mono, color = t.text, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { confirm = true }, border = BorderStroke(1.dp, t.accent)) {
            Text(stringResource(R.string.active_session_end), color = t.accent)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.active_session_end_title)) },
            text = { Text(stringResource(R.string.active_session_end_message)) },
            confirmButton = {
                TextButton(
                    onClick = { confirm = false; onEnd() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.active_session_end)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ---- lists as cards -------------------------------------------------------------------------------

/** A group of rows on one card, hairlines between them. */
@Composable
private fun CardList(rows: List<@Composable () -> Unit>) {
    DeskCard(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row ->
            if (index > 0) Hairline(Modifier.padding(start = 56.dp))
            row()
        }
    }
}

@Composable
private fun CardRow(
    @DrawableRes icon: Int,
    title: String,
    value: String? = null,
    mono: Boolean = false,
    danger: Boolean = false,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val t = tokens
    val tint = if (danger) MaterialTheme.colorScheme.error else t.textDim
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (danger) MaterialTheme.colorScheme.error else t.text)
            if (value != null) Text(value, style = if (mono) DeskLinkType.mono else MaterialTheme.typography.bodyMedium, color = t.textDim, maxLines = 2)
        }
        if (chevron) Icon(painterResource(R.drawable.sym_chevron_right), contentDescription = null, tint = t.textFaint)
    }
}

@Composable
private fun PermissionSection(title: Int, plugins: List<Plugin>, onClick: (Plugin) -> Unit) {
    if (plugins.isEmpty()) return
    SectionHeader(stringResource(title))
    val hint = stringResource(R.string.device_tap_permission)
    CardList(plugins.map { plugin -> { CardRow(R.drawable.sym_info, plugin.displayName, hint, chevron = true) { onClick(plugin) } } })
}

@Composable
private fun ConnectionSection(state: DeviceScreenState, actions: DeviceScreenActions) {
    SectionHeader(stringResource(R.string.device_section_connection))
    val stats = rememberConnectionStats(state.deviceId)
    val via = stats?.let { connectionStatsLabel(it) } ?: connectionPath(state.connection) ?: stringResource(R.string.connection_connecting)
    val encryption = stringResource(R.string.device_encryption)
    val fingerprint = state.fingerprint?.let { stringResource(R.string.device_fingerprint_short, it) }
    val connectedVia = stringResource(R.string.device_connected_via)
    CardList(
        listOf(
            { CardRow(R.drawable.sym_wifi, connectedVia, via, mono = true) },
            { CardRow(R.drawable.sym_verified_user, encryption, fingerprint, mono = true, chevron = true, onClick = actions.onEncryptionInfo) },
        ),
    )
}

@Composable
private fun ManageSection(state: DeviceScreenState, actions: DeviceScreenActions) {
    var confirmUnpair by remember { mutableStateOf(false) }
    SectionHeader(stringResource(R.string.device_section_manage))
    val features = stringResource(R.string.device_features)
    val unpair = stringResource(R.string.device_menu_unpair)
    CardList(
        listOf(
            { CardRow(R.drawable.sym_tune, features, chevron = true, onClick = actions.onFeatureSettings) },
            { CardRow(R.drawable.sym_link_off, unpair, danger = true, onClick = { confirmUnpair = true }) },
        ),
    )
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(stringResource(R.string.device_unpair_title, state.name)) },
            text = { Text(stringResource(R.string.device_unpair_message)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmUnpair = false; actions.onUnpair() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(unpair) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ---- not paired -------------------------------------------------------------------------------

/** The pairing ritual both apps share: one headline, one instruction, the code, Reject then Accept. */
@Composable
private fun PairingContent(state: DeviceScreenState, actions: DeviceScreenActions) {
    val t = tokens
    val haptic = rememberHaptics()
    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        GlyphSquircle(state.icon, size = 64.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            when (state.pairState) {
                PairState.Requested -> stringResource(R.string.pair_title_waiting, state.name)
                else -> stringResource(R.string.pair_title_request, state.name)
            },
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                when (state.pairState) {
                    PairState.RequestedByPeer -> R.string.pair_code_hint
                    PairState.Requested -> R.string.pair_waiting_hint
                    else -> R.string.pair_request_hint
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = t.textDim,
            textAlign = TextAlign.Center,
        )
        state.pairMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        val key = state.verificationKey
        if (key != null && state.pairState != PairState.NotPaired) {
            Spacer(Modifier.height(32.dp))
            val description = stringResource(R.string.pair_code_description, key.toList().joinToString(" "))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(t.surface2, MaterialTheme.shapes.medium)
                    .border(1.dp, t.hairline, MaterialTheme.shapes.medium)
                    .padding(vertical = 24.dp)
                    .clearAndSetSemantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    key.chunked(3).joinToString(" "),
                    fontFamily = MonoFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 40.sp,
                    letterSpacing = 4.sp,
                    color = t.text,
                )
            }
        }
        if (state.pairState == PairState.Requested) {
            Spacer(Modifier.height(28.dp))
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            when (state.pairState) {
                PairState.RequestedByPeer -> {
                    OutlinedButton(
                        onClick = { haptic(HapticFeedbackType.Reject); actions.onRejectPairing() },
                        border = BorderStroke(1.dp, t.hairlineStrong),
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    ) { Text(stringResource(R.string.pairing_reject), color = t.text) }
                    Button(
                        onClick = { haptic(HapticFeedbackType.Confirm); actions.onAcceptPairing() },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    ) { Text(stringResource(R.string.pairing_accept)) }
                }
                PairState.Requested -> OutlinedButton(onClick = actions.onCancelPairing, border = BorderStroke(1.dp, t.hairlineStrong)) {
                    Text(stringResource(R.string.cancel), color = t.text)
                }
                else -> Button(
                    onClick = { haptic(HapticFeedbackType.ContextClick); actions.onRequestPairing() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text(stringResource(R.string.request_pairing)) }
            }
        }
    }
}
