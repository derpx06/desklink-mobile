/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.screen.tabs

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import org.desklink.mobile.ui.compose.components.EmptyState
import org.desklink.mobile.ui.compose.components.GroupRow
import org.desklink.mobile.ui.compose.components.LargeTitle
import org.desklink.mobile.ui.compose.components.PillButton
import org.desklink.mobile.ui.compose.components.PillStyle
import org.desklink.mobile.ui.compose.components.RowGroup
import org.desklink.mobile.ui.compose.components.SessionPreview
import org.desklink.mobile.ui.compose.components.SessionPreviews
import org.desklink.mobile.ui.compose.components.TerminalTile
import org.desklink.mobile.ui.compose.screen.settings.ClipboardAutoSyncRows
import org.desklink.mobile.ui.compose.screen.settings.clipboardSummary
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
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
        LargeTitle(
            stringResource(R.string.files_title),
            Modifier.padding(top = 12.dp),
            subtitle = if (savedIn.isNotEmpty()) stringResource(R.string.files_saved_in, savedIn) else stringResource(R.string.files_subtitle),
        ) {
            IconButton(onClick = actions.onSendFiles, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.sym_upload_file), contentDescription = stringResource(R.string.home_send_files), tint = t.text)
            }
        }
        sendAgain.forEach { item -> SendAgainCard(item, onSendAgain, onDismissSendAgain) }
        unsaved.forEach { item -> UnsavedCard(item, item.id in retrying, onRetryUnsaved, onDiscardUnsaved) }
        Box(Modifier.weight(1f)) {
            bucket.Screen(deviceId, onBack = {}, embedded = true) {
                recentTransfers(deskName, live, records, revision, actions, limit = if (showAll) Int.MAX_VALUE else RECENT_LIMIT, onShowAll = { showAll = true })
            }
        }
    }
}

/** A file that arrived whole but could not be saved: plain words, and the two things that can be done. */
@Composable
private fun SendAgainCard(
    item: org.desklink.mobile.plugins.share.SendAgain.Item,
    onSendAgain: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    val t = tokens
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(t.coralContainer)
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.send_again_title, item.name), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = t.coralText)
        Text(
            stringResource(if (item.unreadable) R.string.send_again_body_unreadable else R.string.send_again_body_changed),
            fontSize = 13.sp, color = t.text,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.send_again_button), { onSendAgain(item.id) }, height = 48.dp)
            PillButton(stringResource(R.string.send_again_dismiss), { onDismiss(item.id) }, style = PillStyle.Outlined, height = 48.dp)
        }
    }
}

@Composable
private fun UnsavedCard(
    item: org.desklink.mobile.plugins.share.UnpublishedFiles.Item,
    busy: Boolean,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    val t = tokens
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(t.coralContainer)
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.files_unsaved_title, item.name), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = t.coralText)
        Text(
            if (item.storageFull) stringResource(R.string.files_unsaved_body_full) else stringResource(R.string.files_unsaved_body),
            fontSize = 13.sp, color = t.text,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // While it runs a second tap would start a second copy: the buttons do nothing until it has an answer.
            PillButton(
                stringResource(if (busy) R.string.files_saving_label else R.string.files_unsaved_retry),
                { if (!busy) onRetry(item.id) }, height = 48.dp,
            )
            PillButton(stringResource(R.string.files_unsaved_discard), { if (!busy) onDiscard(item.id) }, style = PillStyle.Outlined, height = 48.dp)
        }
    }
}

// ---- Notes ----------------------------------------------------------------------------------------------------

