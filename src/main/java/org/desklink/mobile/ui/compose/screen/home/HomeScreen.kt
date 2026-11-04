/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.desklink.mobile.R
import org.desklink.mobile.plugins.share.TransferRecord
import org.desklink.mobile.plugins.share.previewKind
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.components.IconCircle
import org.desklink.mobile.ui.compose.components.LaptopGlyph
import org.desklink.mobile.ui.compose.components.LinkGlyph
import org.desklink.mobile.ui.compose.components.LinkGlyphState
import org.desklink.mobile.ui.compose.components.PillButton
import org.desklink.mobile.ui.compose.components.PillStyle
import org.desklink.mobile.ui.compose.components.SectionLabel
import org.desklink.mobile.ui.compose.components.linkGlyphState
import org.desklink.mobile.ui.compose.components.shimmer
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.screen.transfers.iconFor
import org.desklink.mobile.ui.compose.screen.transfers.rememberFilePreview
import org.desklink.mobile.ui.compose.tokens
import org.desklink.mobile.webrtc.WebRtcConnectionPath

/** A paired computer as the switcher sheet lists it. */
@Immutable
data class ComputerRow(val id: String, val name: String, val connection: DeviceConnection)

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
    /** The newest few files sent or received (see [HomeDerive.recentFiles]). */
    val recent: List<TransferRecord> = emptyList(),
    /** One line of what is on this phone's clipboard, only while clipboard sharing is on. */
    val clipboardPreview: String? = null,
    /** How many of the computer's notifications are in this phone's shade. */
    val deskNotifications: Int = 0,
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
    val onNotes: () -> Unit = {},
    val onNote: (String) -> Unit = {},
    val onNewNote: () -> Unit = {},
    val onBucket: () -> Unit = {},
    /** Opens the computers sheet from outside (a long-press on the Home tab). */
    val openComputersRequest: Int = 0,
    val onSendClipboard: () -> Unit = {},
    val onCancelTransfer: () -> Unit = {},
    val onOpenRecent: (TransferRecord) -> Unit = {},
    /** The Terminals tab. */
    val onTerminals: () -> Unit = {},
)

/** From this width the screen has two columns: what you can do on the left, what is going on on the right. */
private val TWO_COLUMNS = 560.dp

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    val t = tokens
    var sheet by rememberSaveable { mutableStateOf(false) }
    var linkDialog by rememberSaveable { mutableStateOf(false) }
    val notReady = notReadyMessage(state)
    val gate = { action: () -> Unit -> if (state.ready) action() else actions.onUnavailable(notReady) }
    // The Home tab's long-press asks for the computers sheet.
    androidx.compose.runtime.LaunchedEffect(actions.openComputersRequest) { if (actions.openComputersRequest > 0) sheet = true }

    var playerOpen by rememberSaveable { mutableStateOf(false) }
    // Where the card's artwork is, for the player to grow from.
    val cardArt = remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
            val two = maxWidth >= TWO_COLUMNS
            val doing: @Composable () -> Unit = {
                HeaderCard(state, actions) { sheet = true }
                QuickActions(state, gate, actions)
                MoreActions(state, gate, actions) { linkDialog = true }
            }
            val going: @Composable () -> Unit = {
                HappeningNow(state, actions, cardArt) { playerOpen = true }
                RecentFiles(state, actions)
                org.desklink.mobile.plugins.notes.NotesHomeCard(onOpenList = actions.onNotes, onOpenNote = actions.onNote, onNew = actions.onNewNote, limit = HomeDerive.RECENT_NOTES)
                MirroredLine(state)
                // Local-first: the Bucket shows whether or not the computer is there.
                org.desklink.mobile.plugins.folders.BucketHomeCard(state.deviceId, state.name, onOpen = actions.onBucket)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
            ) {
                if (two) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) { doing() }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) { going() }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        // What is going on comes first when something is: it is what you opened the app to check or stop.
                        HeaderCard(state, actions) { sheet = true }
                        HappeningNow(state, actions, cardArt) { playerOpen = true }
                        QuickActions(state, gate, actions)
                        MoreActions(state, gate, actions) { linkDialog = true }
                        RecentFiles(state, actions)
                        org.desklink.mobile.plugins.notes.NotesHomeCard(onOpenList = actions.onNotes, onOpenNote = actions.onNote, onNew = actions.onNewNote, limit = HomeDerive.RECENT_NOTES)
                        MirroredLine(state)
                        org.desklink.mobile.plugins.folders.BucketHomeCard(state.deviceId, state.name, onOpen = actions.onBucket)
                    }
                }
            }
        }
        NowPlayingOverlay(state.media, actions, cardArt, playerOpen && state.media != null) { playerOpen = false }
    }

    if (sheet) ComputersSheet(state, onDismiss = { sheet = false }, actions)
    if (linkDialog) SendLinkDialog(onDismiss = { linkDialog = false }) { url -> linkDialog = false; actions.onSendLink(url) }
}

