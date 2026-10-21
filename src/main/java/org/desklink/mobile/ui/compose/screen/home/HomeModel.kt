/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.home

import android.content.Context
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.Device
import org.desklink.mobile.R
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.clipboard.ClipboardPlugin
import org.desklink.mobile.plugins.mpris.MprisPlugin
import org.desklink.mobile.plugins.screen.PhoneScreenCaptureService
import org.desklink.mobile.plugins.screen.ScreenControlPlugin
import org.desklink.mobile.plugins.share.TransferProgress
import org.desklink.mobile.ui.compose.components.SessionPreviews
import org.desklink.mobile.ui.compose.extensions.device.toUiModel

/** Reads Home's state from the live device objects; called on every refresh tick. */
object HomeModel {
    val clipboardKey: String = Plugin.getPluginKey(ClipboardPlugin::class.java)

    fun pairedDevices(): List<Device> =
        DeskLinkApplication.getInstance().devices.values.filter { it.isPaired }.sortedBy { it.name.lowercase() }

    fun build(context: Context, device: Device, paired: List<Device>): HomeState {
        val app = DeskLinkApplication.getInstance()
        val id = device.deviceId
        val connection = device.toUiModel().connection
        val terminal = app.terminalClient(id)?.state?.value
        val previews = SessionPreviews.of(context, id, lineCount = 2)
        return HomeState(
            deviceId = id,
            name = device.name,
            connection = connection,
            computers = paired.map { ComputerRow(it.deviceId, it.name, it.toUiModel().connection) },
            live = LiveSessions(
                screen = device.getPlugin(ScreenControlPlugin::class.java)?.remoteLive == true,
                terminals = terminal?.sessions?.count { it.attached && !it.gone } ?: 0,
                phoneScreen = PhoneScreenCaptureService.sharingDeviceId == id,
            ),
            media = media(context, device),
            terminalCount = terminal?.sessions?.count { !it.gone } ?: 0,
            terminalLines = previews.firstOrNull { it.lines.isNotEmpty() }?.lines?.takeLast(2).orEmpty(),
            terminalNewest = previews.lastOrNull()?.name ?: terminal?.sessions?.lastOrNull { !it.gone }?.title?.ifBlank { null },
            transfer = TransferProgress.visibleFor(id)?.let { tr ->
                HomeTransfer(
                    name = tr.title,
                    label = when {
                        tr.failed -> context.getString(R.string.home_transfer_failed)
                        tr.finished -> context.getString(if (tr.upload) R.string.home_transfer_sent else R.string.home_transfer_received)
                        else -> tr.percent?.let { "$it%" } ?: context.getString(if (tr.upload) R.string.home_transfer_sending else R.string.home_transfer_receiving)
                    },
                    progress = if (tr.finished) 1f else tr.percent?.let { it / 100f },
                    upload = tr.upload,
                )
            },
            clipboardOn = device.isPluginEnabled(clipboardKey),
            historyCount = org.desklink.mobile.plugins.share.TransferHistory.store(context).forDevice(id).size,
            relayAvailable = org.desklink.mobile.backends.relay.RelaySettings.enabled(context) &&
                org.desklink.mobile.backends.relay.RelayLinkProvider.statusLive.value == org.desklink.mobile.backends.relay.RelayLinkProvider.Status.ONLINE,
        )
    }

    /** The desk's player, playing first; null (no card) until the desk reports one with a title. */
    private fun media(context: Context, device: Device): HomeMedia? {
        val plugin = device.getPlugin(MprisPlugin::class.java) ?: return null
        val player = plugin.playingPlayer ?: plugin.playerList.mapNotNull(plugin::getPlayerStatus).firstOrNull { it.title.isNotBlank() } ?: return null
        if (player.title.isBlank()) return null
        val where = context.getString(if (player.isPlaying) R.string.home_media_playing_on else R.string.home_media_paused_on, device.name)
        return HomeMedia(
            title = player.title,
            subtitle = listOf(player.artist, where).filter(String::isNotBlank).joinToString(" · "),
            playing = player.isPlaying,
            canNext = player.isGoNextAllowed,
        )
    }

    fun mediaPlayer(device: Device): MprisPlugin.MprisPlayer? {
        val plugin = device.getPlugin(MprisPlugin::class.java) ?: return null
        return plugin.playingPlayer ?: plugin.playerList.mapNotNull(plugin::getPlayerStatus).firstOrNull { it.title.isNotBlank() }
    }
}
