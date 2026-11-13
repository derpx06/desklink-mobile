/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.screen.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.input.pointer.pointerInput
import org.desklink.mobile.ui.compose.shell.TabBarHeight
import org.desklink.mobile.ui.compose.shell.isWide
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.plugins.notes.NotesStore
import org.desklink.mobile.ui.compose.DeskIconButton
import org.desklink.mobile.ui.compose.components.DeskSearchField
import org.desklink.mobile.ui.compose.components.IconTile
import org.desklink.mobile.ui.compose.components.RowGroup
import org.desklink.mobile.ui.compose.components.SectionHeader
import org.desklink.mobile.ui.compose.screen.home.PaletteModel.Action
import org.desklink.mobile.ui.compose.screen.settings.SettingsPage
import org.desklink.mobile.ui.compose.tokens

/**
 * "Find anything": one search over what the app really has. Actions the connected computer offers, recent files, notes,
 * snippets, settings pages and computers, grouped. Ranking is [PaletteModel]; this file only turns the app's data into
 * items and an item's id back into a call. It covers the Home page (the tab bar stays).
 */
@Composable
fun PaletteScreen(
    state: HomeState,
    actions: HomeActions,
    onClose: () -> Unit,
    onSendLink: () -> Unit,
    initialQuery: String = "",
) {
    val t = tokens
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var query by remember { mutableStateOf(initialQuery) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Closing puts the keyboard away too, so it is not left up over Home.
    val close = { focusManager.clearFocus(); keyboard?.hide(); onClose() }
    BackHandler(onBack = close)

    val revision by NotesStore.revision.collectAsState()
    val notes = remember(revision) { runCatching { NotesStore.notes(context) }.getOrDefault(emptyList()) }
    val snippets = remember(revision) { runCatching { NotesStore.snippets(context) }.getOrDefault(emptyList()) }

    // Rebuilt only when what it is made of changes (Home's state is rebuilt every second, equal most of the time).
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val built = remember(state, notes, snippets, configuration) { buildItems(context, state, notes, snippets) }
    val sections = remember(built, query) { PaletteModel.search(built.items, query) }

    val copied = stringResource(R.string.snippets_copied)
    fun run(id: String) {
        close()
        // A dimmed action says why and how to fix it; it never sends anything.
        built.items.firstOrNull { it.id == id }?.blocked?.let { b -> return actions.onBlocked(id, built.items.first { it.id == id }.title, b) }
        when {
            id == Action.SEND_FILES -> actions.onSendFiles()
            id == Action.SEND_CLIPBOARD -> actions.onSendClipboard()
            id == Action.SEND_LINK -> onSendLink()
            id == Action.DESK_SCREEN -> actions.onDeskScreen()
            id == Action.RING -> actions.onRing()
            id == Action.PING -> actions.onPing()
            id == Action.LOCK -> actions.onLock()
            id == Action.UNLOCK -> actions.onUnlockDesk()
            id == Action.SHARE_SCREEN -> actions.onShareScreen()
            id == Action.NEW_TERMINAL -> actions.onNewTerminal()
            id == Action.OPEN_TERMINALS -> actions.onTerminals()
            id == Action.NEW_NOTE -> actions.onNewNote()
            id == Action.OPEN_BUCKET -> actions.onBucket()
            id == Action.PAIR_ANOTHER -> actions.onPairAnother()
            id == Action.GUIDE -> actions.onGuide()
            id.startsWith("file:") -> built.files[id]?.let(actions.onOpenRecent)
            id.startsWith("note:") -> actions.onNote(id.removePrefix("note:"))
            id.startsWith("snippet:") -> built.snippets[id]?.let { clipboard.setText(AnnotatedString(it)); actions.onMessage(copied) }
            id.startsWith("settings:") -> actions.onOpenSettings(id.removePrefix("settings:"))
            id.startsWith("computer:") -> id.removePrefix("computer:").let { if (it != state.deviceId) actions.onSelectComputer(it) }
        }
    }

    // The keyboard only needs room for what the tab bar does not already take (a phone has the bar; a tablet's rail is on the side).
    val bar = if (isWide()) 0.dp else TabBarHeight
    Column(
        Modifier
            .fillMaxSize()
            .background(t.bg)
            // Covers Home: taps and sideways drags (which would swipe the tabs) stop here.
            .pointerInput(Unit) { detectTapGestures { } }
            .draggable(rememberDraggableState { }, Orientation.Horizontal)
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets(bottom = bar))),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DeskIconButton(R.drawable.sym_arrow_back, stringResource(R.string.palette_close), close)
            DeskSearchField(
                query, { query = it }, stringResource(R.string.palette_hint), stringResource(R.string.palette_clear), Modifier.weight(1f), focus,
                // The Search key: the list is already filtered as you type, so it only puts the keyboard away.
                onSearch = { focusManager.clearFocus(); keyboard?.hide() },
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sections.forEach { section ->
                SectionHeader(groupTitle(section.group))
                RowGroup { section.items.forEach { item -> PaletteRow(item) { run(item.id) } } }
            }
            if (sections.isEmpty()) {
                Text(
                    stringResource(R.string.palette_nothing, query.trim()),
                    fontSize = 14.sp, lineHeight = 20.sp, color = t.textDim,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun groupTitle(group: PaletteGroup): String = stringResource(
    when (group) {
        PaletteGroup.ACTIONS -> R.string.palette_group_actions
        PaletteGroup.FILES -> R.string.palette_group_files
        PaletteGroup.NOTES -> R.string.palette_group_notes
        PaletteGroup.SNIPPETS -> R.string.palette_group_snippets
        PaletteGroup.SETTINGS -> R.string.palette_group_settings
        PaletteGroup.COMPUTERS -> R.string.palette_group_computers
    },
)

@Composable
private fun PaletteRow(item: PaletteItem, onClick: () -> Unit) {
    val t = tokens
    val big = LocalDensity.current.fontScale > 1.4f
    Row(
        Modifier
            .fillMaxWidth()
            .background(t.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .alpha(if (item.blocked == null) 1f else 0.55f)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(item.icon, size = 36.dp, radius = 18.dp)
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
            // What a dimmed action needs is said whole, under its name.
            if ((big || item.blocked != null) && item.hint.isNotEmpty()) Text(item.hint, fontSize = 12.sp, lineHeight = 16.sp, color = t.textDim)
        }
        if (!big && item.blocked == null && item.hint.isNotEmpty()) {
            Text(item.hint, fontSize = 12.sp, lineHeight = 16.sp, color = t.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.6f, fill = false))
        }
    }
}

private class Built(val items: List<PaletteItem>, val files: Map<String, org.desklink.mobile.plugins.share.TransferRecord>, val snippets: Map<String, String>)

private fun buildItems(context: android.content.Context, state: HomeState, notes: List<org.desklink.mobile.plugins.notes.Note>, snippets: List<org.desklink.mobile.plugins.notes.Snippet>): Built {
    val items = mutableListOf<PaletteItem>()
    val name = state.name
    fun action(id: String, icon: Int, title: String, hint: String = "", keywords: String = "") {
        val blocked = HomeDerive.blocker(state, id)
        items.add(PaletteItem(id, PaletteGroup.ACTIONS, title, icon, blocked?.let { context.getString(HomeDerive.needs(it), name) } ?: hint, keywords, blocked))
    }
    val kwClipboard = context.getString(R.string.palette_keywords_clipboard)
    val kwFiles = context.getString(R.string.palette_keywords_files)
    val kwScreen = context.getString(R.string.palette_keywords_screen)
    val kwRing = context.getString(R.string.palette_keywords_ring)
    val kwTerminal = context.getString(R.string.palette_keywords_terminal)
    val kwPair = context.getString(R.string.palette_keywords_pair)
    val hintScreen = context.getString(R.string.home_tile_screen_hint)
    val hintRing = context.getString(R.string.home_tile_ring_hint)
    val hintLock = context.getString(R.string.home_tile_lock_hint)
    PaletteModel.offeredActions(state).forEach { id ->
        when (id) {
            Action.SEND_FILES -> action(id, R.drawable.sym_upload_file, context.getString(R.string.home_send_files), keywords = kwFiles)
            Action.SEND_CLIPBOARD -> action(id, R.drawable.sym_content_paste, context.getString(R.string.home_send_clipboard_short), keywords = kwClipboard)
            Action.SEND_LINK -> action(id, R.drawable.sym_link, context.getString(R.string.home_send_link))
            Action.DESK_SCREEN -> action(id, R.drawable.sym_desktop_windows, context.getString(R.string.home_desk_screen), hintScreen, kwScreen)
            Action.RING -> action(id, R.drawable.sym_vibration, context.getString(R.string.home_ring_desk), hintRing, kwRing)
            Action.PING -> action(id, R.drawable.sym_waving_hand, context.getString(R.string.home_ping_desk))
            Action.LOCK -> action(id, R.drawable.sym_lock, context.getString(R.string.home_lock_desk), hintLock)
            Action.UNLOCK -> action(id, R.drawable.sym_lock_open, context.getString(R.string.home_unlock_desk, name))
            Action.SHARE_SCREEN -> action(id, R.drawable.sym_mobile_screen_share, context.getString(if (state.live.phoneScreen) R.string.home_stop_sharing else R.string.home_share_screen))
            Action.NEW_TERMINAL -> action(id, R.drawable.sym_terminal, context.getString(R.string.tab_quick_new_terminal), keywords = kwTerminal)
            Action.OPEN_TERMINALS -> action(id, R.drawable.sym_terminal, context.getString(R.string.palette_open_terminals), keywords = kwTerminal)
            Action.NEW_NOTE -> action(id, R.drawable.sym_edit_note, context.getString(R.string.tab_quick_new_note))
            Action.OPEN_BUCKET -> action(id, R.drawable.sym_folder, context.getString(R.string.palette_open_bucket))
            Action.PAIR_ANOTHER -> action(id, R.drawable.sym_add, context.getString(R.string.home_pair_another), keywords = kwPair)
            Action.GUIDE -> action(id, R.drawable.sym_info, context.getString(R.string.guide_open), keywords = context.getString(R.string.guide_keywords))
        }
    }
    val sent = context.getString(R.string.home_transfer_sent)
    val received = context.getString(R.string.home_transfer_received)
    val files = state.history.associateBy { "file:" + it.id }
    state.history.forEach { r ->
        items.add(PaletteItem("file:" + r.id, PaletteGroup.FILES, r.name, org.desklink.mobile.ui.compose.screen.transfers.iconFor(org.desklink.mobile.plugins.share.previewKind(r.mime, r.name)), if (r.sent) sent else received))
    }
    notes.forEach { n ->
        val preview = n.body.lineSequence().map(String::trim).filter(String::isNotEmpty).drop(if (n.title.isBlank()) 1 else 0).firstOrNull().orEmpty()
        items.add(PaletteItem("note:" + n.id, PaletteGroup.NOTES, n.label, R.drawable.sym_edit_note, "", preview))
    }
    val copy = context.getString(R.string.palette_copy_snippet)
    snippets.forEach { s ->
        items.add(PaletteItem("snippet:" + s.id, PaletteGroup.SNIPPETS, s.label, R.drawable.sym_content_paste, copy, s.text.take(200)))
    }
    SettingsPage.entries.forEach { page ->
        items.add(PaletteItem("settings:" + page.route, PaletteGroup.SETTINGS, context.getString(page.title), page.icon, "", context.getString(page.summary)))
    }
    items.add(PaletteItem("settings:" + SettingsPage.PERMISSIONS, PaletteGroup.SETTINGS, context.getString(R.string.settings_permissions), R.drawable.sym_tune))
    val here = context.getString(R.string.palette_computer_here)
    state.computers.forEach { c ->
        items.add(PaletteItem("computer:" + c.id, PaletteGroup.COMPUTERS, c.name, R.drawable.sym_desktop_windows, if (c.id == state.deviceId) here else context.getString(connectionShortRes(c.connection))))
    }
    return Built(items, files, snippets.associate { "snippet:" + it.id to it.text })
}