@Composable
private fun notReadyMessage(state: HomeState) = stringResource(
    when {
        state.updateNeeded -> R.string.home_update_deskLink
        else -> when (state.connection) {
            DeviceConnection.CONNECTING -> R.string.home_unavailable_connecting
            DeviceConnection.UNAVAILABLE -> R.string.home_unavailable_unreachable
            else -> R.string.home_unavailable_offline
        }
    },
    state.name,
)

@Composable
private fun connectionLabel(connection: DeviceConnection, path: WebRtcConnectionPath?): String = stringResource(
    when (linkGlyphState(connection, path)) {
        LinkGlyphState.OFFLINE -> R.string.connection_offline
        LinkGlyphState.CONNECTING -> R.string.connection_connecting
        // TURN is a relay server; the DeskLink relay only when the link really goes through it.
        LinkGlyphState.RELAY -> if (path == WebRtcConnectionPath.TURN) R.string.home_connected_turn else R.string.home_connected_relay
        LinkGlyphState.DIRECT -> when {
            connection == DeviceConnection.LOCAL || path == WebRtcConnectionPath.LAN -> R.string.home_connected_wifi
            path == WebRtcConnectionPath.DIRECT || connection == DeviceConnection.AWAY -> R.string.home_connected_internet
            else -> R.string.connection_connected
        }
    },
)

@Composable
fun connectionShort(connection: DeviceConnection): String = stringResource(
    when (connection) {
        DeviceConnection.LOCAL -> R.string.home_short_wifi
        DeviceConnection.AWAY -> R.string.home_short_relay
        DeviceConnection.CONNECTED -> R.string.connection_connected
        DeviceConnection.CONNECTING -> R.string.connection_connecting
        DeviceConnection.OFFLINE -> R.string.connection_offline
        DeviceConnection.UNAVAILABLE -> R.string.connection_unavailable
    },
)

// ---- header -------------------------------------------------------------------------------------------

/**
 * The computer's name (tap to switch), one plain sentence of how it is reached, and, when it is not,
 * the one next step. Nothing here ticks: the round trip is the one number, and it comes with the state.
 */
@Composable
private fun HeaderCard(state: HomeState, actions: HomeActions, onSwitch: () -> Unit) {
    val t = tokens
    val glyph = linkGlyphState(state.connection, state.path)
    val shape = RoundedCornerShape(28.dp)
    val quiet = glyph == LinkGlyphState.OFFLINE
    val switchDescription = stringResource(R.string.home_switch_computer, state.name, connectionShort(state.connection))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (quiet) Brush.verticalGradient(listOf(t.surface, t.surface)) else Brush.verticalGradient(0f to t.accentContainer, 0.9f to t.surface))
            .border(1.dp, t.hairline, shape)
            .animateContentSize()
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(20.dp))
                .semantics(mergeDescendants = true) { contentDescription = switchDescription }
                .clickable(role = Role.Button, onClick = onSwitch)
                .padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(44.dp).background(t.surface, CircleShape).border(1.dp, t.hairline, CircleShape), contentAlignment = Alignment.Center) {
                LaptopGlyph(t.text, Modifier.padding(bottom = 1.dp).size(width = 23.dp, height = 15.dp))
            }
            // The computer's name is what this card says: two lines before anything is cut.
            Text(
                state.name,
                fontFamily = MonoFamily,
                // At the largest system font a long name must break between words, not inside "arch-de|sk".
                fontSize = if (LocalDensity.current.fontScale > 1.4f) 15.sp else 20.sp,
                lineHeight = if (LocalDensity.current.fontScale > 1.4f) 20.sp else 26.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            )
            Icon(painterResource(R.drawable.sym_expand_more), contentDescription = null, tint = t.textDim, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Crossfade(glyph, label = "status") { shown ->
                StatusBlock(state, shown, actions)
            }
            if (state.ready && state.canUnlock) {
                PillButton(stringResource(R.string.home_unlock_desk, state.name), actions.onUnlockDesk, Modifier.fillMaxWidth(), style = PillStyle.Outlined, height = 44.dp, icon = R.drawable.sym_lock_open)
            }
            if (!state.ready && quiet) {
                PillButton(stringResource(R.string.device_try_again), actions.onRetry, Modifier.fillMaxWidth(), style = PillStyle.Tonal, height = 44.dp, icon = R.drawable.sym_refresh)
            }
        }
    }
}

