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
import androidx.compose.ui.semantics.stateDescription
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
)

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    val t = tokens
    var sheet by rememberSaveable { mutableStateOf(false) }
    var moreSheet by rememberSaveable { mutableStateOf(false) }
    var linkDialog by rememberSaveable { mutableStateOf(false) }
    val notReady = notReadyMessage(state)
    val gate = { action: () -> Unit -> if (state.ready) action() else actions.onUnavailable(notReady) }
    // The Home tab's long-press asks for the computers sheet.
    androidx.compose.runtime.LaunchedEffect(actions.openComputersRequest) { if (actions.openComputersRequest > 0) sheet = true }

    var playerOpen by rememberSaveable { mutableStateOf(false) }
    // Where the card's artwork is, for the player to grow from.
    val cardArt = remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                DeviceChip(state.name, state.connection) { sheet = true }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StatusCard(state, actions)
                PrimaryActions(state, gate, actions)
                // One place, playing or paused: directly under the actions.
                state.media?.let { MediaCard(it, actions, cardArt) { playerOpen = true } }
                // Local-first: Notes and the Bucket show whether or not the computer is there.
                org.desklink.mobile.plugins.notes.NotesHomeCard(onOpenList = actions.onNotes, onOpenNote = actions.onNote, onNew = actions.onNewNote)
                FilesCard(state, actions)
                MoreActionsRow { moreSheet = true }
                Spacer(Modifier.height(12.dp))
            }
        }
        NowPlayingOverlay(state.media, actions, cardArt, playerOpen && state.media != null) { playerOpen = false }
    }

    if (sheet) ComputersSheet(state, onDismiss = { sheet = false }, actions)
    if (moreSheet) MoreActionsSheet(state, gate, actions, onDismiss = { moreSheet = false }, onSendLink = { moreSheet = false; linkDialog = true })
    if (linkDialog) SendLinkDialog(onDismiss = { linkDialog = false }) { url -> linkDialog = false; actions.onSendLink(url) }
}

@Composable
private fun notReadyMessage(state: HomeState) = stringResource(
    when (state.connection) {
        DeviceConnection.CONNECTING -> R.string.home_unavailable_connecting
        DeviceConnection.UNAVAILABLE -> R.string.home_unavailable_unreachable
        else -> R.string.home_unavailable_offline
    },
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
        DeviceConnection.UNAVAILABLE -> R.string.connection_unavailable
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

// ---- primary actions ----------------------------------------------------------------------------------

/** The four things people do most: the desk's screen, send files, ring the desk, lock it. */
@Composable
private fun PrimaryActions(state: HomeState, gate: (() -> Unit) -> Unit, actions: HomeActions) {
    val tiles = listOf(
        Triple(R.drawable.sym_desktop_windows, R.string.home_desk_screen, actions.onDeskScreen),
        Triple(R.drawable.sym_upload_file, R.string.home_send_files, actions.onSendFiles),
        Triple(R.drawable.sym_vibration, R.string.home_ring_desk, actions.onRing),
        Triple(R.drawable.sym_lock, R.string.home_lock_desk, actions.onLock),
    )
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEachIndexed { index, (icon, label, onClick) ->
            ActionTile(icon, stringResource(label), index == 0, state.ready, Modifier.weight(1f).fillMaxHeight()) { gate(onClick) }
        }
    }
}

@Composable
private fun ActionTile(@DrawableRes icon: Int, label: String, primary: Boolean, ready: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = tokens
    val shape = RoundedCornerShape(20.dp)
    val bg = if (primary) t.accentContainer else t.surface
    Column(
        modifier
            .heightIn(min = 84.dp)
            .clip(shape)
            .background(bg)
            .then(if (primary) Modifier else Modifier.border(1.dp, t.hairline, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { if (!ready) stateDescription = "unavailable" }
            .padding(horizontal = 6.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = if (primary) t.onAccentContainer else t.text, modifier = Modifier.size(26.dp))
        Text(
            label,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            color = if (primary) t.onAccentContainer else t.text,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2,
        )
    }
}

// ---- files card ---------------------------------------------------------------------------------------

/** The Bucket's summary, and the file moving now: both open the Files tab. */
@Composable
private fun FilesCard(state: HomeState, actions: HomeActions) {
    val t = tokens
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.transfer?.let { transfer ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(t.surface)
                    .border(1.dp, t.hairline, RoundedCornerShape(20.dp))
                    .clickable(role = Role.Button, onClick = actions.onOpenFiles)
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
        }
        org.desklink.mobile.plugins.folders.BucketHomeCard(state.deviceId, state.name, onOpen = actions.onBucket)
    }
}

// ---- more actions -------------------------------------------------------------------------------------

@Composable
private fun MoreActionsRow(onClick: () -> Unit) {
    val t = tokens
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .border(1.dp, t.hairline, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconCircle(R.drawable.sym_tune, 32.dp, 19.dp)
        Text(stringResource(R.string.home_more_actions), fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(painterResource(R.drawable.sym_chevron_right), contentDescription = null, tint = t.textDim, modifier = Modifier.size(20.dp))
    }
}

/** What is rarer: a link, a ping, this phone's screen (and the unlock, when the computer allows it). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreActionsSheet(state: HomeState, gate: (() -> Unit) -> Unit, actions: HomeActions, onDismiss: () -> Unit, onSendLink: () -> Unit) {
    val t = tokens
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.surface2,
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle(width = 32.dp, height = 4.dp, color = t.hairlineStrong) },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.home_more_actions), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = t.textDim, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
            val rows = buildList {
                add(Triple(R.drawable.sym_link, stringResource(R.string.home_send_link), { gate(onSendLink) }))
                add(Triple(R.drawable.sym_waving_hand, stringResource(R.string.home_ping_desk), { onDismiss(); gate(actions.onPing) }))
                add(
                    Triple(
                        R.drawable.sym_mobile_screen_share,
                        stringResource(if (state.live.phoneScreen) R.string.home_stop_sharing else R.string.home_share_screen),
                        { onDismiss(); gate(actions.onShareScreen) },
                    ),
                )
                if (state.canUnlock) add(Triple(R.drawable.sym_lock_open, stringResource(R.string.home_unlock_desk, state.name), { onDismiss(); actions.onUnlockDesk() }))
            }
            rows.forEach { (icon, label, onClick) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(t.surface)
                        .clickable(role = Role.Button, onClick = onClick)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    IconCircle(icon, 36.dp, 20.dp)
                    Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                }
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
