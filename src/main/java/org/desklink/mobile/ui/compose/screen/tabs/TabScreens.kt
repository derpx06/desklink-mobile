/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.screen.tabs

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.desklink.mobile.ui.compose.components.deskSwitchColors
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.plugins.folders.BucketUi
import org.desklink.mobile.plugins.notes.NotesActivity
import org.desklink.mobile.plugins.notes.NotesUi
import org.desklink.mobile.ui.compose.components.DeskSearchField
import org.desklink.mobile.ui.compose.components.EmptyState
import org.desklink.mobile.ui.compose.components.SegmentedControl
import org.desklink.mobile.ui.compose.components.GroupRow
import org.desklink.mobile.ui.compose.components.LargeTitle
import org.desklink.mobile.ui.compose.components.SectionHeader
import org.desklink.mobile.ui.compose.components.StatusDot
import org.desklink.mobile.ui.compose.components.RowGroup
import org.desklink.mobile.ui.compose.components.SessionPreview
import org.desklink.mobile.ui.compose.components.SessionPreviews
import org.desklink.mobile.ui.compose.components.TerminalTile
import org.desklink.mobile.ui.compose.screen.settings.ClipboardAutoSyncRows
import org.desklink.mobile.ui.compose.screen.settings.clipboardSummary
import org.desklink.mobile.ui.compose.DeskIconButton
import org.desklink.mobile.ui.compose.PrimaryButton
import org.desklink.mobile.ui.compose.SecondaryButton
import org.desklink.mobile.ui.compose.screen.transfers.FileFilter
import org.desklink.mobile.ui.compose.screen.transfers.LiveTransfer
import org.desklink.mobile.ui.compose.screen.transfers.RECENT_LIMIT
import org.desklink.mobile.ui.compose.screen.transfers.TransferActions
import org.desklink.mobile.ui.compose.screen.transfers.recentTransfers
import org.desklink.mobile.ui.compose.tokens
import org.desklink.mobile.webrtc.terminal.TerminalLinkState
import org.desklink.mobile.plugins.share.TransferRecord

// ---- Files ----------------------------------------------------------------------------------------------------

/** The Bucket above, "Recent transfers" below it: one scrolling grid. */
@Composable
fun FilesTab(
    bucket: BucketUi,
    deviceId: String,
    deskName: String,
    live: LiveTransfer?,
    records: List<TransferRecord>,
    revision: Int,
    actions: TransferActions,
    /** Where received files go, in words ("the Downloads folder", a folder's name). */
    savedIn: String = "",
    /** Verified files that arrived but could not be saved, kept for a retry. */
    unsaved: List<org.desklink.mobile.plugins.share.UnpublishedFiles.Item> = emptyList(),
    /** Sends that cannot go on and must be shared again. */
    sendAgain: List<org.desklink.mobile.plugins.share.SendAgain.Item> = emptyList(),
    onSendAgain: (String) -> Unit = {},
    onDismissSendAgain: (String) -> Unit = {},
    /** Ids whose "Try again" is running. */
    retrying: List<String> = emptyList(),
    onRetryUnsaved: (String) -> Unit = {},
    onDiscardUnsaved: (String) -> Unit = {},
) {
    val t = tokens
    var showAll by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
        LargeTitle(
            stringResource(R.string.files_title),
            Modifier.padding(top = 12.dp),
            subtitle = if (savedIn.isNotEmpty()) stringResource(R.string.files_saved_in, savedIn) else stringResource(R.string.files_subtitle),
        ) {
            // Also in the Bucket card: this one stays so that sending is one tap away with the Bucket off, too.
            DeskIconButton(R.drawable.sym_upload_file, stringResource(R.string.home_send_files), actions.onSendFiles)
        }
        if (sendAgain.isNotEmpty() || unsaved.isNotEmpty()) {
            // Notices scroll inside a share of the screen: several of them, or a large font, must not push the files off it.
            val share = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.45f).dp
            Column(Modifier.heightIn(max = share).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                sendAgain.forEach { item -> SendAgainCard(item, onSendAgain, onDismissSendAgain) }
                unsaved.forEach { item -> UnsavedCard(item, item.id in retrying, onRetryUnsaved, onDiscardUnsaved) }
            }
        }
        Box(Modifier.weight(1f)) {
            bucket.Screen(deviceId, onBack = {}, embedded = true, onSendFiles = actions.onSendFiles, below = {
                recentTransfers(
                    deskName, live, records, revision, actions,
                    filter = FileFilter.entries[filter], onFilter = { filter = it.ordinal; showAll = false },
                    limit = if (showAll) Int.MAX_VALUE else RECENT_LIMIT, onShowAll = { showAll = true },
                )
            })
        }
    }
}

