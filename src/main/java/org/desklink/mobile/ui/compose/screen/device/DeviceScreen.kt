/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.device

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.PairingHandler.PairState
import org.desklink.mobile.R
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.ui.compose.components.BatteryBadge
import org.desklink.mobile.ui.compose.components.ConnectionPill
import org.desklink.mobile.ui.compose.components.DeviceAvatar
import org.desklink.mobile.ui.compose.components.HintCard
import org.desklink.mobile.ui.compose.components.SectionTitle
import org.desklink.mobile.ui.compose.components.connectionLabel
import org.desklink.mobile.ui.compose.model.device.DeviceConnection

/** A quick action from a plugin's menu entry, with the icon the device screen gives it. */
data class QuickActionItem(val entry: Plugin.PluginUiMenuEntry, @DrawableRes val icon: Int)

data class DeviceScreenState(
    val name: String,
    @DrawableRes val icon: Int,
    val pairState: PairState,
    val reachable: Boolean,
    val connection: DeviceConnection,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val verificationKey: String? = null,
    val pairMessage: String? = null,
    val protocolVersion: Int = 0,
    val controls: List<Plugin.PluginUiButton> = emptyList(),
    val quickActions: List<QuickActionItem> = emptyList(),
    val needPermissions: List<Plugin> = emptyList(),
    val optionalPermissions: List<Plugin> = emptyList(),
    val isRefreshing: Boolean = false,
)

class DeviceScreenActions(
    val onControl: (Plugin.PluginUiButton) -> Unit,
    val onQuickAction: (Plugin.PluginUiMenuEntry) -> Unit,
    val onRequiredPermission: (Plugin) -> Unit,
    val onOptionalPermission: (Plugin) -> Unit,
    val onRequestPairing: () -> Unit,
    val onAcceptPairing: () -> Unit,
    val onRejectPairing: () -> Unit,
    val onCancelPairing: () -> Unit,
    val onRefresh: () -> Unit,
    val onEncryptionInfo: () -> Unit,
    val onPluginSettings: () -> Unit,
    val onUnpair: () -> Unit,
)

@Composable
fun DeviceScreen(state: DeviceScreenState, actions: DeviceScreenActions) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp + bottom),
        ) {
            when {
                state.pairState != PairState.Paired -> PairingContent(state, actions)
                !state.reachable -> OfflineContent(state, actions)
                else -> ConnectedContent(state, actions)
            }
        }
    }
}

@Composable
private fun Hero(state: DeviceScreenState, showStatus: Boolean = true, showName: Boolean = true, tinted: Boolean = state.reachable) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DeviceAvatar(state.icon, online = tinted, size = 84.dp)
        if (showName) {
            Spacer(Modifier.height(16.dp))
            Text(
                state.name,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showStatus) {
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                ConnectionPill(state.connection)
                state.batteryPercent?.let { BatteryBadge(it, state.charging) }
            }
        }
    }
}

// ---- paired and reachable -------------------------------------------------------------------

