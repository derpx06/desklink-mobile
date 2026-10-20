/*
 * SPDX-FileCopyrightText: 2026 Saul Cintero Chocarro <scintero@gmail.com>
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.pairing

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.components.BatteryBadge
import org.desklink.mobile.ui.compose.components.ConnectionPill
import org.desklink.mobile.ui.compose.components.connectionDescription
import org.desklink.mobile.ui.compose.components.DeskLinkThemePreviews
import org.desklink.mobile.ui.compose.components.DeviceAvatar
import org.desklink.mobile.ui.compose.components.HintCard
import org.desklink.mobile.ui.compose.components.SectionTitle
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.model.device.DeviceUiModel

/** One-tap shortcuts on a device card. */
enum class QuickAction { TERMINAL, SCREEN, FILES }

@Composable
fun PairingScreen(
    uiState: PairingUiState,
    onClick: (String) -> Unit,
    onQuickAction: (deviceId: String, action: QuickAction) -> Unit = { _, _ -> },
    onScanQr: () -> Unit = {},
    onWifiSettingsClick: () -> Unit = {},
    onNotificationSettingsClick: () -> Unit = {},
    onDuplicateNamesClick: () -> Unit = {},
    onRefresh: () -> Unit = {}
) {
    val paired = uiState.connected + uiState.remembered
    PullToRefreshBox(isRefreshing = uiState.isRefreshing, onRefresh = onRefresh) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Hints(uiState, onWifiSettingsClick, onNotificationSettingsClick, onDuplicateNamesClick) }

            if (paired.isEmpty()) {
                item { EmptyHero(onScanQr) }
            } else {
                item { SectionTitle(stringResource(R.string.home_your_computers)) }
                items(paired, key = { it.id }) { device ->
                    DeviceCard(device, onClick = { onClick(device.id) }, onQuickAction = { onQuickAction(device.id, it) })
                }
            }

            if (uiState.available.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.home_ready_to_pair)) }
                items(uiState.available, key = { it.id }) { device ->
                    NearbyCard(device, onClick = { onClick(device.id) })
                }
            }

            if (paired.isNotEmpty()) {
                item { AddAnotherCard(onScanQr) }
            }
        }
    }
}

@Composable
private fun Hints(
    uiState: PairingUiState,
    onWifiSettingsClick: () -> Unit,
    onNotificationSettingsClick: () -> Unit,
    onDuplicateNamesClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (uiState.hasDuplicateNames) {
            HintCard(R.drawable.ic_warning, stringResource(R.string.pairing_duplicate_names), warning = true)
        }
        val someDevicesReachable = uiState.available.isNotEmpty() || uiState.connected.isNotEmpty()
        when {
            !someDevicesReachable && !uiState.isWifiAvailable ->
                HintCard(R.drawable.ic_wifi, stringResource(R.string.no_wifi), onClick = onWifiSettingsClick)
            !uiState.hasNotificationsPermission ->
                HintCard(R.drawable.ic_notifications_24dp, stringResource(R.string.no_notifications), warning = true, onClick = onNotificationSettingsClick)
            !uiState.isTrustedNetwork ->
                HintCard(R.drawable.ic_shield_24dp, stringResource(R.string.on_non_trusted_message))
        }
    }
}

/** First run: what DeskLink is for, and the one thing to do next. */
@Composable
private fun EmptyHero(onScanQr: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(listOf(colors.primaryContainer.copy(alpha = 0.55f), colors.surfaceContainerLow)),
                )
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandTile()
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.home_empty_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.home_empty_body),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onScanQr,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(painterResource(R.drawable.ic_qr_code_scanner_24dp), contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.qr_scan_to_pair), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_wifi),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.home_same_wifi_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** The DeskLink mark on its navy tile, as on the launcher icon. */
@Composable
private fun BrandTile() {
    Box(
        modifier = Modifier
            .size(88.dp)
            .background(Color(0xFF14213D), RoundedCornerShape(26.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.desklink_mark), contentDescription = null, modifier = Modifier.size(52.dp))
    }
}

@Composable
private fun DeviceCard(device: DeviceUiModel, onClick: () -> Unit, onQuickAction: (QuickAction) -> Unit) {
    val online = device.connection != DeviceConnection.OFFLINE
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceAvatar(device.icon, online)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        device.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        connectionDescription(device.connection),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        ConnectionPill(device.connection)
                        device.batteryPercent?.let { BatteryBadge(it, device.charging) }
                    }
                }
                Icon(
                    painterResource(R.drawable.ic_chevron_right_24dp),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (device.summaryRes > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(device.summaryRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val actions = buildList {
                if (device.canOpenTerminal) add(Triple(QuickAction.TERMINAL, R.drawable.ic_terminal_24dp, R.string.terminal_open))
                if (device.canControlScreen) add(Triple(QuickAction.SCREEN, R.drawable.ic_desktop_24dp, R.string.remote_mode_screen))
                if (device.canSendFiles) add(Triple(QuickAction.FILES, R.drawable.ic_upload_24dp, R.string.home_action_files))
            }
            if (actions.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEach { (action, icon, label) ->
                        QuickActionButton(icon, stringResource(label)) { onQuickAction(action) }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(start = 14.dp, end = 18.dp),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun NearbyCard(device: DeviceUiModel, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(device.icon, online = false, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(if (device.summaryRes > 0) device.summaryRes else R.string.home_nearby_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (device.summaryRes > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.home_pair))
            }
        }
    }
}

@Composable
private fun AddAnotherCard(onScanQr: () -> Unit) {
    Surface(
        onClick = onScanQr,
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_qr_code_scanner_24dp), contentDescription = null, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_add_another), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.qr_scan_prompt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                        canOpenTerminal = true,
                        canControlScreen = true,
                        canSendFiles = true,
                    ),
                ),
                available = emptyList(),
                remembered = emptyList(),
            ),
            onClick = {},
        )
    }
}