/** A send that cannot go on: plain words, and the two things that can be done. */
@Composable
private fun SendAgainCard(
    item: org.desklink.mobile.plugins.share.SendAgain.Item,
    onSendAgain: (String) -> Unit,
    onDismiss: (String) -> Unit,
) = NoticeCard(
    stringResource(R.string.send_again_title, item.name),
    stringResource(if (item.unreadable) R.string.send_again_body_unreadable else R.string.send_again_body_changed),
    stringResource(R.string.send_again_button), { onSendAgain(item.id) },
    stringResource(R.string.send_again_dismiss), { onDismiss(item.id) },
)

/** A file that arrived whole but could not be saved. */
@Composable
private fun UnsavedCard(
    item: org.desklink.mobile.plugins.share.UnpublishedFiles.Item,
    busy: Boolean,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
) = NoticeCard(
    stringResource(R.string.files_unsaved_title, item.name),
    if (item.storageFull) stringResource(R.string.files_unsaved_body_full) else stringResource(R.string.files_unsaved_body),
    // While it runs a second tap would start a second copy: the buttons do nothing until it has an answer.
    stringResource(if (busy) R.string.files_saving_label else R.string.files_unsaved_retry), { if (!busy) onRetry(item.id) },
    stringResource(R.string.files_unsaved_discard), { if (!busy) onDiscard(item.id) },
)

/** The coral card of a file that needs a decision: what happened, and two buttons (48 dp). */
@Composable
private fun NoticeCard(title: String, body: String, primary: String, onPrimary: () -> Unit, secondary: String, onSecondary: () -> Unit) {
    val t = tokens
    Column(
        Modifier
            .fillMaxWidth()
            .background(t.coralContainer, RoundedCornerShape(22.dp))
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = t.coralText)
        Text(body, fontSize = 13.sp, lineHeight = 18.sp, color = t.text)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(primary, onPrimary, height = 48.dp)
            SecondaryButton(secondary, onSecondary, height = 48.dp)
        }
    }
}

// ---- Notes ----------------------------------------------------------------------------------------------------

/**
 * Notes | Snippets | Clipboard. The clipboard sync switch stays in the header (it is the same setting as the first row of the
 * Clipboard page); the search field above the segments searches notes and snippets.
 */
@Composable
fun NotesTab(
    notes: NotesUi,
    host: android.app.Activity,
    deskName: String,
    clipboardOn: Boolean,
    onClipboard: (Boolean) -> Unit,
    onSendClipboard: () -> Unit,
    onMessage: (String) -> Unit,
) {
    org.desklink.mobile.ui.compose.SecureWindow()
    val t = tokens
    val editing = notes.editing.value
    if (editing != null) {
        // The editor has its own header; the tab bar waits.
        notes.Editor(editing) { notes.editing.value = null }
        return
    }
    var segment by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
        LargeTitle(stringResource(R.string.notes_title), Modifier.padding(top = 12.dp), subtitle = stringResource(R.string.notes_subtitle)) {
            ClipboardSwitch(clipboardOn, onClipboard)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (segment < 2) DeskSearchField(query, { query = it }, stringResource(R.string.notes_search_all), stringResource(R.string.palette_clear))
            SegmentedControl(
                listOf(stringResource(R.string.notes_tab_notes), stringResource(R.string.notes_tab_snippets), stringResource(R.string.notes_tab_clipboard)),
                segment, { segment = it },
            )
        }
        Box(Modifier.weight(1f)) {
            when (segment) {
                0 -> notes.NotesList(query)
                1 -> notes.SnippetsList(query)
                else -> ClipboardPanel(host, deskName, clipboardOn, onClipboard, onSendClipboard, onMessage)
            }
        }
    }
}

