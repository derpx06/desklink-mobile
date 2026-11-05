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
import org.desklink.mobile.plugins.mpris.MediaControl as MprisControlState
import org.desklink.mobile.plugins.screen.PhoneScreenCaptureService
import org.desklink.mobile.plugins.screen.ScreenControlPlugin
import org.desklink.mobile.plugins.share.TransferProgress
import org.desklink.mobile.ui.compose.components.SessionPreviews
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.extensions.device.toUiModel

/** Reads Home's state from the live device objects; called on every refresh tick. */
object HomeModel {
    val clipboardKey: String = Plugin.getPluginKey(ClipboardPlugin::class.java)

    fun pairedDevices(): List<Device> =
        DeskLinkApplication.getInstance().devices.values.filter { it.isPaired }.sortedBy { it.name.lowercase() }

    fun build(context: Context, device: Device, paired: List<Device>, history: List<org.desklink.mobile.plugins.share.TransferRecord> = emptyList()): HomeState {
        val app = DeskLinkApplication.getInstance()
        val id = device.deviceId
        val connection = device.toUiModel().connection
        val terminal = app.terminalClient(id)?.state?.value
        val previews = SessionPreviews.of(context, id, lineCount = 2)
        return HomeState(
            deviceId = id,
            name = device.name,
            connection = connection,
            updateNeeded = !device.hasActiveWebRtcFeatureTransport && org.desklink.mobile.DeviceInfo.versionsSkew(org.desklink.mobile.BuildConfig.VERSION_NAME, device.deviceInfo.appVersion),
            computers = paired.map { ComputerRow(it.deviceId, it.name, it.toUiModel().connection) },
            notificationsOff = HomeDerive.notificationsOff(android.os.Build.VERSION.SDK_INT, androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED),
            live = LiveSessions(
                screen = device.getPlugin(ScreenControlPlugin::class.java)?.remoteLive == true,
                terminals = terminal?.sessions?.count { it.attached && !it.gone } ?: 0,
                phoneScreen = PhoneScreenCaptureService.sharingDeviceId == id,
                audio = org.desklink.mobile.plugins.audio.PhoneAudio.sendingSource
                    .takeIf { org.desklink.mobile.plugins.audio.PhoneAudio.sendingTo == id },
                hearing = org.desklink.mobile.plugins.audio.PhoneAudio.hearingFrom == id,
                glide = org.desklink.mobile.plugins.inputdevicesreceiver.Glide.isGliding(id),
            ),
            media = media(context, device),
            terminalCount = terminal?.sessions?.count { !it.gone } ?: 0,
            terminalLines = previews.firstOrNull { it.lines.isNotEmpty() }?.lines?.takeLast(2).orEmpty(),
            terminalNewest = previews.lastOrNull()?.name ?: terminal?.sessions?.lastOrNull { !it.gone }?.title?.ifBlank { null },
            transfer = TransferProgress.visibleFor(id)?.let { tr ->
                HomeTransfer(
                    name = tr.title,
                    label = when {
                        tr.resuming -> context.getString(R.string.home_transfer_resuming)
                        tr.failed -> context.getString(R.string.home_transfer_failed)
                        tr.finished -> context.getString(if (tr.upload) R.string.home_transfer_sent else R.string.home_transfer_received)
                        else -> (tr.percent?.let { "$it%" } ?: context.getString(if (tr.upload) R.string.home_transfer_sending else R.string.home_transfer_receiving)) +
                            (org.desklink.mobile.plugins.share.TransferNotification.laneWord(context, id, tr.upload, tr.title)?.let { " · $it" } ?: "")
                    },
                    progress = if (tr.finished) 1f else tr.percent?.let { it / 100f },
                    upload = tr.upload,
                    active = !tr.finished && !tr.failed && !tr.saving,
                )
            },
            clipboardOn = device.isPluginEnabled(clipboardKey),
            deskSoundOn = org.desklink.mobile.plugins.audio.PhoneAudio.playDeskSound(context, id),
            historyCount = history.size,
            recent = HomeDerive.recentFiles(history),
            clipboardPreview = if (device.isPluginEnabled(clipboardKey)) org.desklink.mobile.plugins.clipboard.ClipboardListener.instance(context).preview().let(HomeDerive::clipboardPreview) else null,
            canUnlock = connection == DeviceConnection.LOCAL &&
                device.getPlugin(org.desklink.mobile.plugins.desksettings.DeskSettingsPlugin::class.java)?.settings?.unlockNearby == true &&
                device.getPlugin(org.desklink.mobile.plugins.deskunlock.DeskUnlockPlugin::class.java) != null &&
                device.featureActive(org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol.PACKET_TYPE_UNLOCK_REQUEST),
            offers = offers(context, device),
            history = history.take(HISTORY_SEARCHED),
            terminalAttention = org.desklink.mobile.plugins.terminal.TerminalAttentionBadge.has(id),
            relayAvailable = org.desklink.mobile.backends.relay.RelaySettings.enabled(context) &&
                org.desklink.mobile.backends.relay.RelayLinkProvider.statusLive.value == org.desklink.mobile.backends.relay.RelayLinkProvider.Status.ONLINE,
        )
    }

