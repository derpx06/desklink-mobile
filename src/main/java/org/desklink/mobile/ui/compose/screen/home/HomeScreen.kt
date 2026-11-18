/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.home

import org.desklink.mobile.ui.compose.components.DialogAction
import org.desklink.mobile.ui.compose.components.DeskDialog
import org.desklink.mobile.ui.compose.components.DeskTextField
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
import androidx.compose.ui.text.TextStyle
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
import org.desklink.mobile.ui.compose.pressScale
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
import org.desklink.mobile.ui.compose.components.rememberReducedMotion
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.screen.transfers.extensionBadge
import org.desklink.mobile.ui.compose.screen.transfers.iconFor
import org.desklink.mobile.ui.compose.screen.transfers.kindColors
import org.desklink.mobile.ui.compose.screen.transfers.rememberFilePreview
import org.desklink.mobile.ui.compose.tokens
import org.desklink.mobile.webrtc.WebRtcConnectionPath

/** From this width the screen has two columns: what you can do on the left, what is going on on the right. */
private val TWO_COLUMNS = 560.dp

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions, finishSetup: (@Composable () -> Unit)? = null) {
    val t = tokens
    var sheet by rememberSaveable { mutableStateOf(false) }
    var linkDialog by rememberSaveable { mutableStateOf(false) }
    // Not saved: coming back to Home (rotation, the pager recreating the page) must not pop the keyboard.
    var palette by remember { mutableStateOf(false) }
    val notReady = notReadyMessage(state)
    val deskHaptics = org.desklink.mobile.ui.compose.components.rememberDeskHaptics()
    val gate = { action: () -> Unit ->
        if (HomeDerive.usable(state)) action() else {
            deskHaptics.perform(org.desklink.mobile.ui.compose.components.Haptic.FAILURE)
            actions.onUnavailable(notReady)
        }
    }
    // The Home tab's long-press asks for the computers sheet.
    androidx.compose.runtime.LaunchedEffect(actions.openComputersRequest) {
        if (actions.openComputersRequest > 0) {
            sheet = true
            actions.onComputersShown()
        }
    }

    var playerOpen by rememberSaveable { mutableStateOf(false) }
    val resources = androidx.compose.ui.platform.LocalResources.current
    // A row of "Everything DeskLink does": open it, run its step (then open its page), look again, or say why not.
    val onFeatureRow: (Feature, FeatureStatus) -> Unit = { f, status ->
        fun open() {
            if (f.open == Everything.OPEN_PLAYER) {
                if (state.media != null) playerOpen = true else actions.onMessage(resources.getString(R.string.everything_media_nothing, state.name))
            } else openAction(f.open, actions) { linkDialog = true }
        }
        when (Everything.action(status)) {
            RowAction.OPEN -> open()
            RowAction.STEP -> {
                val step = (status as FeatureStatus.Step).step
                actions.onFeatureStep(f.id, step)
                if (Everything.opensAfter(f, step)) open()
            }
            RowAction.RETRY -> actions.onRetry()
            RowAction.EXPLAIN -> (status as FeatureStatus.Off).let { off ->
                off.why?.let { actions.onMessage(resources.getString(it, state.name)) } ?: actions.onBlocked(f.action ?: f.id, resources.getString(f.name, state.name), off.blocker)
            }
        }
    }
    // Where the card's artwork is, for the player to grow from.
    val cardArt = remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val quiet = linkGlyphState(state.connection, state.path) == LinkGlyphState.OFFLINE
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
            val two = maxWidth >= TWO_COLUMNS
            // "Finish setting up" comes after what you open the app for (the computer, what is going on, the quick actions),
            // still near the top. It lists notifications with the rest, so the one-line nudge is for when it is not there.
            val setup: @Composable () -> Unit = {
                if (finishSetup != null) finishSetup() else if (state.notificationsOff) NotificationsNudge(actions.onTurnOnNotifications)
            }
            val doing: @Composable () -> Unit = {
                if (quiet) {
                    OfflineHero(state, actions) { sheet = true }
                    ThingsToCheck(state)
                    if (!two) setup()
                    StillWorksOffline(state, actions)
                } else {
                    Hero(state, gate, actions) { sheet = true }
                    QuickActions(state, gate, actions, notReady, onAll = { palette = true }, onSendLink = { linkDialog = true })
                }
            }
            val going: @Composable () -> Unit = {
                // Two columns: first on the right. One column places it itself (after the offline checks, or the quick actions).
                if (two) setup()
                HappeningNow(state, actions, cardArt) { playerOpen = true }
                RecentFiles(state, actions)
                org.desklink.mobile.plugins.notes.NotesHomeCard(onOpenList = actions.onNotes, onOpenNote = actions.onNote, onNew = actions.onNewNote, limit = HomeDerive.RECENT_NOTES)
                MirroredLine(state)
                // Local-first: the Bucket shows whether or not the computer is there.
                org.desklink.mobile.plugins.folders.BucketHomeCard(state.deviceId, state.name, onOpen = actions.onBucket)
            }
            // Pull down looks for the computer again: the same action as "Try again" (a discovery refresh, nothing destructive).
            var refreshing by remember { mutableStateOf(false) }
            val haptics = org.desklink.mobile.ui.compose.components.rememberDeskHaptics()
            androidx.compose.runtime.LaunchedEffect(refreshing) {
                if (refreshing) {
                    delay(1200)
                    refreshing = false
                }
            }
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    haptics.perform(org.desklink.mobile.ui.compose.components.Haptic.TICK)
                    actions.onRetry()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
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
                        setup()
                        RecentFiles(state, actions)
                        org.desklink.mobile.plugins.notes.NotesHomeCard(onOpenList = actions.onNotes, onOpenNote = actions.onNote, onNew = actions.onNewNote, limit = HomeDerive.RECENT_NOTES)
                        MirroredLine(state)
                        org.desklink.mobile.plugins.folders.BucketHomeCard(state.deviceId, state.name, onOpen = actions.onBucket)
                    }
                }
                // Last: where everything is, each with its one step. Full width in both layouts.
                EverythingSection(state, onFeatureRow)
            }
            }
        }
        NowPlayingOverlay(state.media, actions, cardArt, playerOpen && state.media != null) { playerOpen = false }
        if (palette) PaletteScreen(state, actions, onClose = { palette = false }, onSendLink = { palette = false; linkDialog = true }) { f ->
            onFeatureRow(f, Everything.status(f, state))
        }
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
        LinkGlyphState.RELAY -> if (path == WebRtcConnectionPath.TURN) R.string.home_connected_turn else R.string.home_connected_internet
        LinkGlyphState.DIRECT -> when {
            connection == DeviceConnection.LOCAL || path == WebRtcConnectionPath.LAN -> R.string.home_connected_wifi
            path == WebRtcConnectionPath.DIRECT || connection == DeviceConnection.AWAY -> R.string.home_connected_internet
            else -> R.string.connection_connected
        }
    },
)

