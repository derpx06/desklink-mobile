/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.home


import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
import org.desklink.mobile.ui.compose.DeskLinkShape
import org.desklink.mobile.ui.compose.DeskLinkType
import org.desklink.mobile.ui.compose.DeskTextButton
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.PrimaryButton
import org.desklink.mobile.ui.compose.SecondaryButton
import org.desklink.mobile.ui.compose.components.ActionTile
import org.desklink.mobile.ui.compose.components.DeskBottomSheet
import org.desklink.mobile.ui.compose.components.DeskProgressBar
import org.desklink.mobile.ui.compose.components.HeroCard
import org.desklink.mobile.ui.compose.components.IconCircle
import org.desklink.mobile.ui.compose.components.LaptopGlyph
import org.desklink.mobile.ui.compose.components.LaptopIllustration
import org.desklink.mobile.ui.compose.components.LinkGlyphState
import org.desklink.mobile.ui.compose.components.LiveBanner
import org.desklink.mobile.ui.compose.components.QuickActionTile
import org.desklink.mobile.ui.compose.components.RowGroup
import org.desklink.mobile.ui.compose.components.SectionHeader
import org.desklink.mobile.ui.compose.components.StatusDot
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

/** What the connected computer's DeskLink can do (a feature it lacks is not offered by the palette). All on until known. */
@Immutable
data class HomeOffers(
    val files: Boolean = true,
    val clipboard: Boolean = true,
    val link: Boolean = true,
    val screen: Boolean = true,
    val terminal: Boolean = true,
    val ring: Boolean = true,
    val ping: Boolean = true,
    val lock: Boolean = true,
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
)

/** From this width the screen has two columns: what you can do on the left, what is going on on the right. */
private val TWO_COLUMNS = 560.dp

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    val t = tokens
    var sheet by rememberSaveable { mutableStateOf(false) }
    var linkDialog by rememberSaveable { mutableStateOf(false) }
    var palette by rememberSaveable { mutableStateOf(false) }
    val notReady = notReadyMessage(state)
    val gate = { action: () -> Unit -> if (state.ready) action() else actions.onUnavailable(notReady) }
    // The Home tab's long-press asks for the computers sheet.
    androidx.compose.runtime.LaunchedEffect(actions.openComputersRequest) {
        if (actions.openComputersRequest > 0) {
            sheet = true
            actions.onComputersShown()
        }
    }

    var playerOpen by rememberSaveable { mutableStateOf(false) }
    // Where the card's artwork is, for the player to grow from.
    val cardArt = remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val quiet = linkGlyphState(state.connection, state.path) == LinkGlyphState.OFFLINE
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
            val two = maxWidth >= TWO_COLUMNS
            val doing: @Composable () -> Unit = {
                if (quiet) {
                    OfflineHero(state, actions) { sheet = true }
                    ThingsToCheck(state, actions)
                    StillWorksOffline(state, actions)
                } else {
                    Hero(state, gate, actions) { sheet = true }
                    QuickActions(state, gate, actions, notReady, onAll = { palette = true }, onSendLink = { linkDialog = true })
                }
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
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Greeting(onSearch = { palette = true })
                if (two) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { doing() }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { going() }
                    }
                } else {
                    // What is going on comes right after the hero when something is: it is what you opened the app to check or stop.
                    if (quiet) {
                        doing()
                        going()
                    } else {
                        Hero(state, gate, actions) { sheet = true }
                        HappeningNow(state, actions, cardArt) { playerOpen = true }
                        QuickActions(state, gate, actions, notReady, onAll = { palette = true }, onSendLink = { linkDialog = true })
                        RecentFiles(state, actions)
                        org.desklink.mobile.plugins.notes.NotesHomeCard(onOpenList = actions.onNotes, onOpenNote = actions.onNote, onNew = actions.onNewNote, limit = HomeDerive.RECENT_NOTES)
                        MirroredLine(state)
                        org.desklink.mobile.plugins.folders.BucketHomeCard(state.deviceId, state.name, onOpen = actions.onBucket)
                    }
                }
            }
        }
        NowPlayingOverlay(state.media, actions, cardArt, playerOpen && state.media != null) { playerOpen = false }
        if (palette) PaletteScreen(state, actions, onClose = { palette = false }, onSendLink = { palette = false; linkDialog = true })
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

// ---- greeting -----------------------------------------------------------------------------------------