/** Notes | Snippets | Clipboard, with the clipboard sync switch in the header. */
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
    val t = tokens
    val editing = notes.editing.value
    if (editing != null) {
        // The editor has its own header; the tab bar waits.
        notes.Editor(editing) { notes.editing.value = null }
        return
    }
    var segment by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
        LargeTitle(stringResource(R.string.notes_title), Modifier.padding(top = 12.dp), subtitle = stringResource(R.string.notes_subtitle)) {
            ClipboardSwitch(clipboardOn, onClipboard)
        }
        Segments(segment) { segment = it }
        Box(Modifier.weight(1f).padding(top = 12.dp)) {
            when (segment) {
                0 -> notes.NotesList()
                1 -> notes.SnippetsList()
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

@Composable
private fun Segments(selected: Int, onSelect: (Int) -> Unit) {
    val t = tokens
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(50)).background(t.fill).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(R.string.notes_tab_notes, R.string.notes_tab_snippets, R.string.notes_tab_clipboard).forEachIndexed { index, label ->
            val on = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) t.surface else t.fill)
                    .selectable(selected = on, role = Role.Tab) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(label), fontSize = 15.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = if (on) t.text else t.textDim)
            }
        }
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
            PillButton(stringResource(R.string.notes_clipboard_pin), { NotesActivity.pinClipboard(host) }, style = PillStyle.Tonal, height = 48.dp, icon = R.drawable.sym_keep)
        }
    }
}

// ---- Terminals --------------------------------------------------------------------------------------------------

/**
 * The sessions on the computer: a tile each (opened in the full-screen terminal), "New terminal", and an
 * honest state when they are locked, not allowed or the link is down. Sessions are not attached here: only a
 * tap opens one.
 */
@Composable
fun TerminalsTab(
    deviceId: String,
    deskName: String,
    onOpenSession: (String) -> Unit,
    onOpenTerminals: () -> Unit,
    onNew: () -> Unit,
) {
    val t = tokens
    val context = LocalContext.current
    val client = remember(deviceId) { DeskLinkApplication.getInstance().terminalClient(deviceId) }
    val state by (client?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(org.desklink.mobile.webrtc.terminal.TerminalClientState()) }).collectAsState()
    val connected = state.link == TerminalLinkState.CONNECTED
    val locked = state.passwordNeeded != null
    // Sessions can also start at the desk, which is not announced: look again while this tab is on screen.
    LaunchedEffect(client, connected, locked) {
        while (client != null && connected && !locked) {
            client.refresh()
            kotlinx.coroutines.delay(REFRESH_MS)
        }
    }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(PREVIEW_MS)
            tick++
        }
    }
    val live = state.sessions.filter { !it.gone }
    val previews = remember(state, tick) { SessionPreviews.of(context, deviceId, lineCount = 2) }.associateBy { it.id }
    Box(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            LargeTitle(
                stringResource(R.string.terminal_section),
                Modifier.padding(top = 12.dp),
                subtitle = when {
                    client == null || !connected -> stringResource(R.string.terminal_status_reconnecting)
                    live.isEmpty() -> stringResource(R.string.terminals_subtitle_none, deskName)
                    else -> androidx.compose.ui.res.pluralStringResource(R.plurals.terminal_count_on, live.size, live.size, deskName)
                },
            )
            when {
                client == null || state.notAllowed || locked -> EmptyState(
                    R.drawable.sym_terminal,
                    stringResource(if (state.notAllowed) R.string.terminal_not_allowed_title else R.string.terminal_section),
                    stringResource(if (state.notAllowed) R.string.terminal_not_allowed_message else R.string.terminals_unavailable),
                ) { PillButton(stringResource(R.string.terminals_open), onOpenTerminals, style = PillStyle.Tonal, height = 48.dp) }
                live.isEmpty() -> EmptyState(R.drawable.sym_terminal, stringResource(R.string.terminal_section), stringResource(R.string.terminal_empty_hint)) {
                    PillButton(stringResource(R.string.terminal_new_terminal), onNew, height = 48.dp, icon = R.drawable.sym_add, enabled = connected)
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
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
                }
            }
        }
        if (client != null && !state.notAllowed && !locked && live.isNotEmpty()) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(t.accentContainer)
                    .selectable(selected = false, role = Role.Button) { if (connected) onNew() }
                    .padding(start = 16.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(painterResource(R.drawable.sym_add), contentDescription = null, tint = t.onAccentContainer)
                Text(stringResource(R.string.terminal_new_terminal), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = t.onAccentContainer)
            }
        }
    }
}

private const val REFRESH_MS = 5_000L
private const val PREVIEW_MS = 2_000L