@Composable
fun connectionShort(connection: DeviceConnection, path: WebRtcConnectionPath? = null): String = stringResource(connectionShortRes(connection, path))

/** "Relay" only when the link really goes through a relay (TURN); any other way over the internet is "Internet". */
fun connectionShortRes(connection: DeviceConnection, path: WebRtcConnectionPath? = null): Int = when (connection) {
    DeviceConnection.LOCAL -> R.string.home_short_wifi
    DeviceConnection.AWAY -> if (path == WebRtcConnectionPath.TURN) R.string.home_short_relay else R.string.home_short_internet
    DeviceConnection.CONNECTED -> R.string.connection_connected
    DeviceConnection.CONNECTING -> R.string.connection_connecting
    DeviceConnection.OFFLINE -> R.string.connection_offline
    DeviceConnection.UNAVAILABLE -> R.string.connection_unavailable
}

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
private fun NameBlock(state: HomeState, onSwitch: () -> Unit, spoken: List<String?>, modifier: Modifier = Modifier, header: @Composable () -> Unit, footer: @Composable () -> Unit) {
    val t = tokens
    // What the card shows is what TalkBack says: the switch sentence, then the status words, the round trip and the update note.
    val switchDescription = (listOf(stringResource(R.string.home_switch_computer, state.name, connectionShort(state.connection, state.path))) + spoken.filterNotNull()).joinToString(". ")
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
                state, onSwitch, listOf(status, meta, detail), Modifier.weight(1f),
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
        }
        // Three across; one column once the words would not fit. Terminals works with the computer away: it opens the tab, which keeps its own sessions.
        val terminalsHint = if (state.live.terminals > 0) pluralStringResource(R.plurals.home_live_terminals, state.live.terminals, state.live.terminals) else null
        // Dimmed, never hidden: the hint says what is missing and a tap says how to get it.
        @Composable
        fun tile(action: String, icon: Int, title: String, run: () -> Unit, primary: Boolean, m: Modifier, compact: Boolean) {
            val blocked = HomeDerive.blocker(state, action)
            ActionTile(
                icon, title, { gate(run) }, m, primary = primary, enabled = blocked == null, compact = compact,
                hint = off ?: blocked?.let { stringResource(HomeDerive.needs(it), state.name) },
                onDisabledClick = { blocked?.let { actions.onBlocked(action, title, it) } },
            )
        }
        val labels = listOf(stringResource(R.string.home_desk_screen), stringResource(R.string.home_send_files), stringResource(R.string.tab_terminals))
        val tiles: List<@Composable (Modifier, Boolean) -> Unit> = listOf(
            { m, c -> tile(PaletteModel.Action.DESK_SCREEN, R.drawable.sym_desktop_windows, labels[0], actions.onDeskScreen, true, m, c) },
            { m, c -> tile(PaletteModel.Action.SEND_FILES, R.drawable.sym_upload_file, labels[1], actions.onSendFiles, false, m, c) },
            { m, c -> ActionTile(R.drawable.sym_terminal, labels[2], actions.onTerminals, m, hint = terminalsHint, compact = c) },
        )
        // From the width the tiles really get (a two-pane fold gives them less than the screen): three across while each
        // keeps its longest word whole (the label is capped at 1.3, as ActionTile draws it), else one under another,
        // in the compact icon-at-start form (UI-REDESIGN 2.8.7).
        androidx.compose.foundation.layout.BoxWithConstraints {
            // The names, and the one-word hints under them ("Connecting…").
            val word = longestWordDp(labels + listOfNotNull(off, terminalsHint), TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium), maxFontScale = 1.3f)
            if (tilesAcross(maxWidth.value, 3, gap = 8f, fixed = ACTION_TILE_PADDING, word = word) < 3) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { tiles.forEach { it(Modifier.fillMaxWidth(), true) } }
            } else {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.forEach { it(Modifier.weight(1f).fillMaxHeight(), false) }
                }
            }
        }
    }
}

