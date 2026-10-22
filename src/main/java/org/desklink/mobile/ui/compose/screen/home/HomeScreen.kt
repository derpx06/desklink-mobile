/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.components.IconCircle
import org.desklink.mobile.ui.compose.components.LaptopGlyph
import org.desklink.mobile.ui.compose.components.LaptopIllustration
import org.desklink.mobile.ui.compose.components.LinkGlyph
import org.desklink.mobile.ui.compose.components.LinkGlyphState
import org.desklink.mobile.ui.compose.components.PillButton
import org.desklink.mobile.ui.compose.components.PillStyle
import org.desklink.mobile.ui.compose.components.SectionLabel
import org.desklink.mobile.ui.compose.components.deskSwitchColors
import org.desklink.mobile.ui.compose.components.linkGlyphState
import org.desklink.mobile.ui.compose.components.rememberReducedMotion
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.tokens
import org.desklink.mobile.webrtc.WebRtcConnectionPath

/** A paired computer as the switcher sheet lists it. */
@Immutable
data class ComputerRow(val id: String, val name: String, val connection: DeviceConnection)

/** The newest file transfer with this computer. */
@Immutable
data class HomeTransfer(val name: String, val label: String, val progress: Float?, val upload: Boolean)

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
) {
    val ready get() = connection == DeviceConnection.LOCAL || connection == DeviceConnection.AWAY || connection == DeviceConnection.CONNECTED
}

class HomeActions(
    val onSelectComputer: (String) -> Unit,
    val onPairAnother: () -> Unit,
    val onSettings: () -> Unit,
    val onRetry: () -> Unit,
    val onEndLive: () -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onVolume: (Int) -> Unit,
    val onSelectPlayer: (String) -> Unit,
    val onTerminals: () -> Unit,
    val onDeskScreen: () -> Unit,
    val onSendFiles: () -> Unit,
    val onSendLink: (String) -> Unit,
    val onLock: () -> Unit,
    val onShareScreen: () -> Unit,
    val onPing: () -> Unit,
    val onRing: () -> Unit,
    val onTransfers: () -> Unit,
    val onSendClipboard: () -> Unit,
    val onClipboard: (Boolean) -> Unit,
    val onUnlockDesk: () -> Unit,
    /** An action was tapped while the computer cannot take it; the message says why. */
    val onUnavailable: (String) -> Unit,
    val onNotes: () -> Unit = {},
    val onNote: (String) -> Unit = {},
    val onNewNote: () -> Unit = {},
    val onBucket: () -> Unit = {},
    val onDeskSound: (Boolean) -> Unit = {},
)

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    val t = tokens
    var sheet by rememberSaveable { mutableStateOf(false) }
    var linkDialog by rememberSaveable { mutableStateOf(false) }
    val notReady = notReadyMessage(state)
    val gate = { action: () -> Unit -> if (state.ready) action() else actions.onUnavailable(notReady) }

    var playerOpen by rememberSaveable { mutableStateOf(false) }
    // Where the card's artwork is, for the player to grow from.
    val cardArt = remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                DeviceChip(state.name, state.connection) { sheet = true }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = actions.onSettings, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.sym_settings), contentDescription = stringResource(R.string.settings), tint = t.text)
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StatusCard(state, actions)
                // One place, playing or paused: directly under the status card.
                state.media?.let { MediaCard(it, actions, cardArt) { playerOpen = true } }
                SectionLabel(stringResource(R.string.home_open_on, state.name))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TerminalsTile(state, Modifier.weight(1f)) { gate(actions.onTerminals) }
                    DeskTile(state.ready, Modifier.weight(1f)) { gate(actions.onDeskScreen) }
                }
                // Local-first: Notes and the Bucket show whether or not the computer is there.
                org.desklink.mobile.plugins.notes.NotesHomeCard(onOpenList = actions.onNotes, onOpenNote = actions.onNote, onNew = actions.onNewNote)
                org.desklink.mobile.plugins.folders.BucketHomeCard(state.deviceId, state.name, onOpen = actions.onBucket)
                SectionLabel(stringResource(R.string.home_quick_actions))
                QuickActions(state, gate, actions) { linkDialog = true }
                TransferAndClipboard(state, actions)
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars).padding(bottom = 6.dp))
            }
        }
        NowPlayingOverlay(state.media, actions, cardArt, playerOpen && state.media != null) { playerOpen = false }
    }

    if (sheet) ComputersSheet(state, onDismiss = { sheet = false }, actions)
    if (linkDialog) SendLinkDialog(onDismiss = { linkDialog = false }) { url -> linkDialog = false; actions.onSendLink(url) }
}