@Composable
private fun ClipboardSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val label = stringResource(R.string.notes_clipboard_sync)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label }
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.sym_content_paste), contentDescription = null, tint = tokens.textDim, modifier = Modifier.size(20.dp))
        androidx.compose.material3.Switch(checked = on, onCheckedChange = null, colors = deskSwitchColors())
    }
}

/** Sync, automatic sync, send now, and the truth about history: Android lets an app read only what is on the clipboard now. */
@Composable
private fun ClipboardPanel(
    host: android.app.Activity,
    deskName: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
    onSendClipboard: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { androidx.preference.PreferenceManager.getDefaultSharedPreferences(context) }
    var revision by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") revision
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        RowGroup {
            GroupRow(
                stringResource(R.string.settings_phone_clipboard),
                icon = R.drawable.sym_content_paste,
                subtitle = if (on) clipboardSummary(deskName) else stringResource(R.string.home_clipboard_off),
                checked = on,
                onClick = { onChange(!on) },
            )
            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ClipboardAutoSyncRows(deskName, prefs, onMessage) { revision++ }
            if (on) GroupRow(stringResource(R.string.home_send_clipboard, deskName), icon = R.drawable.sym_upload, onClick = onSendClipboard)
        }
        EmptyState(R.drawable.sym_content_paste, stringResource(R.string.notes_clipboard_history_title), stringResource(R.string.notes_clipboard_history_body)) {
            SecondaryButton(stringResource(R.string.notes_clipboard_pin), { NotesActivity.pinClipboard(host) }, tonal = true, height = 48.dp, icon = R.drawable.sym_keep)
        }
    }
}

// ---- Terminals --------------------------------------------------------------------------------------------------

/**
 * The sessions on the computer: launcher tiles (a shell, Claude Code, Codex) that start one, a dark card each for the open
 * sessions (opened in the full-screen terminal), and an honest state when they are locked, not allowed or the link is down.
 * Sessions are not attached here: only a tap opens one.
 */