/** An ActionTile's padding, both sides (dp): what of its width is not for words. */
private const val ACTION_TILE_PADDING = 24f

/** An offline tile's padding, glyph circle and the gap after it (dp). */
private const val OFFLINE_TILE_FIXED = 12f * 2 + 40f + 10f

/**
 * How many of [count] tiles fit side by side in [width] dp, [gap] apart, when each spends [fixed] dp on padding and
 * glyph and must hold its longest word ([word] dp) whole; at least 1. Too few, and a name breaks inside a word
 * ("Termina / ls", F47).
 */
internal fun tilesAcross(width: Float, count: Int, gap: Float, fixed: Float, word: Float): Int =
    (count downTo 1).firstOrNull { n -> (width - gap * (n - 1)) / n - fixed >= word } ?: 1

/** The width in dp of the longest word of [labels] in [style], at the system font scale capped at [maxFontScale]. */
@Composable
private fun longestWordDp(labels: List<String>, style: TextStyle, maxFontScale: Float = Float.MAX_VALUE): Float {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    // As Text draws it: the theme's style (its letter spacing) under the given size and weight.
    val drawn = androidx.compose.material3.LocalTextStyle.current.merge(style)
    return remember(labels, drawn, density, maxFontScale) {
        val capped = androidx.compose.ui.unit.Density(density.density, minOf(density.fontScale, maxFontScale))
        labels.flatMap { it.split(' ') }.maxOfOrNull { word ->
            measurer.measure(word, drawn, softWrap = false, maxLines = 1, density = capped).size.width / density.density
        } ?: 0f
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
                state, onSwitch, listOf(word, body), Modifier.weight(1f),
                header = { StatusChip(word, t.textFaint, t.textDim, t.fill) },
                footer = { Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = t.textDim, modifier = Modifier.padding(top = 4.dp)) },
            )
        }
        PrimaryButton(stringResource(R.string.device_try_again), actions.onRetry, Modifier.fillMaxWidth(), icon = R.drawable.sym_refresh, height = 48.dp)
    }
}