    /** How many files the palette can search. */
    const val HISTORY_SEARCHED = 50

    private fun offers(context: Context, device: Device): HomeOffers {
        fun menu(name: Int) = device.loadedPlugins.values.any { p -> p.getUiMenuEntries().any { it.name == context.getString(name) } }
        val share = device.getPlugin(org.desklink.mobile.plugins.share.SharePlugin::class.java) != null
        return HomeOffers(
            files = share,
            clipboard = device.getPlugin(ClipboardPlugin::class.java) != null,
            link = share,
            screen = device.getPlugin(ScreenControlPlugin::class.java) != null,
            ring = menu(R.string.ring),
            ping = menu(R.string.send_ping),
            lock = menu(R.string.lock_remote_device),
        )
    }

    /** How many of the computer's notifications are in this phone's shade now (0 when it does not mirror them). */
    fun deskNotificationCount(context: Context, device: Device): Int {
        val key = Plugin.getPluginKey(org.desklink.mobile.plugins.receivenotifications.ReceiveNotificationsPlugin::class.java)
        if (!device.isPluginEnabled(key)) return 0
        val prefix = device.deviceId + "/"
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return 0
        return runCatching { manager.activeNotifications.count { it.tag?.startsWith(prefix) == true } }.getOrDefault(0)
    }

    /** The time of the last position report; 0 when there is none (a value built from the clock would tick Home every second). */
    internal fun positionStamp(lastPositionTime: Long?): Long = lastPositionTime ?: 0L

    /**
     * The desk's current player ([MprisPlugin.currentPlayerName]); null (no card) only when the
     * desk has no player at all.
     */
    private fun media(context: Context, device: Device): HomeMedia? {
        val plugin = device.getPlugin(MprisPlugin::class.java) ?: return null
        @Suppress("UNUSED_VARIABLE") val changed = plugin.revision // recompose on every push
        val name = plugin.currentPlayerName() ?: return null
        val player = plugin.getPlayerStatus(name)
        val now = android.os.SystemClock.elapsedRealtime()
        // Without the feature link the desk's report may be out of date: shown as it was, still,
        // with the controls off ("Waiting for <desk>…") until the next snapshot replaces it.
        val stale = !device.hasActiveWebRtcFeatureTransport
        val reported = player?.isPlaying == true && !stale
        // The desk's inline cover first, then an http(s) one; otherwise the card draws a gradient.
        val art = player?.art()
        return HomeMedia(
            player = name,
            players = plugin.playerList,
            title = player?.title?.takeIf(String::isNotBlank) ?: name,
            artist = player?.artist.orEmpty(),
            playing = MprisControlState.shownPlaying(reported, plugin.pendingPlaying, plugin.pendingSince, now),
            pending = MprisControlState.isPending(reported, plugin.pendingPlaying, plugin.pendingSince, now),
            canPlayPause = !stale && (player == null || player.isPlayAllowed || player.isPauseAllowed),
            canNext = !stale && (player?.isGoNextAllowed ?: true),
            canPrevious = !stale && (player?.isGoPreviousAllowed ?: true),
            canSeek = !stale && player != null && player.seekAllowed && player.length > 0,
            lengthMs = player?.length ?: -1,
            positionMs = player?.lastPosition ?: 0,
            // MprisPlayer stamps positions with the wall clock. Unknown is 0, never "now": a state built every second must not differ
            // from the last one, or the whole of Home recomposes (and the seek bar restarts) once a second.
            positionAt = positionStamp(player?.lastPositionTime),
            reportedPlaying = reported,
            volume = player?.volume?.takeIf { it in 0..100 },
            art = art?.image,
            vibrant = art?.vibrant,
            artKey = player?.artId ?: player?.albumArtUrl?.takeIf { art != null } ?: "title:" + (player?.title ?: name),
            desk = device.name,
            subtitleFallback = context.getString(R.string.home_media_on, device.name),
            stale = stale,
            waiting = context.getString(R.string.settings_waiting_for, device.name),
        )
    }
}