/** "Wednesday, 5 November" / "Good evening" and the search button. The minute clock is the only thing here that moves. */
@Composable
private fun Greeting(onSearch: () -> Unit) {
    val t = tokens
    val context = LocalContext.current
    val now by minuteClock()
    val date = android.text.format.DateUtils.formatDateTime(
        context, now, android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_SHOW_DATE,
    )
    val hour = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
    val greeting = stringResource(
        when {
            hour < 12 -> R.string.home_greeting_morning
            hour < 18 -> R.string.home_greeting_afternoon
            else -> R.string.home_greeting_evening
        },
    )
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(date, fontSize = 13.sp, lineHeight = 18.sp, color = t.textDim)
            Text(greeting, style = DeskLinkType.splash, maxLines = 2, modifier = Modifier.semantics { heading() })
        }
        val label = stringResource(R.string.home_search)
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onSearch), contentAlignment = Alignment.Center) {
            Box(Modifier.size(44.dp).background(t.surface, CircleShape).border(1.dp, t.hairline, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.sym_search), contentDescription = label, tint = t.text, modifier = Modifier.size(22.dp))
            }
        }
    }
}

// ---- hero ---------------------------------------------------------------------------------------------

/** The status as a small chip on the hero: a dot and the word in plain colour. */
@Composable
private fun StatusChip(text: String, dot: androidx.compose.ui.graphics.Color, ink: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier.heightIn(min = 26.dp).background(background, DeskLinkShape.stadium).padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        StatusDot(dot, size = 7.dp)
        Text(text, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = ink)
    }
}

/** The computer's name with the switcher chevron: one tap target for the whole block, one sentence for TalkBack. */
@Composable
private fun NameBlock(state: HomeState, onSwitch: () -> Unit, modifier: Modifier = Modifier, header: @Composable () -> Unit, footer: @Composable () -> Unit) {
    val t = tokens
    val switchDescription = stringResource(R.string.home_switch_computer, state.name, connectionShort(state.connection))
    val big = LocalDensity.current.fontScale > 1.4f
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .semantics(mergeDescendants = true) { contentDescription = switchDescription }
            .clickable(role = Role.Button, onClick = onSwitch)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        header()
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // The computer's name is what this card says: two lines before anything is cut; at the largest font between words.
            Text(
                state.name,
                fontFamily = MonoFamily,
                fontSize = if (big) 18.sp else 24.sp,
                lineHeight = if (big) 24.sp else 30.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(painterResource(R.drawable.sym_expand_more), contentDescription = null, tint = t.textDim, modifier = Modifier.size(24.dp))
        }
        footer()
    }
}

/**
 * The hero: how the computer is reached in plain words, its name (tap to switch), the round trip, and the three things
 * you came to do. Connecting and "update needed" use the same card with their sentence under the name.
 */