/** The glyph and the one sentence, with the line under it that fits the state. */
@Composable
private fun StatusBlock(state: HomeState, glyph: LinkGlyphState, actions: HomeActions) {
    val t = tokens
    val sentence = when (glyph) {
        LinkGlyphState.OFFLINE -> stringResource(R.string.home_cant_reach, state.name)
        LinkGlyphState.CONNECTING -> stringResource(R.string.connection_connecting)
        LinkGlyphState.RELAY, LinkGlyphState.DIRECT -> {
            val meta = if (glyph == LinkGlyphState.RELAY) {
                state.rttMs?.let { stringResource(R.string.home_meta_relay, it) } ?: stringResource(R.string.home_meta_encrypted)
            } else {
                state.rttMs?.let { stringResource(R.string.home_meta_rtt, it) }
            }
            listOfNotNull(connectionLabel(state.connection, state.path), meta).joinToString(" · ")
        }
    }
    val detail = when {
        state.updateNeeded -> stringResource(R.string.home_update_deskLink, state.name)
        glyph == LinkGlyphState.OFFLINE -> stringResource(if (state.connection == DeviceConnection.UNAVAILABLE) R.string.home_unavailable_unreachable else R.string.home_offline_body, state.name)
        glyph == LinkGlyphState.CONNECTING -> stringResource(if (state.relayAvailable) R.string.home_trying_paths else R.string.home_looking_on_wifi, state.name)
        else -> null
    }
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LinkGlyph(glyph, Modifier.padding(top = 7.dp))
            Text(sentence, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        }
        if (glyph == LinkGlyphState.CONNECTING) {
            // A placeholder where the round trip will be, instead of a gap that jumps when it arrives.
            Box(Modifier.size(width = 96.dp, height = 10.dp).clip(RoundedCornerShape(5.dp)).shimmer())
        }
        detail?.let { Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = t.textDim) }
    }
}

// ---- quick actions ------------------------------------------------------------------------------------

/** What each tile says under its name: why it is off, or what it will do. */
@Composable
private fun reasonText(why: NotReady): String = stringResource(
    when (why) {
        NotReady.UPDATE -> R.string.home_tile_update
        NotReady.CONNECTING -> R.string.connection_connecting
        NotReady.CANT_REACH -> R.string.connection_unavailable
        NotReady.OFFLINE -> R.string.connection_offline
    },
)