private class Check(val title: String, val body: String, val action: String? = null, val onAction: () -> Unit = {})

/** Numbered things that are really worth a look, for this state: no guesses about networks the phone cannot know. */
@Composable
private fun ThingsToCheck(state: HomeState) {
    val checks = buildList {
        if (state.updateNeeded) add(Check(stringResource(R.string.home_check_update, state.name), stringResource(R.string.home_check_update_body, state.name)))
        add(Check(stringResource(R.string.home_check_awake, state.name), stringResource(R.string.home_check_awake_body)))
        add(Check(stringResource(R.string.home_check_wifi), stringResource(R.string.home_check_wifi_body, state.name)))
        // Information only, never a way to turn the relay on: that is BuildConfig.RELAY_ENABLED alone.
        if (org.desklink.mobile.BuildConfig.RELAY_ENABLED && state.relayAvailable) add(Check(stringResource(R.string.home_check_away), stringResource(R.string.home_check_away_relay_on)))
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
    val word = longestWordDp(tiles.map { it.label }, TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(stringResource(R.string.home_still_works))
        // Two across while each tile keeps "Terminals" whole in the width it really gets, else one.
        androidx.compose.foundation.layout.BoxWithConstraints {
            val columns = if (tilesAcross(maxWidth.value, 2, gap = 8f, fixed = OFFLINE_TILE_FIXED, word = word) >= 2) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // A lone last tile takes the whole row instead of leaving a hole beside it.
                        row.forEach { tile -> OfflineTile(tile, Modifier.weight(columns.toFloat() / row.size).fillMaxHeight(), unreachable) }
                    }
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
    // Every action is listed, whatever the computer has: one it cannot take is dimmed, and a tap says why and how to turn it on.
    val usable = HomeDerive.usable(state)
    val sharing = state.live.phoneScreen
    class Q(val id: String, val icon: Int, val label: String, val run: () -> Unit) { val blocked = HomeDerive.blocker(state, id) }
    val items = listOf(
        Q(PaletteModel.Action.SEND_CLIPBOARD, R.drawable.sym_content_paste, stringResource(R.string.home_tile_clipboard)) { gate(actions.onSendClipboard) },
        Q(PaletteModel.Action.SEND_LINK, R.drawable.sym_link, stringResource(R.string.home_send_link)) { gate(onSendLink) },
        Q(PaletteModel.Action.RING, R.drawable.sym_vibration, stringResource(R.string.home_ring_desk)) { gate(actions.onRing) },
        Q(PaletteModel.Action.LOCK, R.drawable.sym_lock, stringResource(R.string.home_lock_desk)) { gate(actions.onLock) },
        Q(PaletteModel.Action.SHARE_SCREEN, R.drawable.sym_mobile_screen_share, stringResource(if (sharing) R.string.home_stop_sharing else R.string.home_share_screen)) { if (sharing) actions.onShareScreen() else gate(actions.onShareScreen) },
        Q(PaletteModel.Action.PING, R.drawable.sym_waving_hand, stringResource(R.string.home_ping_desk)) { gate(actions.onPing) },
        Q(PaletteModel.Action.UNLOCK, R.drawable.sym_lock_open, stringResource(R.string.home_tile_unlock)) { actions.onUnlockDesk() },
    )
    val clipboardLine = when {
        !usable || !o.clipboard -> null
        !state.clipboardOn -> stringResource(R.string.home_tile_clipboard_off)
        else -> state.clipboardPreview?.let { stringResource(R.string.home_tile_clipboard_preview, it) }
    }
    // Two across from 1.2x text: at 1.3x four across put "Ring computer" and "Lock computer" edge to edge.
    val columns = if (LocalDensity.current.fontScale > org.desklink.mobile.ui.compose.TextScale.CROWDED) 2 else 4
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(stringResource(R.string.home_quick_actions)) { DeskTextButton(stringResource(R.string.home_all_actions), onAll) }
        val shape = DeskLinkShape.group
        Column(
            Modifier.fillMaxWidth().clip(shape).background(t.surface).border(1.dp, t.hairline, shape).padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    // A short last row is centred, so the grid does not end in a hole on one side.
                    val gap = (columns - row.size) / 2f
                    if (gap > 0f) Spacer(Modifier.weight(gap))
                    row.forEach { q ->
                        QuickActionTile(q.icon, q.label, q.run, Modifier.weight(1f), enabled = q.blocked == null, onDisabledClick = { q.blocked?.let { actions.onBlocked(q.id, q.label, it) } })
                    }
                    if (gap > 0f) Spacer(Modifier.weight(gap))
                }
            }
            clipboardLine?.let {
                Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = t.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp))
            }
        }
    }
}