@Composable
private fun Hero(state: HomeState, gate: (() -> Unit) -> Unit, actions: HomeActions, onSwitch: () -> Unit) {
    val t = tokens
    val glyph = linkGlyphState(state.connection, state.path)
    val connecting = glyph == LinkGlyphState.CONNECTING
    val why = HomeDerive.whyNot(state)
    val off = why?.let { reasonText(it) }
    val notReady = notReadyMessage(state)
    val status = if (connecting) stringResource(R.string.connection_connecting) else connectionLabel(state.connection, state.path)
    val meta = when {
        glyph == LinkGlyphState.RELAY -> state.rttMs?.let { stringResource(R.string.home_meta_relay, it) } ?: stringResource(R.string.home_meta_encrypted)
        glyph == LinkGlyphState.DIRECT -> state.rttMs?.let { stringResource(R.string.home_meta_rtt, it) }
        else -> null
    }
    val detail = when {
        state.updateNeeded -> stringResource(R.string.home_update_deskLink, state.name)
        connecting -> stringResource(if (state.relayAvailable) R.string.home_trying_paths else R.string.home_looking_on_wifi, state.name)
        else -> null
    }
    HeroCard(Modifier.animateContentSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            NameBlock(
                state, onSwitch, Modifier.weight(1f),
                header = {
                    val ink = if (connecting) t.accentText else t.mintText
                    StatusChip(status, if (connecting) t.accent else t.mint, ink, t.surface)
                },
                footer = {
                    if (connecting) {
                        // A placeholder where the round trip will be, instead of a gap that jumps when it arrives.
                        Box(Modifier.padding(top = 6.dp).size(width = 96.dp, height = 10.dp).clip(RoundedCornerShape(5.dp)).shimmer())
                    }
                    meta?.let { Text(it, style = DeskLinkType.mono, color = t.textDim, modifier = Modifier.padding(top = 2.dp)) }
                    detail?.let { Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = t.textDim, modifier = Modifier.padding(top = 4.dp)) }
                },
            )
            if (LocalDensity.current.fontScale <= 1.4f) LaptopIllustration(Modifier.padding(top = 8.dp))
        }
        if (state.ready && state.canUnlock) {
            SecondaryButton(stringResource(R.string.home_unlock_desk, state.name), actions.onUnlockDesk, Modifier.fillMaxWidth(), icon = R.drawable.sym_lock_open, height = 48.dp)
        }
        // Three across; one column once the words would not fit. Terminals works with the computer away: it opens the tab, which keeps its own sessions.
        val terminalsHint = if (state.live.terminals > 0) pluralStringResource(R.plurals.home_live_terminals, state.live.terminals, state.live.terminals) else null
        val tiles: List<@Composable (Modifier) -> Unit> = listOf(
            { m -> ActionTile(R.drawable.sym_desktop_windows, stringResource(R.string.home_desk_screen), { gate(actions.onDeskScreen) }, m, primary = true, enabled = why == null, hint = off, onDisabledClick = { actions.onUnavailable(notReady) }) },
            { m -> ActionTile(R.drawable.sym_upload_file, stringResource(R.string.home_send_files), { gate(actions.onSendFiles) }, m, enabled = why == null, hint = off, onDisabledClick = { actions.onUnavailable(notReady) }) },
            { m -> ActionTile(R.drawable.sym_terminal, stringResource(R.string.tab_terminals), actions.onTerminals, m, hint = terminalsHint) },
        )
        if (LocalDensity.current.fontScale > 1.4f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { tiles.forEach { it(Modifier.fillMaxWidth()) } }
        } else {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

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

// ---- offline ------------------------------------------------------------------------------------------

/** The computer cannot be reached: what state it is in, the one button, and a faded picture of it. */
@Composable
private fun OfflineHero(state: HomeState, actions: HomeActions, onSwitch: () -> Unit) {
    val t = tokens
    val shape = DeskLinkShape.hero
    val word = stringResource(if (state.connection == DeviceConnection.UNAVAILABLE) R.string.connection_unavailable else R.string.connection_offline)
    val body = stringResource(if (state.connection == DeviceConnection.UNAVAILABLE) R.string.home_unavailable_unreachable else R.string.home_offline_body, state.name)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(t.surface).border(1.dp, t.hairline, shape).animateContentSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            NameBlock(
                state, onSwitch, Modifier.weight(1f),
                header = { StatusChip(word, t.textFaint, t.textDim, t.fill) },
                footer = { Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = t.textDim, modifier = Modifier.padding(top = 4.dp)) },
            )
            if (LocalDensity.current.fontScale <= 1.4f) LaptopIllustration(Modifier.padding(top = 8.dp).alpha(0.45f), dimmed = true)
        }
        PrimaryButton(stringResource(R.string.device_try_again), actions.onRetry, Modifier.fillMaxWidth(), icon = R.drawable.sym_refresh, height = 48.dp)
    }
}

private class Check(val title: String, val body: String, val action: String? = null, val onAction: () -> Unit = {})

/** Numbered things that are really worth a look, for this state: no guesses about networks the phone cannot know. */
@Composable
private fun ThingsToCheck(state: HomeState, actions: HomeActions) {
    val checks = buildList {
        if (state.updateNeeded) add(Check(stringResource(R.string.home_check_update, state.name), stringResource(R.string.home_check_update_body, state.name)))
        add(Check(stringResource(R.string.home_check_awake, state.name), stringResource(R.string.home_check_awake_body)))
        add(Check(stringResource(R.string.home_check_wifi), stringResource(R.string.home_check_wifi_body, state.name)))
        if (state.relayAvailable) add(Check(stringResource(R.string.home_check_away), stringResource(R.string.home_check_away_relay_on)))
        else add(Check(stringResource(R.string.home_check_away), stringResource(R.string.home_check_away_relay_off, state.name), stringResource(R.string.tab_settings)) { actions.onOpenSettings("connection") })
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(stringResource(R.string.home_things_to_check))
        RowGroup {
            checks.forEachIndexed { i, check -> NumberedRow(i + 1, check) }
        }
    }
}