@Composable
private fun ConnectedContent(state: DeviceScreenState, actions: DeviceScreenActions) {
    Hero(state)

    if (state.controls.isNotEmpty()) {
        SectionTitle(stringResource(R.string.device_section_controls))
        ControlGrid(state.controls, actions.onControl)
    }

    if (state.quickActions.isNotEmpty()) {
        SectionTitle(stringResource(R.string.device_section_quick))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.quickActions.forEach { item ->
                FilledTonalButton(
                    onClick = { actions.onQuickAction(item.entry) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(start = 14.dp, end = 18.dp),
                ) {
                    Icon(painterResource(item.icon), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(item.entry.name)
                }
            }
        }
    }

    PermissionSection(R.string.device_section_permissions, state.needPermissions, warning = true, onClick = actions.onRequiredPermission)
    PermissionSection(R.string.device_section_optional_permissions, state.optionalPermissions, warning = false, onClick = actions.onOptionalPermission)

    SectionTitle(stringResource(R.string.device_section_connection))
    GroupCard {
        InfoRow(R.drawable.ic_wifi_tethering_24dp, stringResource(R.string.device_path), connectionLabel(state.connection))
        RowDivider()
        InfoRow(R.drawable.ic_info_24dp, stringResource(R.string.device_protocol), stringResource(R.string.device_protocol_value, state.protocolVersion))
        RowDivider()
        InfoRow(R.drawable.ic_shield_24dp, stringResource(R.string.device_encryption), stringResource(R.string.device_encryption_value), onClick = actions.onEncryptionInfo)
    }

    ManageSection(state, actions)
}

@Composable
private fun ControlGrid(controls: List<Plugin.PluginUiButton>, onClick: (Plugin.PluginUiButton) -> Unit) {
    val columns = integerResource(R.integer.plugins_columns)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        controls.chunked(columns).forEachIndexed { rowIndex, row ->
            // Tiles in a row share the tallest one's height, whatever their descriptions wrap to.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { index, button ->
                    ControlTile(button, tone = (rowIndex * columns + index) % 3, modifier = Modifier.weight(1f).fillMaxHeight()) { onClick(button) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ControlTile(button: Plugin.PluginUiButton, tone: Int, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        0 -> colors.primaryContainer to colors.onPrimaryContainer
        1 -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow,
        modifier = modifier.heightIn(min = 136.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = container, contentColor = content, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(button.iconRes), contentDescription = null, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                button.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            tileDescription(button.iconRes)?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** What a control does, keyed by the icon its plugin gives it (the only stable handle a button has). */
private fun tileDescription(@DrawableRes icon: Int): Int? = when (icon) {
    R.drawable.touchpad_plugin_action_24dp -> R.string.tile_desc_remote
    R.drawable.ic_terminal_24dp -> R.string.tile_desc_terminal
    R.drawable.share_plugin_action_24dp -> R.string.tile_desc_files
    R.drawable.ic_baseline_content_paste_24 -> R.string.tile_desc_clipboard
    R.drawable.ic_presenter_24dp -> R.string.tile_desc_presenter
    R.drawable.run_command_plugin_icon_24dp -> R.string.tile_desc_runcommand
    R.drawable.mpris_plugin_action_24dp -> R.string.tile_desc_media
    R.drawable.ic_draw_24dp -> R.string.tile_desc_digitizer
    R.drawable.tv_remote_24px -> R.string.tile_desc_tv
    else -> null
}

@Composable
private fun PermissionSection(title: Int, plugins: List<Plugin>, warning: Boolean, onClick: (Plugin) -> Unit) {
    if (plugins.isEmpty()) return
    SectionTitle(stringResource(title))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plugins.forEach { plugin ->
            HintCard(
                icon = if (warning) R.drawable.ic_warning else R.drawable.ic_info_24dp,
                text = plugin.displayName,
                warning = warning,
                onClick = { onClick(plugin) },
            )
        }
    }
}

@Composable
private fun ManageSection(state: DeviceScreenState, actions: DeviceScreenActions) {
    var confirmUnpair by remember { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.device_section_manage))
    GroupCard {
        InfoRow(R.drawable.ic_tune_24dp, stringResource(R.string.device_menu_plugins), null, onClick = actions.onPluginSettings)
        RowDivider()
        InfoRow(
            R.drawable.ic_link_off_24dp,
            stringResource(R.string.device_menu_unpair),
            stringResource(R.string.device_unpair_summary),
            danger = true,
            onClick = { confirmUnpair = true },
        )
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            icon = { Icon(painterResource(R.drawable.ic_link_off_24dp), contentDescription = null) },
            title = { Text(stringResource(R.string.device_unpair_title, state.name)) },
            text = { Text(stringResource(R.string.device_unpair_message)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmUnpair = false; actions.onUnpair() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.device_menu_unpair)) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

@Composable
private fun InfoRow(
    @DrawableRes icon: Int,
    title: String,
    value: String?,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val body: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                if (value != null) {
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onClick != null) {
                Icon(painterResource(R.drawable.ic_chevron_right_24dp), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, color = Color.Transparent, content = body)
    } else {
        body()
    }
}

// ---- paired but unreachable -----------------------------------------------------------------

@Composable
private fun OfflineContent(state: DeviceScreenState, actions: DeviceScreenActions) {
    Hero(state)
    Spacer(Modifier.height(24.dp))
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.device_offline_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.device_offline_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = actions.onRefresh, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(painterResource(R.drawable.ic_action_refresh_24dp), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.device_try_again))
            }
        }
    }
    ManageSection(state, actions)
}

// ---- not paired -------------------------------------------------------------------------------

@Composable
private fun PairingContent(state: DeviceScreenState, actions: DeviceScreenActions) {
    Hero(state, showStatus = false, showName = false, tinted = true)
    Spacer(Modifier.height(16.dp))
    val title = when (state.pairState) {
        PairState.RequestedByPeer -> stringResource(R.string.pair_title_incoming, state.name)
        PairState.Requested -> stringResource(R.string.pair_title_waiting, state.name)
        else -> stringResource(R.string.pair_title_request, state.name)
    }
    Text(
        state.pairMessage ?: title,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(
            when (state.pairState) {
                PairState.RequestedByPeer -> R.string.pair_code_hint
                PairState.Requested -> R.string.pair_waiting_hint
                else -> R.string.pairing_explanation
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    val key = state.verificationKey
    if (key != null && state.pairState != PairState.NotPaired) {
        Spacer(Modifier.height(24.dp))
        VerificationCode(key)
    }

    Spacer(Modifier.height(28.dp))
    when (state.pairState) {
        PairState.RequestedByPeer -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = actions.onRejectPairing,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.large,
            ) { Text(stringResource(R.string.pairing_reject)) }
            Button(
                onClick = actions.onAcceptPairing,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.large,
            ) { Text(stringResource(R.string.pairing_accept)) }
        }
        PairState.Requested -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = actions.onCancelPairing, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel_pairing))
            }
        }
        else -> Button(
            onClick = actions.onRequestPairing,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.large,
        ) { Text(stringResource(R.string.request_pairing), style = MaterialTheme.typography.titleMedium) }
    }
}

/** The six digits both screens show, large and grouped in threes: "482 913". */
@Composable
private fun VerificationCode(key: String) {
    val description = stringResource(R.string.pair_code_description, key.toList().joinToString(" "))
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .padding(vertical = 24.dp, horizontal = 16.dp)
                .clearAndSetSemantics { contentDescription = description },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.pair_code_label).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                key.chunked(3).joinToString(" "),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 44.sp,
                letterSpacing = 6.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}
