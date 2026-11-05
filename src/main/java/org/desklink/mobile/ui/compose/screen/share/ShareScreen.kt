/*
 * SPDX-FileCopyrightText: 2026 Saul Cintero Chocarro <scintero@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.extensions.safeDrawingBottomPadding
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.DeskLinkShape
import org.desklink.mobile.ui.compose.DeskLinkType
import org.desklink.mobile.ui.compose.components.DeskCard
import org.desklink.mobile.ui.compose.components.GlyphSquircle
import org.desklink.mobile.ui.compose.tokens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import org.desklink.mobile.ui.compose.components.DeskLinkThemePreviews
import org.desklink.mobile.ui.compose.components.SectionHeader
import org.desklink.mobile.ui.compose.model.device.DeviceUiModel
import org.desklink.mobile.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    devices: List<DeviceUiModel>,
    intentHasUrl: Boolean,
    isRefreshing: Boolean,
    onDeviceClick: (String) -> Unit,
    onRefresh: () -> Unit
) {
    val state = rememberLazyListState()
    val sectionTitle = if (intentHasUrl) {
        "${stringResource(id = R.string.unreachable_device_url_share_text)} ${stringResource(id = R.string.share_to)}"
    } else {
        stringResource(id = R.string.share_to)
    }

    ShareScreenContent(
        state = state,
        isRefreshing = isRefreshing,
        sectionTitle = sectionTitle,
        devices = devices,
        onDeviceClick = onDeviceClick,
        onRefresh = onRefresh
    )
}

@Composable
private fun ShareScreenContent(
    state: LazyListState,
    isRefreshing: Boolean,
    sectionTitle: String,
    devices: List<DeviceUiModel>,
    onDeviceClick: (String) -> Unit,
    onRefresh: () -> Unit
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = 4.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
            contentPadding = safeDrawingBottomPadding(),
            state = state
        ) {
            item {
                SectionHeader(title = sectionTitle)
            }
            items(
                items = devices,
                key = { device -> device.id }
            ) { device ->
                DeskCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = DeskLinkShape.group,
                    onClick = { onDeviceClick(device.id) },
                ) { ShareScreenCardContent(device = device) }
            }
        }
    }
}

/** A computer to send to: its glyph, its name, and in coral why it cannot be reached; 72 dp tall, the whole card is the button. */
@Composable
private fun ShareScreenCardContent(device: DeviceUiModel) {
    val t = tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (device.icon > 0) GlyphSquircle(device.icon, dimmed = !device.isReachable)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(device.name, style = DeskLinkType.cardTitle, color = t.text)
            if (!device.isReachable) {
                Text(stringResource(id = R.string.device_not_reachable), fontSize = 14.sp, color = t.coralText)
            }
        }
        Icon(painterResource(R.drawable.sym_chevron_right), contentDescription = null, tint = t.textDim, modifier = Modifier.size(22.dp))
    }
}

@DeskLinkThemePreviews
@Composable
private fun ShareScreenPreview() {
    DeskLinkTheme(context = LocalContext.current) {
        ShareScreenContent(
            state = rememberLazyListState(),
            isRefreshing = false,
            sectionTitle = stringResource(id = R.string.share_to),
            devices = listOf(
                DeviceUiModel(
                    id = "_2504584b_6aa2_3cd6_bd1b_5e958aa6cd23_",
                    icon = R.drawable.ic_device_laptop_32dp,
                    name = "Device 1",
                    summaryRes = 0,
                    isReachable = true,
                    isPaired = true,
                ),
                DeviceUiModel(
                    id = "_2504584b_6aa2_3cd6_bd1b_5e958aa6cd24_",
                    icon = R.drawable.ic_device_phone_32dp,
                    name = "Device 2",
                    summaryRes = 0,
                    isReachable = true,
                    isPaired = true,
                )
            ),
            onDeviceClick = { /* Do nothing */ },
            onRefresh = { /* Do nothing */ }
        )
    }
}