@Composable
private fun NumberedRow(number: Int, check: Check) {
    val t = tokens
    val big = LocalDensity.current.fontScale > 1.4f
    Row(
        Modifier.fillMaxWidth().background(t.surface).semantics(mergeDescendants = true) {}.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(28.dp).background(t.fill, CircleShape), contentAlignment = Alignment.Center) {
            Text(number.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(check.title, fontSize = 15.sp, lineHeight = 20.sp)
            Text(check.body, fontSize = 13.sp, lineHeight = 18.sp, color = t.textDim)
            if (check.action != null && big) DeskTextButton(check.action, check.onAction, Modifier.padding(top = 4.dp))
        }
        if (check.action != null && !big) DeskTextButton(check.action, check.onAction)
    }
}

private class Tile(@DrawableRes val icon: Int, val label: String, val sub: String?, val enabled: Boolean, val onClick: () -> Unit)

/** What does not need the computer stays tappable; what does says so. */
@Composable
private fun StillWorksOffline(state: HomeState, actions: HomeActions) {
    val cant = stringResource(R.string.home_offline_cant_reach)
    val works = stringResource(R.string.home_offline_works)
    val unreachable = stringResource(R.string.home_unavailable_offline, state.name)
    val tiles = listOf(
        Tile(R.drawable.sym_upload_file, stringResource(R.string.home_send_files), cant, false) { actions.onUnavailable(unreachable) },
        Tile(R.drawable.sym_desktop_windows, stringResource(R.string.home_desk_screen), cant, false) { actions.onUnavailable(unreachable) },
        Tile(R.drawable.sym_terminal, stringResource(R.string.tab_terminals), works, true) { actions.onTerminals() },
        Tile(R.drawable.sym_edit_note, stringResource(R.string.tab_notes), works, true) { actions.onNotes() },
        Tile(R.drawable.sym_folder, stringResource(R.string.bucket_title), works, true) { actions.onBucket() },
    )
    val columns = if (LocalDensity.current.fontScale > 1.4f) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(stringResource(R.string.home_still_works))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { tile -> OfflineTile(tile, Modifier.weight(1f).fillMaxHeight(), unreachable) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun OfflineTile(tile: Tile, modifier: Modifier, unreachable: String) {
    val t = tokens
    val shape = DeskLinkShape.card
    Row(
        modifier
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.hairline, shape)
            .clickable(role = Role.Button, onClick = tile.onClick)
            .semantics(mergeDescendants = true) { if (!tile.enabled) stateDescription = unreachable }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Off is shown by colour and by the words under the name, never by fading the text below 4.5:1.
        IconCircle(
            tile.icon, 40.dp, 22.dp,
            background = if (tile.enabled) t.accentContainer else t.fill,
            tint = if (tile.enabled) t.onAccentContainer else t.text.copy(alpha = 0.38f),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(tile.label, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = if (tile.enabled) t.text else t.textDim)
            tile.sub?.let { Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = t.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// ---- quick actions ------------------------------------------------------------------------------------

/** The rarer actions, four to a row on one card (two at the largest font); "All actions" opens the palette. */
@Composable
private fun QuickActions(state: HomeState, gate: (() -> Unit) -> Unit, actions: HomeActions, notReady: String, onAll: () -> Unit, onSendLink: () -> Unit) {
    val t = tokens
    val o = state.offers
    // Until the link is up nothing is known about the computer, so every action is listed (dimmed); then only what it has.
    fun shown(offered: Boolean) = !state.ready || offered
    val sharing = state.live.phoneScreen
    class Q(val icon: Int, val label: String, val show: Boolean, val run: () -> Unit)
    val items = listOf(
        Q(R.drawable.sym_content_paste, stringResource(R.string.home_send_clipboard_short), shown(o.clipboard)) { gate(actions.onSendClipboard) },
        Q(R.drawable.sym_link, stringResource(R.string.home_send_link), shown(o.link)) { gate(onSendLink) },
        Q(R.drawable.sym_vibration, stringResource(R.string.home_ring_desk), shown(o.ring)) { gate(actions.onRing) },
        Q(R.drawable.sym_lock, stringResource(R.string.home_lock_desk), shown(o.lock)) { gate(actions.onLock) },
        Q(R.drawable.sym_mobile_screen_share, stringResource(if (sharing) R.string.home_stop_sharing else R.string.home_share_screen), shown(o.screen)) { gate(actions.onShareScreen) },
        Q(R.drawable.sym_waving_hand, stringResource(R.string.home_ping_desk), shown(o.ping)) { gate(actions.onPing) },
        Q(R.drawable.sym_lock_open, stringResource(R.string.home_unlock_desk, state.name), state.ready && state.canUnlock) { actions.onUnlockDesk() },
    ).filter { it.show }
    val clipboardLine = when {
        !state.ready || !o.clipboard -> null
        !state.clipboardOn -> stringResource(R.string.home_tile_clipboard_off)
        else -> state.clipboardPreview?.let { stringResource(R.string.home_tile_clipboard_preview, it) }
    }
    val columns = if (LocalDensity.current.fontScale > 1.4f) 2 else 4
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(stringResource(R.string.home_quick_actions)) { DeskTextButton(stringResource(R.string.home_all_actions), onAll) }
        val shape = DeskLinkShape.group
        Column(
            Modifier.fillMaxWidth().clip(shape).background(t.surface).border(1.dp, t.hairline, shape).padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { q ->
                        QuickActionTile(q.icon, q.label, q.run, Modifier.weight(1f), enabled = state.ready, onDisabledClick = { actions.onUnavailable(notReady) })
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            clipboardLine?.let {
                Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = t.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp))
            }
        }
    }
}

// ---- happening now ------------------------------------------------------------------------------------

/** What is running between the phone and the computer at this moment; absent when nothing is. */
@Composable
private fun HappeningNow(state: HomeState, actions: HomeActions, cardArt: androidx.compose.runtime.MutableState<androidx.compose.ui.geometry.Rect?>, onOpenPlayer: () -> Unit) {
    // animateContentSize: a session starting or a transfer ending moves what is below it smoothly.
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.live.any) LiveStrip(state.live, actions.onEndLive)
        if (state.terminalAttention) {
            LiveBanner(
                stringResource(R.string.home_terminal_needs), stringResource(R.string.home_open), actions.onTerminals,
                icon = R.drawable.sym_terminal, detail = stringResource(R.string.home_terminal_needs_detail),
            )
        }
        state.transfer?.let { TransferRow(it, actions) }
        state.media?.let { MediaCard(it, actions, cardArt, onOpenPlayer) }
    }
}

/** The coral strip for live sessions: the first thing live is its title, the rest "Also live", and End always. */
@Composable
private fun LiveStrip(live: LiveSessions, onEnd: () -> Unit) {
    val parts = buildList {
        if (live.screen) add(R.drawable.sym_desktop_windows to stringResource(R.string.home_live_screen))
        if (live.terminals > 0) add(R.drawable.sym_terminal to pluralStringResource(R.plurals.home_live_terminals, live.terminals, live.terminals))
        if (live.phoneScreen) add(R.drawable.sym_mobile_screen_share to stringResource(R.string.home_live_phone_screen))
        live.audio?.let { add(R.drawable.sym_mic to stringResource(if (it == "mic") R.string.home_live_mic else R.string.home_live_sound)) }
        if (live.hearing) add(R.drawable.sym_volume_up to stringResource(R.string.home_live_hearing))
        if (live.glide) add(R.drawable.sym_mouse to stringResource(R.string.home_live_glide))
    }
    val (icon, first) = parts.first()
    val rest = parts.drop(1).joinToString(", ") { it.second }
    LiveBanner(
        first.replaceFirstChar { it.uppercase() }, stringResource(R.string.active_session_end), onEnd,
        icon = icon, detail = rest.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.home_live_also, it) },
    )
}