/** Send files, send clipboard, desk screen, ring, lock, terminals: two across, one across when the words would not fit. */
@Composable
private fun QuickActions(state: HomeState, gate: (() -> Unit) -> Unit, actions: HomeActions) {
    val why = HomeDerive.whyNot(state)
    val off = why?.let { reasonText(it) }
    val clipboardSub = when {
        off != null -> off
        !state.clipboardOn -> stringResource(R.string.home_tile_clipboard_off)
        else -> state.clipboardPreview?.let { stringResource(R.string.home_tile_clipboard_preview, it) } ?: stringResource(R.string.home_tile_clipboard_hint)
    }
    val terminalsSub = if (state.live.terminals > 0) pluralStringResource(R.plurals.home_live_terminals, state.live.terminals, state.live.terminals)
    else stringResource(R.string.home_tile_terminals_hint)
    // Terminals is the one tile that works with the computer away: it opens the tab, which keeps its own sessions.
    val tiles = listOf(
        Tile(R.drawable.sym_upload_file, stringResource(R.string.home_send_files), off, why == null) { gate(actions.onSendFiles) },
        Tile(R.drawable.sym_content_paste, stringResource(R.string.home_send_clipboard_short), clipboardSub, why == null) { gate(actions.onSendClipboard) },
        Tile(R.drawable.sym_desktop_windows, stringResource(R.string.home_desk_screen), off ?: stringResource(R.string.home_tile_screen_hint), why == null) { gate(actions.onDeskScreen) },
        Tile(R.drawable.sym_vibration, stringResource(R.string.home_ring_desk), off ?: stringResource(R.string.home_tile_ring_hint), why == null) { gate(actions.onRing) },
        Tile(R.drawable.sym_lock, stringResource(R.string.home_lock_desk), off ?: stringResource(R.string.home_tile_lock_hint), why == null) { gate(actions.onLock) },
        Tile(R.drawable.sym_terminal, stringResource(R.string.tab_terminals), terminalsSub, true) { actions.onTerminals() },
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val big = LocalDensity.current.fontScale > 1.4f
        val columns = if (maxWidth < 270.dp || big) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { tile -> ActionTile(tile, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}

private class Tile(@DrawableRes val icon: Int, val label: String, val sub: String?, val enabled: Boolean, val onClick: () -> Unit)

@Composable
private fun ActionTile(tile: Tile, modifier: Modifier) {
    val t = tokens
    val shape = RoundedCornerShape(20.dp)
    val reason = if (!tile.enabled) tile.sub else null
    Row(
        modifier
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.hairline, shape)
            .clickable(role = Role.Button, onClick = tile.onClick)
            .semantics(mergeDescendants = true) { if (reason != null) stateDescription = reason }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Off is shown by colour and by the words under the name, never by fading the text below 4.5:1.
        IconCircle(
            tile.icon, 40.dp, 22.dp,
            background = if (tile.enabled) t.accentContainer else t.fill,
            tint = if (tile.enabled) t.onAccentContainer else t.textDim,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(tile.label, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = if (tile.enabled) t.text else t.textDim)
            tile.sub?.let { Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = t.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// ---- more actions -------------------------------------------------------------------------------------

/** What is rarer, one tap away and quieter: a link, a ping, this phone's screen (and the unlock, when the computer allows it). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreActions(state: HomeState, gate: (() -> Unit) -> Unit, actions: HomeActions, onSendLink: () -> Unit) {
    val ready = state.ready
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(R.string.home_more_actions))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(R.drawable.sym_link, stringResource(R.string.home_send_link), ready) { gate(onSendLink) }
            ActionChip(R.drawable.sym_waving_hand, stringResource(R.string.home_ping_desk), ready) { gate(actions.onPing) }
            ActionChip(
                R.drawable.sym_mobile_screen_share,
                stringResource(if (state.live.phoneScreen) R.string.home_stop_sharing else R.string.home_share_screen),
                ready,
            ) { gate(actions.onShareScreen) }
            if (state.canUnlock) ActionChip(R.drawable.sym_lock_open, stringResource(R.string.home_unlock_desk, state.name), true) { actions.onUnlockDesk() }
        }
    }
}

@Composable
private fun ActionChip(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val t = tokens
    val shape = RoundedCornerShape(50)
    val unavailable = stringResource(R.string.home_action_unavailable)
    val color = if (enabled) t.text else t.textDim
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .border(1.dp, t.hairlineStrong, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { if (!enabled) stateDescription = unavailable }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(label, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

// ---- happening now ------------------------------------------------------------------------------------

/** What is running between the phone and the computer at this moment; absent when nothing is. */
@Composable
private fun HappeningNow(state: HomeState, actions: HomeActions, cardArt: androidx.compose.runtime.MutableState<androidx.compose.ui.geometry.Rect?>, onOpenPlayer: () -> Unit) {
    // animateContentSize: a session starting or a transfer ending moves what is below it smoothly.
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (HomeDerive.happeningNow(state)) {
            SectionLabel(stringResource(R.string.home_happening_now))
            if (state.live.any) LiveRow(state.live, actions.onEndLive)
            state.transfer?.let { TransferRow(it, actions) }
            state.media?.let { MediaCard(it, actions, cardArt, onOpenPlayer) }
        }
    }
}

@Composable
private fun LiveRow(live: LiveSessions, onEnd: () -> Unit) {
    val t = tokens
    val parts = buildList {
        if (live.screen) add(stringResource(R.string.home_live_screen))
        if (live.terminals > 0) add(pluralStringResource(R.plurals.home_live_terminals, live.terminals, live.terminals))
        if (live.phoneScreen) add(stringResource(R.string.home_live_phone_screen))
        live.audio?.let { add(stringResource(if (it == "mic") R.string.home_live_mic else R.string.home_live_sound)) }
        if (live.hearing) add(stringResource(R.string.home_live_hearing))
        if (live.glide) add(stringResource(R.string.home_live_glide))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(t.coralContainer, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            parts.forEach { part ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(8.dp).background(t.coral, CircleShape))
                    Text(part.replaceFirstChar { it.uppercase() }, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                }
            }
        }
        // 34dp visible, 48dp to touch.
        Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onEnd), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.active_session_end),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = t.coralText,
                modifier = Modifier.background(t.surface, RoundedCornerShape(17.dp)).padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun TransferRow(transfer: HomeTransfer, actions: HomeActions) {
    val t = tokens
    val shape = RoundedCornerShape(20.dp)
    val canCancel = HomeDerive.canCancel(transfer)
    // At a large system font the Cancel button goes under the file, and the percentage under its name.
    val big = LocalDensity.current.fontScale > 1.4f
    val cancel: @Composable () -> Unit = {
        TextButton(onClick = actions.onCancelTransfer, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.cancel), color = t.accentText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
    val file: @Composable (Modifier) -> Unit = { modifier ->
        Row(
            modifier
                .clickable(role = Role.Button, onClick = actions.onOpenFiles)
                .semantics(mergeDescendants = true) {}
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = if (canCancel && !big) 0.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconCircle(if (transfer.upload) R.drawable.sym_upload else R.drawable.sym_download, 36.dp, 20.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val name: @Composable (Modifier) -> Unit = { Text(transfer.name, fontFamily = MonoFamily, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.MiddleEllipsis, modifier = it) }
                val label: @Composable () -> Unit = { Text(transfer.label, fontSize = 12.sp, color = t.textDim) }
                if (big) {
                    name(Modifier)
                    label()
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        name(Modifier.weight(1f))
                        label()
                    }
                }
                if (transfer.progress == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp), color = t.accent, trackColor = t.fill, gapSize = 0.dp)
                } else {
                    LinearProgressIndicator({ transfer.progress }, Modifier.fillMaxWidth().height(4.dp), color = t.accent, trackColor = t.fill, drawStopIndicator = {}, gapSize = 0.dp)
                }
            }
        }
    }
    if (big) {
        Column(Modifier.fillMaxWidth().clip(shape).background(t.surface).border(1.dp, t.hairline, shape)) {
            file(Modifier.fillMaxWidth())
            if (canCancel) Box(Modifier.fillMaxWidth().padding(end = 8.dp), contentAlignment = Alignment.CenterEnd) { cancel() }
        }
    } else {
        Row(Modifier.fillMaxWidth().clip(shape).background(t.surface).border(1.dp, t.hairline, shape), verticalAlignment = Alignment.CenterVertically) {
            file(Modifier.weight(1f))
            if (canCancel) cancel()
        }
    }
}

// ---- recent -------------------------------------------------------------------------------------------

/** The last few files sent or received, each one tap from open. */
@Composable
private fun RecentFiles(state: HomeState, actions: HomeActions) {
    val t = tokens
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(R.string.home_recent_files)) {
            if (HomeDerive.moreFiles(state)) {
                TextButton(onClick = actions.onOpenFiles, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.home_see_all), color = t.accentText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Column(Modifier.fillMaxWidth().clip(shape).background(t.surface).border(1.dp, t.hairline, shape).animateContentSize()) {
            if (!HomeDerive.showRecentFiles(state)) {
                Text(
                    stringResource(R.string.home_recent_files_empty, state.name),
                    fontSize = 13.sp, lineHeight = 19.sp, color = t.textDim,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
            // One clock for the whole list, once a minute: the rest of Home does not recompose for "2 min ago".
            val now by minuteClock()
            state.recent.forEachIndexed { index, record ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(t.hairline))
                RecentFileRow(record, now) { actions.onOpenRecent(record) }
            }
        }
    }
}

@Composable
private fun minuteClock() = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(60_000)
        value = System.currentTimeMillis()
    }
}

@Composable
private fun RecentFileRow(record: TransferRecord, now: Long, onOpen: () -> Unit) {
    val t = tokens
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { 44.dp.roundToPx() }
    val preview by rememberFilePreview(record, sizePx)
    val kind = previewKind(record.mime, record.name)
    val whenText = android.text.format.DateUtils.getRelativeTimeSpanString(record.time, now, android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    val direction = stringResource(if (record.sent) R.string.home_transfer_sent else R.string.home_transfer_received)
    val meta = listOf(direction, whenText, org.desklink.mobile.helpers.Sizes.iec(record.size)).joinToString(" · ")
    val openLabel = stringResource(R.string.home_open_file)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen)
            .semantics(mergeDescendants = true) {}
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(t.accentContainer), contentAlignment = Alignment.Center) {
            val bitmap = preview.bitmap
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(painterResource(iconFor(kind)), contentDescription = null, tint = t.onAccentContainer, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(record.name, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(meta, fontSize = 12.sp, lineHeight = 16.sp, color = t.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(openLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = t.accentText)
        }
    }
}

/** "Showing 3 notifications from arch-desk here": only while some of the computer's are in the shade. */
@Composable
private fun MirroredLine(state: HomeState) {
    if (!HomeDerive.showMirrored(state)) return
    val t = tokens
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, t.hairline, shape)
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(R.drawable.sym_notifications), contentDescription = null, tint = t.textDim, modifier = Modifier.size(20.dp))
        Text(
            pluralStringResource(R.plurals.home_mirrored, state.deskNotifications, state.deskNotifications, state.name),
            fontSize = 13.sp, lineHeight = 18.sp, color = t.textDim,
        )
    }
}

