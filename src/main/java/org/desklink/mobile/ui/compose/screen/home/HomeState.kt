/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.home

import androidx.compose.runtime.Immutable
import org.desklink.mobile.plugins.share.TransferRecord
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.webrtc.WebRtcConnectionPath

// What Home shows and what it can do: the state MainActivity builds each tick
// (HomeState and its parts) and the actions it hands in (HomeActions). Data
// only; the screens are in HomeScreen.kt and the files beside it.

/** A paired computer as the switcher sheet lists it. */
@Immutable
data class ComputerRow(val id: String, val name: String, val connection: DeviceConnection, val path: org.desklink.mobile.webrtc.WebRtcConnectionPath? = null)

/** The newest file transfer with this computer. */
@Immutable
data class HomeTransfer(val name: String, val label: String, val progress: Float?, val upload: Boolean, val active: Boolean = true)

/** What is running between the two right now: the coral row and the session-live notification. */
@Immutable
data class LiveSessions(
    val screen: Boolean = false,
    val terminals: Int = 0,
    val phoneScreen: Boolean = false,
    /** F1: this phone's "mic" or "system" sound going to the desk (the coral chip). */
    val audio: String? = null,
    /** F1: the desk's sound is playing on this phone. */
    val hearing: Boolean = false,
    /** F4: the desk's pointer and keyboard are on this phone. */
    val glide: Boolean = false,
) {
    val any get() = screen || terminals > 0 || phoneScreen || audio != null || hearing || glide
}

/** What the connected computer's DeskLink can do (a feature it lacks is not offered by the palette). All on until known. */
@Immutable
data class HomeOffers(
    val files: Boolean = true,
    val clipboard: Boolean = true,
    val link: Boolean = true,
    val screen: Boolean = true,
    val ring: Boolean = true,
    val ping: Boolean = true,
    val lock: Boolean = true,
    /** Why an action above is false, by [PaletteModel.Action] id (the computer's own "off" when missing). */
    val why: Map<String, Blocker> = emptyMap(),
)

@Immutable
data class HomeState(
    val deviceId: String,
    val name: String,
    val connection: DeviceConnection,
    val computers: List<ComputerRow>,
    /** The computer runs a different DeskLink major.minor: the not-connected line says to update. */
    val updateNeeded: Boolean = false,
    val path: WebRtcConnectionPath? = null,
    val rttMs: Int? = null,
    val live: LiveSessions = LiveSessions(),
    val media: HomeMedia? = null,
    val terminalCount: Int = 0,
    val terminalLines: List<String> = emptyList(),
    /** The newest session's name, shown while no session's screen is open here. */
    val terminalNewest: String? = null,
    val transfer: HomeTransfer? = null,
    val clipboardOn: Boolean = false,
    /** F1: "Play <desk>'s sound here" (phone setting, off by default). */
    val deskSoundOn: Boolean = false,
    /** A relay is set up, turned on and online: only then is it mentioned while connecting. */
    val relayAvailable: Boolean = false,
    /** Files sent or received with this computer in the last 30 days. */
    val historyCount: Int = 0,
    /** The desk lets this phone unlock it and the link is the local network: "Unlock desk" shows. */
    val canUnlock: Boolean = false,
    /** Why "Unlock desk" cannot be used, while [canUnlock] is false. */
    val unlockBlocker: Blocker = Blocker.UNLOCK_OFF,
    /** The newest few files sent or received (see [HomeDerive.recentFiles]). */
    val recent: List<TransferRecord> = emptyList(),
    /** One line of what is on this phone's clipboard, only while clipboard sharing is on. */
    val clipboardPreview: String? = null,
    /** How many of the computer's notifications are in this phone's shade. */
    val deskNotifications: Int = 0,
    /** What the computer offers, for the palette. */
    val offers: HomeOffers = HomeOffers(),
    /** More files than [recent] (the palette searches them). */
    val history: List<TransferRecord> = emptyList(),
    /** A terminal command finished or needs the person: the coral strip says so. */
    val terminalAttention: Boolean = false,
    /** Notifications are not allowed on this phone (Android 13+): a one-line nudge with a button. */
    val notificationsOff: Boolean = false,
    /** This phone's side of each feature: missing permissions and Glide ("Everything DeskLink does"). */
    val phone: PhoneFacts = PhoneFacts(),
) {
    val ready get() = connection == DeviceConnection.LOCAL || connection == DeviceConnection.AWAY || connection == DeviceConnection.CONNECTED
}

@androidx.compose.runtime.Stable
class HomeActions(
    val onSelectComputer: (String) -> Unit,
    val onPairAnother: () -> Unit,
    val onRetry: () -> Unit,
    val onEndLive: () -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onVolume: (Int) -> Unit,
    val onSelectPlayer: (String) -> Unit,
    val onDeskScreen: () -> Unit,
    val onSendFiles: () -> Unit,
    val onSendLink: (String) -> Unit,
    val onLock: () -> Unit,
    val onShareScreen: () -> Unit,
    val onPing: () -> Unit,
    val onRing: () -> Unit,
    /** The Files tab: the Bucket and the recent transfers. */
    val onOpenFiles: () -> Unit,
    val onUnlockDesk: () -> Unit,
    /** An action was tapped while the computer cannot take it; the message says why. */
    val onUnavailable: (String) -> Unit,
    /** A dimmed action was tapped: say why ([HomeDerive.message]) and offer its [Fix]. [title] is the action's name. Sends nothing. */
    val onBlocked: (action: String, title: String, blocker: Blocker) -> Unit = { _, _, _ -> },
    val onNotes: () -> Unit = {},
    val onNote: (String) -> Unit = {},
    val onNewNote: () -> Unit = {},
    val onBucket: () -> Unit = {},
    /** Opens the computers sheet from outside (a long-press on the Home tab). */
    val openComputersRequest: Int = 0,
    /** The request was taken (the sheet is open): the owner resets it, so recreating Home does not open the sheet again. */
    val onComputersShown: () -> Unit = {},
    val onSendClipboard: () -> Unit = {},
    val onCancelTransfer: () -> Unit = {},
    val onOpenRecent: (TransferRecord) -> Unit = {},
    /** The Terminals tab. */
    val onTerminals: () -> Unit = {},
    /** A new terminal on the computer. */
    val onNewTerminal: () -> Unit = {},
    /** A Settings page by route ("connection", "advanced/permissions"); the Settings tab opens on it. */
    val onOpenSettings: (String) -> Unit = {},
    /** A short message at the bottom ("Copied"). */
    val onMessage: (String) -> Unit = {},
    /** The nudge's button: asks Android for notifications (the person tapped it). */
    val onTurnOnNotifications: () -> Unit = {},
    /** The "Get started" guide. */
    val onGuide: () -> Unit = {},
    /** A row of "Everything DeskLink does" needs a step: run it (a permission, a switch, Glide). Its page opens after, from Home. */
    val onFeatureStep: (featureId: String, step: FeatureStep) -> Unit = { _, _ -> },
    /** Quick Settings tiles and widgets: offers a tile (Android 13+), or says how. */
    val onAddTiles: () -> Unit = {},
)