// ---- recent -------------------------------------------------------------------------------------------

/** The last few files sent or received: three thumbnails across (a list at the largest font), each one tap from open. */
@Composable
private fun RecentFiles(state: HomeState, actions: HomeActions) {
    val t = tokens
    val big = LocalDensity.current.fontScale > 1.4f
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(stringResource(R.string.home_recent_files)) {
            if (HomeDerive.moreFiles(state)) DeskTextButton(stringResource(R.string.home_see_all), actions.onOpenFiles)
        }
        // One clock for the whole list, once a minute: the rest of Home does not recompose for "2 min ago".
        val now by minuteClock()
        when {
            !HomeDerive.showRecentFiles(state) -> {
                val shape = DeskLinkShape.group
                Text(
                    stringResource(R.string.home_recent_files_empty, state.name),
                    fontSize = 13.sp, lineHeight = 19.sp, color = t.textDim,
                    modifier = Modifier.fillMaxWidth().clip(shape).background(t.surface).border(1.dp, t.hairline, shape).padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
            big -> {
                val shape = DeskLinkShape.group
                Column(Modifier.fillMaxWidth().clip(shape).background(t.surface).border(1.dp, t.hairline, shape).animateContentSize()) {
                    state.recent.forEachIndexed { index, record ->
                        if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(t.hairline))
                        RecentFileRow(record, now) { actions.onOpenRecent(record) }
                    }
                }
            }
            else -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.recent.forEach { record -> RecentThumb(record, now, Modifier.weight(1f).fillMaxHeight()) { actions.onOpenRecent(record) } }
                repeat(HomeDerive.RECENT_FILES - state.recent.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RecentThumb(record: TransferRecord, now: Long, modifier: Modifier, onOpen: () -> Unit) {
    val t = tokens
    val sizePx = with(LocalDensity.current) { 104.dp.roundToPx() }
    val preview by rememberFilePreview(record, sizePx)
    val kind = previewKind(record.mime, record.name)
    val whenText = android.text.format.DateUtils.getRelativeTimeSpanString(record.time, now, android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    val direction = stringResource(if (record.sent) R.string.home_transfer_sent else R.string.home_transfer_received)
    val meta = listOf(whenText, org.desklink.mobile.helpers.Sizes.iec(record.size)).joinToString(" · ")
    val openLabel = stringResource(R.string.home_open_file)
    val shape = DeskLinkShape.card
    Column(
        modifier
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.hairline, shape)
            .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "$direction, ${record.name}, $meta" },
    ) {
        Box(Modifier.fillMaxWidth().height(72.dp).background(t.accentContainer), contentAlignment = Alignment.Center) {
            val bitmap = preview.bitmap
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(painterResource(iconFor(kind)), contentDescription = null, tint = t.onAccentContainer, modifier = Modifier.size(26.dp))
            }
            Box(Modifier.align(Alignment.TopStart).padding(8.dp).size(20.dp).background(t.surface, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(if (record.sent) R.drawable.sym_upload else R.drawable.sym_download), contentDescription = null, tint = t.textDim, modifier = Modifier.size(12.dp))
            }
        }
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp)) {
            Text(record.name, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(meta, fontSize = 12.sp, lineHeight = 16.sp, color = t.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

/** "4 min. ago" moves on once a minute, and only while Home is on screen (StartedEffect): nothing else on Home ticks. */
@Composable
private fun minuteClock(): androidx.compose.runtime.State<Long> {
    val now = remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    org.desklink.mobile.ui.compose.components.StartedEffect(Unit) {
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay(60_000)
        }
    }
    return now
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

@Composable
private fun ComputersSheet(state: HomeState, onDismiss: () -> Unit, actions: HomeActions) {
    DeskBottomSheet(onDismiss) { ComputersContent(state, onDismiss, actions) }
}

/** The sheet's rows; a screenshot test draws them without the window a real sheet lives in. */
@Composable
internal fun ComputersContent(state: HomeState, onDismiss: () -> Unit, actions: HomeActions) {
    val t = tokens
    run {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.home_your_computers),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = t.textDim,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp).semantics { heading() },
            )
            state.computers.forEach { computer ->
                val selected = computer.id == state.deviceId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(DeskLinkShape.card)
                        .background(if (selected) t.accentContainer else t.surface)
                        .clickable(role = Role.Button) { onDismiss(); actions.onSelectComputer(computer.id) }
                        .semantics(mergeDescendants = true) { this.selected = selected }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val fg = if (selected) t.onAccentContainer else t.text
                    Box(
                        Modifier.size(40.dp).background(if (selected) t.surface else t.fill, DeskLinkShape.small),
                        contentAlignment = Alignment.Center,
                    ) { LaptopGlyph(if (selected) t.text else t.textDim) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(computer.name, fontFamily = MonoFamily, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(connectionShort(computer.connection), fontSize = 12.sp, lineHeight = 16.sp, color = if (selected) fg else t.textDim)
                    }
                    if (selected) Icon(painterResource(R.drawable.sym_check_circle), contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(DeskLinkShape.card)
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