@Composable
fun TerminalsTab(
    deviceId: String,
    deskName: String,
    onOpenSession: (String) -> Unit,
    onOpenTerminals: () -> Unit,
    onNew: () -> Unit,
    /** A launcher tile: "claude", "codex", or null for a shell. */
    onNewLauncher: (String?) -> Unit = { onNew() },
) {
    org.desklink.mobile.ui.compose.SecureWindow()
    val t = tokens
    val context = LocalContext.current
    val client = remember(deviceId) { DeskLinkApplication.getInstance().terminalClient(deviceId) }
    val state by (client?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(org.desklink.mobile.webrtc.terminal.TerminalClientState()) }).collectAsState()
    val connected = state.link == TerminalLinkState.CONNECTED
    val locked = state.passwordNeeded != null
    // Sessions can also start at the desk, which is not announced: look again while this tab is on screen.
    org.desklink.mobile.ui.compose.components.StartedEffect(client, connected, locked) {
        while (client != null && connected && !locked) {
            client.refresh()
            kotlinx.coroutines.delay(REFRESH_MS)
        }
    }
    var tick by remember { mutableIntStateOf(0) }
    org.desklink.mobile.ui.compose.components.StartedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(PREVIEW_MS)
            tick++
        }
    }
    val live = state.sessions.filter { !it.gone }
    val previews = remember(state, tick) { SessionPreviews.of(context, deviceId, lineCount = 2) }.associateBy { it.id }
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
        LargeTitle(
            stringResource(R.string.terminal_section),
            Modifier.padding(top = 12.dp),
            subtitle = when {
                client == null || !connected -> stringResource(R.string.terminal_status_reconnecting)
                live.isEmpty() -> stringResource(R.string.terminals_subtitle_none, deskName)
                else -> androidx.compose.ui.res.pluralStringResource(R.plurals.terminal_count_on, live.size, live.size, deskName)
            },
        ) {
            if (client != null && !state.notAllowed && !locked) LinkChip(connected)
        }
        when {
            client == null || state.notAllowed || locked -> EmptyState(
                R.drawable.sym_terminal,
                stringResource(if (state.notAllowed) R.string.terminal_not_allowed_title else R.string.terminal_section),
                stringResource(if (state.notAllowed) R.string.terminal_not_allowed_message else R.string.terminals_unavailable),
            ) { SecondaryButton(stringResource(R.string.terminals_open), onOpenTerminals, tonal = true, height = 48.dp) }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "launchers") { Launchers(state.launchers, connected, onNewLauncher) }
                if (live.isEmpty()) {
                    item(key = "none") {
                        Text(stringResource(R.string.terminal_empty_hint), fontSize = 14.sp, lineHeight = 20.sp, color = t.textDim, modifier = Modifier.padding(horizontal = 6.dp))
                    }
                } else {
                    item(key = "open") { SectionHeader(stringResource(R.string.terminals_open_sessions)) }
                    items(live, key = { it.id }) { session ->
                        val preview = previews[session.id] ?: SessionPreview(
                            id = session.id,
                            name = session.title.ifBlank { session.id.take(8) },
                            badge = SessionPreviews.badgeOf(session.title),
                            live = false,
                            lines = emptyList(),
                            size = stringResource(R.string.terminal_session_size, session.cols, session.rows),
                        )
                        TerminalTile(preview, Modifier.fillMaxWidth()) { onOpenSession(session.id) }
                    }
                    item(key = "footnote") {
                        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(painterResource(R.drawable.sym_terminal), contentDescription = null, tint = t.textDim, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.terminals_keep_running, deskName), fontSize = 13.sp, lineHeight = 18.sp, color = t.textDim)
                        }
                    }
                }
            }
        }
    }
}

/** "Connected" with its dot, or "Reconnecting…": the link the sessions ride on. */
@Composable
private fun LinkChip(connected: Boolean) {
    val t = tokens
    val color = if (connected) t.mintText else t.amber
    Row(
        Modifier.padding(end = 8.dp, bottom = 6.dp).heightIn(min = 28.dp).background(t.surface, RoundedCornerShape(14.dp)).padding(start = 8.dp, end = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        StatusDot(color)
        Text(
            stringResource(if (connected) R.string.connection_connected else R.string.terminal_status_reconnecting),
            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (connected) t.mintText else t.text,
        )
    }
}

/** Shell, then the agent CLIs the computer has: three dark tiles to a row (one each at a large font). Each starts that terminal. */
@Composable
private fun Launchers(offered: List<String>, enabled: Boolean, onStart: (String?) -> Unit) {
    val context = LocalContext.current
    val agents = offered.mapNotNull { id -> SessionPreviews.agentName(context, id)?.let { id to it } }
    val options = listOf<Pair<String?, String>>(null to stringResource(R.string.terminal_launch_shell)) + agents
    val wide = androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.3f
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = if (wide) 3 else 1) {
        options.forEach { (id, name) ->
            LauncherTile(SessionPreviews.badgeOf(id.orEmpty()), name, enabled, Modifier.weight(1f)) { onStart(id) }
        }
    }
}

@Composable
private fun LauncherTile(badge: String, name: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = tokens
    val label = stringResource(R.string.terminals_new_launcher, name)
    Column(
        modifier
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(t.terminalBg)
            .border(1.dp, Color(0xFF262931), RoundedCornerShape(20.dp))
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            org.desklink.mobile.ui.compose.components.LabelChip(badge)
            Icon(painterResource(R.drawable.sym_add), contentDescription = null, tint = Color(0xFFA1A5AE), modifier = Modifier.size(18.dp))
        }
        Text(name, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE6E6E6))
    }
}

private const val REFRESH_MS = 5_000L
private const val PREVIEW_MS = 2_000L