@Composable
private fun notReadyMessage(state: HomeState) = stringResource(
    if (state.connection == DeviceConnection.CONNECTING) R.string.home_unavailable_connecting else R.string.home_unavailable_offline,
    state.name,
)

@Composable
private fun DeviceChip(name: String, connection: DeviceConnection, onClick: () -> Unit) {
    val t = tokens
    val status = connectionShort(connection)
    val description = stringResource(R.string.home_switch_computer, name, status)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .height(40.dp)
                .background(t.surface, RoundedCornerShape(20.dp))
                .border(1.dp, t.hairline, RoundedCornerShape(20.dp))
                .padding(start = 6.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(28.dp).background(t.fill, CircleShape), contentAlignment = Alignment.Center) {
                LaptopGlyph(t.text, Modifier.padding(bottom = 1.dp).size(width = 21.dp, height = 14.dp))
            }
            Text(name, fontFamily = MonoFamily, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(IntrinsicSize.Max).padding(end = 0.dp))
            Icon(painterResource(R.drawable.sym_expand_more), contentDescription = null, tint = t.textDim, modifier = Modifier.size(20.dp))
        }
    }
}

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
    },
)

// ---- status card ------------------------------------------------------------------------------------

@Composable
private fun StatusCard(state: HomeState, actions: HomeActions) {
    val t = tokens
    val glyph = linkGlyphState(state.connection, state.path)
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(0f to t.accentContainer, 0.8f to t.surface))
            .border(1.dp, t.hairline, shape)
            .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            LaptopIllustration(t.text)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinkGlyph(glyph, Modifier.align(Alignment.Top).padding(top = 6.dp))
                    Text(connectionLabel(state.connection, state.path), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                }
                val meta = when (glyph) {
                    LinkGlyphState.CONNECTING -> stringResource(if (state.relayAvailable) R.string.home_meta_connecting else R.string.home_meta_connecting_lan)
                    LinkGlyphState.OFFLINE -> null
                    LinkGlyphState.RELAY -> state.rttMs?.let { stringResource(R.string.home_meta_relay, it) } ?: stringResource(R.string.home_meta_encrypted)
                    LinkGlyphState.DIRECT -> state.rttMs?.let { stringResource(R.string.home_meta_rtt, it) }
                }
                meta?.let { Text(it, fontFamily = MonoFamily, fontSize = 12.sp, color = t.textDim) }
            }
        }
        when (glyph) {
            LinkGlyphState.OFFLINE -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.home_offline_body, state.name), fontSize = 14.sp, lineHeight = 20.sp, color = t.textDim)
                PillButton(stringResource(R.string.device_try_again), actions.onRetry, Modifier.fillMaxWidth(), style = PillStyle.Outlined, height = 44.dp)
            }
            LinkGlyphState.CONNECTING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ConnectingDots()
                Text(
                    if (state.relayAvailable) stringResource(R.string.home_trying_paths) else stringResource(R.string.home_looking_on_wifi, state.name),
                    fontSize = 13.sp,
                    color = t.textDim,
                )
            }
            else -> if (state.canUnlock) {
                PillButton(stringResource(R.string.home_unlock_desk, state.name), actions.onUnlockDesk, Modifier.fillMaxWidth(), style = PillStyle.Outlined, height = 44.dp, icon = R.drawable.sym_lock_open)
            }
        }
        if (state.live.any) LiveRow(state.live, actions.onEndLive)
    }
}

