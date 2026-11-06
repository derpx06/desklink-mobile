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
import org.desklink.mobile.ui.compose.screen.transfers.FilesView
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
            // Filtered and grouped when the files, the chip or "Show all" change, not on every tick of the screen.
            val limit = if (showAll) Int.MAX_VALUE else RECENT_LIMIT
            val view = androidx.compose.runtime.remember(records, filter, limit) { FilesView.of(records, FileFilter.entries[filter], limit) }
            bucket.Screen(deviceId, onBack = {}, embedded = true, onSendFiles = actions.onSendFiles, below = {
                recentTransfers(
                    deskName, live, records, revision, actions,
                    filter = FileFilter.entries[filter], onFilter = { filter = it.ordinal; showAll = false },
                    view = view, limit = limit, onShowAll = { showAll = true },
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
    // Declared before the editor takes over, so the search and the segment are still there when it closes.
    var segment by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val editing = notes.editing.value
    if (editing != null) {
        // The editor has its own header. A different note (or a new one) starts a fresh editor; the tab bar floats over it,
        // so the keyboard leaves room only for what the bar does not already cover.
        androidx.compose.runtime.key(editing) {
            notes.Editor(editing, barInset = if (org.desklink.mobile.ui.compose.shell.isWide()) 0.dp else org.desklink.mobile.ui.compose.shell.TabBarHeight) { notes.editing.value = null }
        }
        return
    }
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
