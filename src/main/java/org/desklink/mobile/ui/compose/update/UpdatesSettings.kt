/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskTextButton
import org.desklink.mobile.ui.compose.components.GroupRow
import org.desklink.mobile.ui.compose.components.RowGroup
import org.desklink.mobile.ui.compose.tokens
import org.desklink.mobile.update.Channel

/**
 * The only update UI inside a screen (docs/UPDATES.md 6.6): controls, nothing else. No card, banner or badge; an update found
 * opens the sheet. Not set up = one plain line and no controls.
 */
@Composable
internal fun UpdatesSettings(model: UpdatesSettingsModel, actions: UpdatesSettingsActions) {
    val t = tokens
    if (!model.configured) {
        RowGroup { GroupRow(stringResource(R.string.update_not_set_up), icon = R.drawable.sym_refresh) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RowGroup {
            CheckRow(model, actions)
            GroupRow(
                stringResource(R.string.update_auto), icon = R.drawable.sym_restart_alt, subtitle = stringResource(R.string.update_auto_line),
                checked = model.enabled, onClick = { actions.onEnabled(!model.enabled) },
            )
            GroupRow(
                stringResource(R.string.update_wifi), icon = R.drawable.sym_download,
                checked = model.wifiOnly, onClick = { actions.onWifiOnly(!model.wifiOnly) },
            )
            ChannelRow(model, actions)
            model.skippedVersion?.let { v ->
                GroupRow(stringResource(R.string.update_skipped, v), icon = R.drawable.sym_arrow_forward) { DeskTextButton(stringResource(R.string.update_undo), actions.onUndoSkip) }
            }
        }
        Text(stringResource(R.string.update_share), fontSize = 13.sp, lineHeight = 18.sp, color = t.textDim, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

@Composable
private fun CheckRow(model: UpdatesSettingsModel, actions: UpdatesSettingsActions) {
    val off = if (!model.enabled) stringResource(R.string.update_auto_off) + " · " + stringResource(R.string.update_auto_off_line) else null
    // The status text is a polite live region: TalkBack says "Checking…", then the result.
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (val s = model.status) {
        CheckStatus.Checking -> GroupRow(stringResource(R.string.update_checking), live, icon = R.drawable.sym_refresh) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = tokens.accent)
        }
        is CheckStatus.UpToDate -> GroupRow(
            stringResource(R.string.update_up_to_date), live, icon = R.drawable.sym_check_circle,
            subtitle = stringResource(R.string.update_up_to_date_line, s.version, s.checkedAt),
        ) { DeskTextButton(stringResource(R.string.update_check_button), actions.onCheck) }
        CheckStatus.Failed -> GroupRow(stringResource(R.string.update_failed), live, icon = R.drawable.sym_refresh) {
            DeskTextButton(stringResource(R.string.update_check_button), actions.onCheck)
        }
        CheckStatus.Idle -> GroupRow(stringResource(R.string.update_check), live, icon = R.drawable.sym_refresh, subtitle = off) {
            DeskTextButton(stringResource(R.string.update_check_button), actions.onCheck)
        }
    }
}

@Composable
private fun ChannelRow(model: UpdatesSettingsModel, actions: UpdatesSettingsActions) {
    val name = stringResource(if (model.channel == Channel.BETA) R.string.update_channel_beta else R.string.update_channel_stable)
    val other = if (model.channel == Channel.BETA) Channel.STABLE else Channel.BETA
    GroupRow(
        stringResource(R.string.update_channel), icon = R.drawable.sym_description,
        // Stable is not offered until the update information lists one: a plain note says why.
        subtitle = if (!model.stableSelectable && model.channel == Channel.BETA) stringResource(R.string.update_channel_note) else null,
        onClick = if (model.stableSelectable || model.channel == Channel.STABLE) ({ actions.onChannel(other) }) else null,
    ) { Text(name, fontSize = 15.sp, color = tokens.textDim) }
}