@Composable
private fun ConnectingDots() {
    val reduced = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val alpha = if (reduced) 1f else transition.animateFloat(1f, 0.25f, infiniteRepeatable(tween(500, delayMillis = i * 200), RepeatMode.Reverse), label = "dot$i").value
            Box(Modifier.size(6.dp).alpha(alpha).background(tokens.accent, CircleShape))
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
            .background(t.coralContainer, RoundedCornerShape(18.dp))
            .padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(8.dp).background(t.coral, CircleShape))
        Text(parts.joinToString(", ").replaceFirstChar { it.uppercase() }, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
        // 34dp visible, 48dp to touch.
        Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onEnd), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.active_session_end),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = t.coralText,
                modifier = Modifier
                    .background(t.surface, RoundedCornerShape(17.dp))
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }
    }
}

// ---- media --------------------------------------------------------------------------------------------

// ---- open on the desk -------------------------------------------------------------------------------

private val TileBlack = Color(0xFF0E0F11)
private val TileText = Color(0xFFF2F2F3)
private val TileDim = Color(0xFF8B9099)

@Composable
private fun TerminalsTile(state: HomeState, modifier: Modifier, onClick: () -> Unit) {
    val description = pluralStringResource(R.plurals.home_terminals_tile, state.terminalCount, state.terminalCount)
    Column(
        modifier
            .height(156.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(TileBlack)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(R.drawable.sym_terminal, 40.dp, 22.dp, background = Color.White.copy(alpha = 0.1f), tint = TileText)
            Spacer(Modifier.weight(1f))
            if (state.terminalCount > 0) {
                Text(
                    state.terminalCount.toString(),
                    fontFamily = MonoFamily,
                    fontSize = 12.sp,
                    color = TileText,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            if (state.terminalLines.isNotEmpty()) {
                state.terminalLines.take(2).forEach {
                    Text(it, fontFamily = MonoFamily, fontSize = 10.5.sp, lineHeight = 15.sp, color = TileDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                // No screen open on this phone yet: the newest session and where it runs.
                state.terminalNewest?.let {
                    Text(it, fontFamily = MonoFamily, fontSize = 11.sp, lineHeight = 15.sp, color = TileText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.terminal_running_on, state.name), fontSize = 10.5.sp, lineHeight = 15.sp, color = TileDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(stringResource(R.string.terminal_section), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = TileText)
    }
}

@Composable
private fun DeskTile(ready: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = tokens
    Column(
        modifier
            .height(156.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(t.accentContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(16.dp),
    ) {
        IconCircle(R.drawable.sym_desktop_windows, 40.dp, 22.dp, background = t.surface, tint = t.onAccentContainer)
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.home_desk_screen), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = t.onAccentContainer)
        Text(stringResource(R.string.home_desk_screen_sub), fontSize = 12.sp, color = t.onAccentContainer, modifier = Modifier.padding(top = 2.dp))
    }
}

// ---- quick actions --------------------------------------------------------------------------------------

@Composable
private fun QuickActions(state: HomeState, gate: (() -> Unit) -> Unit, actions: HomeActions, onSendLink: () -> Unit) {
    val tiles = listOf(
        Triple(R.drawable.sym_upload_file, R.string.home_send_files, actions.onSendFiles),
        Triple(R.drawable.sym_link, R.string.home_send_link, onSendLink),
        Triple(R.drawable.sym_lock, R.string.home_lock_desk, actions.onLock),
        Triple(R.drawable.sym_mobile_screen_share, if (state.live.phoneScreen) R.string.home_stop_sharing else R.string.home_share_screen, actions.onShareScreen),
        Triple(R.drawable.sym_waving_hand, R.string.home_ping_desk, actions.onPing),
        Triple(R.drawable.sym_vibration, R.string.home_ring_desk, actions.onRing),
        Triple(R.drawable.sym_swap_vert, R.string.transfers_title, actions.onTransfers),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (icon, label, onClick) ->
                    // The history is on this phone: it opens whether or not the computer is there.
                    QuickTile(icon, stringResource(label), state.ready, Modifier.weight(1f).fillMaxHeight()) { if (label == R.string.transfers_title) onClick() else gate(onClick) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QuickTile(@DrawableRes icon: Int, label: String, ready: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = tokens
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.hairline, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconCircle(icon, 32.dp, 19.dp)
        Text(label, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2)
    }
}

// ---- transfer and clipboard --------------------------------------------------------------------------------

@Composable
private fun TransferAndClipboard(state: HomeState, actions: HomeActions) {
    val t = tokens
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(t.surface)
            .border(1.dp, t.hairline, RoundedCornerShape(24.dp)),
    ) {
        state.transfer?.let { transfer ->
            Row(
                Modifier
                    .clickable(role = Role.Button, onClick = actions.onTransfers)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconCircle(if (transfer.upload) R.drawable.sym_upload else R.drawable.sym_download, 36.dp, 20.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(transfer.name, fontFamily = MonoFamily, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(transfer.label, fontSize = 11.sp, color = t.textDim, maxLines = 1)
                    }
                    if (transfer.progress == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp), color = t.accent, trackColor = t.fill, gapSize = 0.dp)
                    } else {
                        LinearProgressIndicator({ transfer.progress }, Modifier.fillMaxWidth().height(4.dp), color = t.accent, trackColor = t.fill, drawStopIndicator = {}, gapSize = 0.dp)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.hairline))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = actions.onTransfers)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconCircle(R.drawable.sym_swap_vert, 36.dp, 20.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.transfers_title), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(pluralStringResource(R.plurals.home_transfers_count, state.historyCount, state.historyCount), fontSize = 12.sp, color = t.textDim)
            }
            Icon(painterResource(R.drawable.sym_chevron_right), contentDescription = null, tint = t.textDim, modifier = Modifier.size(20.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.hairline))
        val clipboard = stringResource(R.string.home_clipboard_sync)
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = state.clipboardOn, role = Role.Switch, onValueChange = actions.onClipboard)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconCircle(R.drawable.sym_content_paste, 36.dp, 20.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(clipboard, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (state.clipboardOn) org.desklink.mobile.ui.compose.screen.settings.clipboardSummary(state.name) else stringResource(R.string.home_clipboard_off),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = t.textDim,
                )
            }
            Switch(checked = state.clipboardOn, onCheckedChange = null, colors = deskSwitchColors())
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.hairline))
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = state.deskSoundOn, role = Role.Switch, onValueChange = actions.onDeskSound)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconCircle(R.drawable.sym_volume_up, 36.dp, 20.dp)
            Text(stringResource(R.string.settings_desk_sound, state.name), fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Switch(checked = state.deskSoundOn, onCheckedChange = null, colors = deskSwitchColors())
        }
        // Android 10+ lets only the app in front read the clipboard: the phone's side is a tap.
        if (state.clipboardOn && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.hairline))
            val notReady = notReadyMessage(state)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { if (state.ready) actions.onSendClipboard() else actions.onUnavailable(notReady) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconCircle(R.drawable.sym_upload, 36.dp, 20.dp)
                Text(stringResource(R.string.home_send_clipboard, state.name), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = t.accentText, modifier = Modifier.weight(1f))
            }
        }
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
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        Text(computer.name, fontFamily = MonoFamily, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(2048) },
                singleLine = true,
                placeholder = { Text("https://", fontFamily = MonoFamily) },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = MonoFamily, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
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