// ---- the computers sheet ---------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComputersSheet(state: HomeState, onDismiss: () -> Unit, actions: HomeActions) {
    val t = tokens
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.surface2,
        // The stock handle: TalkBack names it and it expands or collapses the sheet.
            dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle(width = 32.dp, height = 4.dp, color = t.hairlineStrong) },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.home_your_computers),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = t.textDim,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
            )
            state.computers.forEach { computer ->
                val selected = computer.id == state.deviceId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) t.accentContainer else t.surface)
                        .clickable(role = Role.Button) { onDismiss(); actions.onSelectComputer(computer.id) }
                        .semantics(mergeDescendants = true) { this.selected = selected }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val fg = if (selected) t.onAccentContainer else t.text
                    Box(
                        Modifier.size(40.dp).background(if (selected) t.surface else t.fill, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) { LaptopGlyph(if (selected) fg else t.textDim) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(computer.name, fontFamily = MonoFamily, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(connectionShort(computer.connection), fontSize = 12.sp, color = if (selected) fg else t.textDim)
                    }
                    if (selected) Icon(painterResource(R.drawable.sym_check_circle), contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(role = Role.Button) { onDismiss(); actions.onPairAnother() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(40.dp).border(1.dp, t.hairlineStrong, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.sym_add), contentDescription = null, tint = t.accentText, modifier = Modifier.size(22.dp))
                }
                Text(stringResource(R.string.home_pair_another), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = t.accentText)
            }
        }
    }
}

/** A link to open on the computer: shared as `share.request` with `url`, like Android's share sheet. */
@Composable
private fun SendLinkDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val url = normalizedUrl(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_send_link_title)) },
        text = {
            // At a large font size, or sideways with the keyboard up, the field must stay reachable.
            Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(2048) },
                singleLine = true,
                placeholder = { Text("https://", fontFamily = MonoFamily) },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = MonoFamily, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            }
        },
        confirmButton = { TextButton(onClick = { url?.let(onSend) }, enabled = url != null) { Text(stringResource(R.string.home_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "example.com/x" gains https://; anything that is still not an http(s) URL is refused. */
fun normalizedUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val uri = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
    return withScheme.takeIf { uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() }
}