/** "Notifications are off. Turn on": amber, one line, shown until Android has them. */
@Composable
private fun NotificationsNudge(onTurnOn: () -> Unit) {
    val t = tokens
    Row(
        Modifier.fillMaxWidth().background(t.surface, DeskLinkShape.banner).border(1.dp, t.amber.copy(alpha = 0.5f), DeskLinkShape.banner).padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(R.drawable.sym_notifications), contentDescription = null, tint = t.amber, modifier = Modifier.size(22.dp))
        // "Turn on" goes under the words when beside them "Notifications" would break.
        org.desklink.mobile.ui.compose.components.TextAndTrailing(
            Modifier.weight(1f),
            text = { Text(stringResource(R.string.home_notifications_off), fontSize = 14.sp, lineHeight = 19.sp, color = t.text, modifier = Modifier.padding(vertical = 8.dp)) },
            trailing = { org.desklink.mobile.ui.compose.DeskTextButton(stringResource(R.string.home_notifications_turn_on), onTurnOn) },
        )
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
            // Nothing yet: the one thing that fills it, while the computer can take it.
            else if (!HomeDerive.showRecentFiles(state) && HomeDerive.blocker(state, PaletteModel.Action.SEND_FILES) == null) {
                DeskTextButton(stringResource(R.string.home_send_files), actions.onSendFiles)
            }
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
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        modifier
            .pressScale(source)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.hairline, shape)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), role = Role.Button, onClickLabel = openLabel, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "$direction, ${record.name}, $meta" },
    ) {
        val (container, ink) = kindColors(kind)
        Box(Modifier.fillMaxWidth().height(72.dp).background(container), contentAlignment = Alignment.Center) {
            val bitmap = preview.bitmap
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                val badge = extensionBadge(record.name)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(iconFor(kind)), contentDescription = null, tint = ink, modifier = Modifier.size(26.dp))
                    if (badge.isNotEmpty()) Text(badge, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = MonoFamily, color = ink)
                }
            }
            Box(Modifier.align(Alignment.TopStart).padding(8.dp).size(20.dp).background(t.surface, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(if (record.sent) R.drawable.sym_upload else R.drawable.sym_download), contentDescription = null, tint = t.textDim, modifier = Modifier.size(12.dp))
            }
        }
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp)) {
            Text(record.name, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            // Two short lines, always: the age, then the size ("471 KiB" never splits across them).
            Text(whenText, fontSize = 12.sp, lineHeight = 16.sp, color = t.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(org.desklink.mobile.helpers.Sizes.iec(record.size), fontSize = 12.sp, lineHeight = 16.sp, color = t.textDim, maxLines = 1)
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
                    // The bar glides to each new value instead of jumping by whole percents.
                    val shown by androidx.compose.animation.core.animateFloatAsState(transfer.progress, androidx.compose.animation.core.tween(if (rememberReducedMotion()) 0 else 300), label = "transfer-progress")
                    LinearProgressIndicator({ shown }, Modifier.fillMaxWidth().height(4.dp), color = t.accent, trackColor = t.fill, drawStopIndicator = {}, gapSize = 0.dp)
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
        val (container, ink) = kindColors(kind)
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(container), contentAlignment = Alignment.Center) {
            val bitmap = preview.bitmap
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(painterResource(iconFor(kind)), contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
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
                        Text(connectionShort(computer.connection, computer.path), fontSize = 12.sp, lineHeight = 16.sp, color = if (selected) fg else t.textDim)
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
    DeskDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.home_send_link_title),
        // The dialog scrolls its body: at a large font size, or sideways with the keyboard up, the field stays reachable.
        text = {
            DeskTextField(
                value = text,
                onValueChange = { text = it.take(2048) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = "https://",
                name = stringResource(R.string.home_send_link_title),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = MonoFamily, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        },
        confirm = DialogAction(stringResource(R.string.home_send), enabled = url != null) { url?.let(onSend) },
        dismiss = DialogAction(stringResource(R.string.cancel)) { onDismiss() },
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
