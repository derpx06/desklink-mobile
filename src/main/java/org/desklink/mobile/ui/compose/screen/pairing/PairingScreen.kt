/*
 * SPDX-FileCopyrightText: 2026 Saul Cintero Chocarro <scintero@gmail.com>
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.pairing

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.DeskLinkType
import org.desklink.mobile.ui.compose.components.DeskCard
import org.desklink.mobile.ui.compose.components.DeskLinkThemePreviews
import org.desklink.mobile.ui.compose.components.GlyphSquircle
import org.desklink.mobile.ui.compose.components.MonoStatusLine
import org.desklink.mobile.ui.compose.components.SectionHeader
import org.desklink.mobile.ui.compose.components.SessionStrip
import org.desklink.mobile.ui.compose.components.batteryText
import org.desklink.mobile.ui.compose.components.connectionPath
import org.desklink.mobile.ui.compose.components.rememberHaptics
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.model.device.DeviceUiModel
import org.desklink.mobile.ui.compose.tokens

@Composable
fun PairingScreen(
    uiState: PairingUiState,
    onClick: (String) -> Unit,
    onScanQr: () -> Unit = {},
    onWifiSettingsClick: () -> Unit = {},
    onNotificationSettingsClick: () -> Unit = {},
    onRefresh: () -> Unit = {}
) {
    val paired = uiState.connected + uiState.remembered
    val firstRun = paired.isEmpty() && uiState.available.isEmpty()
    val haptic = rememberHaptics()
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = uiState.isRefreshing, onRefresh = onRefresh) {
            LazyColumn(
                // Lets the app bar lift as the list scrolls under it.
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(rememberNestedScrollInteropConnection()),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    // Room for the floating button over the last card.
                    bottom = 104.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Hints(uiState, onWifiSettingsClick, onNotificationSettingsClick) }
                if (firstRun) {
                    item { Welcome(onScanQr) }
                }
                if (paired.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.home_your_computers)) }
                    items(paired, key = { it.id }) { device -> DeviceCard(device) { onClick(device.id) } }
                }
                if (uiState.available.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.home_nearby)) }
                    items(uiState.available, key = { it.id }) { device -> DeviceCard(device) { onClick(device.id) } }
                }
            }
        }
        if (!firstRun) {
            ExtendedFloatingActionButton(
                onClick = { haptic(HapticFeedbackType.ContextClick); onScanQr() },
                icon = { Icon(painterResource(R.drawable.sym_qr_code_scanner), contentDescription = null) },
                text = { Text(stringResource(R.string.home_pair_computer)) },
                containerColor = tokens.accent,
                contentColor = tokens.onAccent,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun Hints(
    uiState: PairingUiState,
    onWifiSettingsClick: () -> Unit,
    onNotificationSettingsClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        if (uiState.hasDuplicateNames) {
            Hint(R.drawable.sym_warning, stringResource(R.string.pairing_duplicate_names))
        }
        val someDevicesReachable = uiState.available.isNotEmpty() || uiState.connected.isNotEmpty()
        when {
            !someDevicesReachable && !uiState.isWifiAvailable ->
                Hint(R.drawable.sym_wifi, stringResource(R.string.no_wifi), onWifiSettingsClick)
            !uiState.hasNotificationsPermission ->
                Hint(R.drawable.sym_notifications, stringResource(R.string.no_notifications), onNotificationSettingsClick)
            !uiState.isTrustedNetwork ->
                Hint(R.drawable.sym_shield, stringResource(R.string.on_non_trusted_message))
        }
    }
}

@Composable
private fun Hint(@DrawableRes icon: Int, text: String, onClick: (() -> Unit)? = null) {
    DeskCard(onClick = onClick, color = tokens.surface2) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, tint = tokens.textDim, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.textDim)
        }
    }
}

/** First run: a confident line about what DeskLink is, the one action, and what it looks like. */
@Composable
private fun Welcome(onScanQr: () -> Unit) {
    val t = tokens
    val haptic = rememberHaptics()
    Column(Modifier.fillMaxWidth().padding(top = 32.dp)) {
        Box(
            Modifier
                .size(72.dp)
                .background(Color(0xFF14213D), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.desklink_mark), contentDescription = null, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.home_welcome_title), style = DeskLinkType.hero)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.home_welcome_body), style = MaterialTheme.typography.bodyLarge, color = t.textDim)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { haptic(HapticFeedbackType.ContextClick); onScanQr() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Icon(painterResource(R.drawable.sym_qr_code_scanner), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.home_pair_computer))
        }
        Spacer(Modifier.height(32.dp))
        // What pairing looks like, as the product shows it: a terminal.
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = t.terminalBg,
            border = BorderStroke(1.dp, Color(0xFF262931)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text("$ ", style = DeskLinkType.mono, color = t.mint)
                    Text("desklink pair", style = DeskLinkType.mono, color = Color(0xFFE6E6E6))
                }
                Text(stringResource(R.string.home_welcome_line2), style = DeskLinkType.mono, color = Color(0xFF8A8D96))
                Row {
                    Text("✓ ", style = DeskLinkType.mono, color = t.mint)
                    Text(stringResource(R.string.home_welcome_line3), style = DeskLinkType.mono, color = Color(0xFFE6E6E6))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.home_same_wifi_hint), style = MaterialTheme.typography.bodySmall, color = t.textFaint)
    }
}

/** A computer: its glyph, its name, its mono status, and its terminal sessions when it has any. */
@Composable
private fun DeviceCard(device: DeviceUiModel, onClick: () -> Unit) {
    val offline = device.isPaired && device.connection == DeviceConnection.OFFLINE
    DeskCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphSquircle(device.icon, size = 48.dp, dimmed = offline || !device.isPaired)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        device.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (device.isPaired || device.wantsToPair) {
                        MonoStatusLine(
                            device.connection,
                            listOfNotNull(connectionPath(device.connection), batteryText(device.batteryPercent, device.charging)),
                            wantsToPair = device.wantsToPair,
                        )
                    } else {
                        Text(stringResource(R.string.home_nearby_summary), style = DeskLinkType.mono, color = tokens.textDim)
                    }
                    if (device.summaryRes > 0) {
                        Text(stringResource(device.summaryRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (!device.isPaired) {
                    TextButton(onClick = onClick) { Text(stringResource(R.string.home_pair)) }
                }
            }
            if (device.sessions.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                SessionStrip(device.sessions)
            }
        }
    }
}

@DeskLinkThemePreviews
@Composable
private fun PreviewCompose() {
    DeskLinkTheme(context = LocalContext.current) {
        PairingScreen(
            uiState = PairingUiState(
                isWifiAvailable = true,
                hasNotificationsPermission = true,
                isTrustedNetwork = true,
                hasDuplicateNames = false,
                connected = listOf(
                    DeviceUiModel(
                        id = "a",
                        icon = R.drawable.ic_device_laptop_32dp,
                        name = "Workstation",
                        summaryRes = 0,
                        isReachable = true,
                        isPaired = true,
                        connection = DeviceConnection.LOCAL,
                        batteryPercent = 82,
                    ),
                ),
                available = emptyList(),
                remembered = emptyList(),
            ),
            onClick = {},
        )
    }
}
