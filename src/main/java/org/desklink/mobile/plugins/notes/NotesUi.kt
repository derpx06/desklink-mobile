/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.notes

import org.desklink.mobile.ui.compose.components.DialogAction
import org.desklink.mobile.ui.compose.components.DeskDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import org.desklink.mobile.ui.compose.DeskLinkShape
import org.desklink.mobile.ui.compose.DeskExtendedFab
import org.desklink.mobile.ui.compose.SecondaryButton
import org.desklink.mobile.ui.compose.components.CheckItem
import org.desklink.mobile.ui.compose.components.DeskSearchField
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.components.DeskCard
import org.desklink.mobile.ui.compose.components.EmptyState
import org.desklink.mobile.ui.compose.components.LargeTitle
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.tokens
import androidx.activity.ComponentActivity
import org.desklink.mobile.plugins.notes.NotesActivity.Companion.NEW
import org.desklink.mobile.plugins.notes.NotesActivity.Companion.copy
import org.desklink.mobile.plugins.notes.NotesActivity.Companion.pinClipboard

/** Saved-state saver for text: kept only up to [SAVED_STATE_CAP] chars; a longer one is not saved, so the caller's default (the store) applies. */
internal const val SAVED_STATE_CAP = 20_000
internal val CappedText: androidx.compose.runtime.saveable.Saver<String, Any> = androidx.compose.runtime.saveable.Saver(
    save = { if (it.length <= SAVED_STATE_CAP) it else null },
    restore = { it as? String },
)

/**
 * The notes and snippets screens as a reusable piece: [NotesActivity] hosts them full screen, and the Notes
 * tab hosts them under its own header. [editing] is the note being edited ([NEW], an id) or null.
 */
class NotesUi(
    private val host: ComponentActivity,
    val editing: androidx.compose.runtime.MutableState<String?>,
    /** Shows [message] with an Undo that runs `undo`; null where there is no snackbar (the full-screen Notes activity). */
    private val undoable: ((message: String, undo: () -> Unit) -> Unit)? = null,
) {
    /**
     * The notes. [externalQuery] is the Notes tab's own search field (it searches notes and snippets together); without one the
     * list shows a field of its own (the Notes screen).
     */
    @Composable
    fun NotesList(externalQuery: String? = null) {
        val t = tokens
        val revision by NotesStore.revision.collectAsState()
        var ownQuery by rememberSaveable { mutableStateOf("") }
        val query = externalQuery ?: ownQuery
        val notes = remember(revision, query) { NotesStore.notes(host).filter { NotesModel.matches(it, query) } }
        val narrow = LocalConfiguration.current.screenWidthDp < 340 || LocalDensity.current.fontScale > 1.3f
        val blocks = remember(notes, narrow) { NotesModel.blocks(notes, if (narrow) 1 else 2) }
        Column(Modifier.fillMaxSize()) {
            if (externalQuery == null) {
                DeskSearchField(ownQuery, { ownQuery = it }, stringResource(R.string.notes_search), stringResource(R.string.palette_clear), Modifier.padding(horizontal = 16.dp))
            }
            // Counted off the main thread: a big shelf of copies is real work.
            var preview by remember { mutableStateOf(0 to 0) }
            LaunchedEffect(revision) { preview = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { NotesStore.duplicateCopiesPreview(host) } }
            val (removable, kept) = preview
            var confirmTidy by remember { mutableStateOf(false) }
            if (removable + kept > 0) {
                SecondaryButton(
                    stringResource(R.string.notes_tidy),
                    {
                        if (removable == 0) {
                            Toast.makeText(host, host.getString(R.string.notes_tidy_none, kept), Toast.LENGTH_LONG).show()
                        } else {
                            confirmTidy = true
                        }
                    },
                    tonal = true,
                    height = 48.dp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (confirmTidy) {
                DeskDialog(
                    onDismissRequest = { confirmTidy = false },
                    title = stringResource(R.string.notes_tidy_title),
                    text = { Text(stringResource(R.string.notes_tidy_body, removable, kept)) },
                    confirm = DialogAction(stringResource(R.string.notes_tidy_confirm), destructive = true) {
                        confirmTidy = false
                        val removed = NotesStore.removeDuplicateCopies(host)
                        Toast.makeText(host, host.getString(R.string.notes_tidy_done, removed), Toast.LENGTH_SHORT).show()
                    },
                    dismiss = DialogAction(stringResource(android.R.string.cancel)) { confirmTidy = false },
                )
            }
            Box(Modifier.weight(1f)) {
                if (notes.isEmpty()) {
                    EmptyState(
                        R.drawable.sym_edit_note,
                        stringResource(if (query.isBlank()) R.string.notes_empty_title else R.string.notes_no_match),
                        stringResource(R.string.notes_empty_body),
                        // Scrolls clear of the New note button, which floats over the bottom.
                        Modifier.verticalScroll(rememberScrollState()).padding(bottom = 88.dp),
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(blocks, key = { b -> when (b) { is NotesModel.Block.Checklist -> b.note.id; is NotesModel.Block.Row -> "row-" + b.notes.first().id } }) { block ->
                            when (block) {
                                is NotesModel.Block.Checklist -> ChecklistCard(block.note) { editing.value = block.note.id }
                                is NotesModel.Block.Row -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    block.notes.forEach { note -> NoteCard(note, Modifier.weight(1f).fillMaxHeight()) { editing.value = note.id } }
                                    if (!narrow && block.notes.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                DeskExtendedFab(
                    stringResource(R.string.notes_new), R.drawable.sym_add, { editing.value = NEW },
                    Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
                )
            }
        }
    }

    @Composable
    private fun NoteCard(note: Note, modifier: Modifier, onOpen: () -> Unit) {
        val t = tokens
        val pinned = stringResource(R.string.notes_pinned)
        DeskCard(modifier.semantics(mergeDescendants = true) { if (note.pinned) contentDescription = "${note.label}, $pinned" }, onClick = onOpen) {
            Column(Modifier.padding(14.dp).heightIn(min = 92.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(note.label, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (note.pinned) Icon(painterResource(R.drawable.sym_keep_filled), contentDescription = null, tint = t.accentText, modifier = Modifier.size(18.dp))
                }
                val preview = note.body.lineSequence().map(String::trim).filter(String::isNotEmpty).drop(if (note.title.isBlank()) 1 else 0).joinToString("  ")
                if (preview.isNotEmpty()) Text(preview, fontSize = 13.sp, lineHeight = 18.sp, color = t.textDim, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(DateUtils.getRelativeTimeSpanString(note.modified).toString(), fontSize = 12.sp, lineHeight = 16.sp, color = t.textDim)
            }
        }
    }

    /** A note of tick boxes: the first four on the accent card, "2 of 4"; a tick is saved as an edit of the note (the desk sees it). */
    @Composable
    private fun ChecklistCard(note: Note, onOpen: () -> Unit) {
        val t = tokens
        val items = remember(note.body) { NotesModel.checklist(note.body) }
        val ticked = items.count { it.done }
        val pinned = stringResource(R.string.notes_pinned)
        val ink = t.onAccentContainer
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(t.accentContainer).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = stringResource(R.string.notes_edit), onClick = onOpen)
                    .semantics(mergeDescendants = true) { if (note.pinned) contentDescription = "${note.label}, $pinned" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (note.pinned) Icon(painterResource(R.drawable.sym_keep_filled), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
                Text(note.label, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.notes_checklist_progress, ticked, items.size), fontSize = 12.sp, color = ink, modifier = Modifier.padding(end = 8.dp))
            }
            items.take(4).forEach { item ->
                CheckItem(item.label, item.done, { done ->
                    NotesStore.editNote(host, note.id, loaded = note.modified) { it.copy(body = NotesModel.setChecked(it.body, item.line, done)) }
                })
            }
            if (items.size > 4) Text(stringResource(R.string.notes_checklist_more, items.size - 4), fontSize = 12.sp, color = ink, modifier = Modifier.padding(top = 2.dp))
            Text(DateUtils.getRelativeTimeSpanString(note.modified).toString(), fontSize = 12.sp, color = ink.copy(alpha = 0.8f), modifier = Modifier.padding(top = 6.dp))
        }
    }

    @Composable
    fun SnippetsList(externalQuery: String = "") {
        val t = tokens
        val revision by NotesStore.revision.collectAsState()
        val snippets = remember(revision, externalQuery) { NotesStore.snippets(host).filter { NotesModel.matches(it, externalQuery) } }
        Column(Modifier.fillMaxSize()) {
            SecondaryButton(
                stringResource(R.string.snippets_pin_clipboard),
                { pinClipboard(host) },
                tonal = true,
                icon = R.drawable.sym_keep,
                height = 48.dp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (snippets.isEmpty()) {
                EmptyState(R.drawable.sym_content_paste, stringResource(if (externalQuery.isBlank()) R.string.snippets_empty_title else R.string.notes_no_match), stringResource(R.string.snippets_empty_body))
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(snippets, key = { it.id }) { snippet ->
                        DeskCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(start = 14.dp, top = 6.dp, end = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(snippet.label, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    // Copy is the snippet's one tap; the label names the snippet for TalkBack.
                                    val copyLabel = stringResource(R.string.snippets_copy_named, snippet.label)
                                    Box(
                                        Modifier.heightIn(min = 48.dp).clip(DeskLinkShape.stadium).clickable(role = Role.Button, onClickLabel = copyLabel) { copy(host, snippet.text) }.semantics { contentDescription = copyLabel },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            stringResource(R.string.snippets_copy), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = t.onAccentContainer,
                                            modifier = Modifier.background(t.accentContainer, DeskLinkShape.stadium).heightIn(min = 32.dp).padding(horizontal = 14.dp, vertical = 6.dp),
                                        )
                                    }
                                    val deletedText = stringResource(R.string.snippets_deleted)
                    IconButton(
                        onClick = {
                            val text = snippet.text
                            NotesStore.deleteSnippet(host, snippet.id)
                            // Undo pins the same text again (a snippet is its text: nothing else is lost).
                            undoable?.invoke(deletedText) { NotesStore.pin(host, text) }
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                                        Icon(painterResource(R.drawable.sym_delete), contentDescription = stringResource(R.string.snippets_delete, snippet.label), tint = t.textDim)
                                    }
                                }
                                if (snippet.text.trim() != snippet.label) {
                                    Text(
                                        snippet.text.trim(), fontFamily = MonoFamily, fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xFFD9DBE0), maxLines = 4, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(end = 10.dp).fillMaxWidth().background(t.terminalBg, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- editor ---------------------------------------------------------------------------------------------

    @Composable
    fun Editor(start: String, barInset: Dp = 0.dp, onDone: () -> Unit) {
        val t = tokens
        val context = LocalContext.current
        var id by rememberSaveable { mutableStateOf(start.takeIf { it != NEW }) }
        val first = remember { id?.let { NotesStore.note(context, it) } }
        // A draft the store does not have (a body over the cap, kept across a rotation) wins over the stored note.
        val hadDraft = remember { NoteDrafts.get(NoteDrafts.key(id)) != null }
        val draft = remember { NoteDrafts.valid(NoteDrafts.key(id), first?.modified ?: 0L) }
        if (hadDraft && draft == null) {
            LaunchedEffect(Unit) { Toast.makeText(context, R.string.notes_draft_discarded, Toast.LENGTH_LONG).show() }
        }
        var title by rememberSaveable { mutableStateOf(draft?.title ?: first?.title.orEmpty()) }
        // A big note in the saved-state Bundle crashes the activity stop (TransactionTooLarge): only a small body goes in it, and a
        // large one is restored from the notes store (flushed at onStop).
        var body by rememberSaveable(stateSaver = CappedText) { mutableStateOf(draft?.body ?: first?.body.orEmpty()) }
        var pinned by rememberSaveable { mutableStateOf(draft?.pinned ?: first?.pinned ?: false) }
        var reading by rememberSaveable { mutableStateOf(first != null && first.body.isNotBlank()) }
        /** The version shown: a newer one from the desk replaces it while nothing here is unsaved. */
        var shown by rememberSaveable { mutableStateOf(first?.modified ?: 0L) }
        var dirty by remember { mutableStateOf(false) }

        fun save() {
            // Over the cap: kept in the editor and in memory, not saved (the banner says why).
            if (dirty && !Shelf.syncable(body)) NoteDrafts.put(NoteDrafts.key(id), NoteDrafts.Draft(title, body, pinned, shown))
            if (!dirty || !Shelf.syncable(body)) return
            dirty = false
            NoteDrafts.clear(NoteDrafts.key(id))
            val current = id
            if (current == null) {
                if (title.isBlank() && body.isBlank()) return
                id = NotesStore.newNote(context, title, body) ?: return Toast.makeText(context, R.string.notes_full, Toast.LENGTH_SHORT).show()
            } else {
                // The version this save left, read in the same step as the save: a read afterwards could be the desk's edit that
                // landed in between, which the next save would then type over without a word.
                NotesStore.editNote(context, current, loaded = shown) { it.copy(title = title, body = body, pinned = pinned) }?.let { shown = it }
                return
            }
            shown = id?.let { NotesStore.note(context, it)?.modified } ?: shown
        }

        // Autosave half a second after the last keystroke, and on leaving.
        LaunchedEffect(title, body, pinned) {
            if (dirty && !Shelf.syncable(body)) NoteDrafts.put(NoteDrafts.key(id), NoteDrafts.Draft(title, body, pinned, shown))
            if (dirty) {
                delay(500)
                save()
            }
        }
        val latestSave by rememberUpdatedState(::save)
        DisposableEffect(Unit) { onDispose { latestSave() } }

        val revision by NotesStore.revision.collectAsState()
        LaunchedEffect(revision, dirty) {
            val current = id ?: return@LaunchedEffect
            val stored = NotesStore.note(context, current)
            when {
                stored == null -> onDone() // deleted on the desk
                stored.modified != shown && !dirty -> {
                    title = stored.title
                    body = stored.body
                    pinned = stored.pinned
                    shown = stored.modified
                }
            }
        }

        Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets(bottom = barInset)))) {
            val tooLong = !Shelf.syncable(body)
            BackHandler(tooLong) { Toast.makeText(context, R.string.notes_too_long, Toast.LENGTH_SHORT).show() }
            ScreenHeader({ if (tooLong) Toast.makeText(context, R.string.notes_too_long, Toast.LENGTH_SHORT).show() else { save(); onDone() } }) {
                IconButton(onClick = { pinned = !pinned; dirty = true }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painterResource(if (pinned) R.drawable.sym_keep_filled else R.drawable.sym_keep),
                        contentDescription = stringResource(if (pinned) R.string.notes_unpin else R.string.notes_pin),
                        tint = if (pinned) t.accentText else t.text,
                    )
                }
                IconButton(onClick = { reading = !reading }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painterResource(if (reading) R.drawable.sym_edit else R.drawable.sym_visibility),
                        contentDescription = stringResource(if (reading) R.string.notes_edit else R.string.notes_preview),
                        tint = t.text,
                    )
                }
                IconButton(
                    onClick = {
                        val (oldTitle, oldBody, oldPinned) = Triple(title, body, pinned)
                        val gone = id?.let { NotesStore.deleteNote(context, it, loaded = shown) } ?: true
                        NoteDrafts.clear(NoteDrafts.key(id))
                        dirty = false
                        // A newer version from the desk beats the delete: say so instead of "Note deleted".
                        val undo = undoable
                        if (gone && undo != null && id != null && (oldTitle.isNotBlank() || oldBody.isNotBlank())) {
                            // Undo makes the note again (a new one, same words and pin); the desk gets it as a new note.
                            undo(host.getString(R.string.notes_deleted)) {
                                NotesStore.newNote(host, oldTitle, oldBody)?.let { back -> if (oldPinned) NotesStore.editNote(host, back) { it.copy(pinned = true) } }
                            }
                        } else {
                            Toast.makeText(context, if (gone) R.string.notes_deleted else R.string.notes_delete_kept, Toast.LENGTH_SHORT).show()
                        }
                        onDone()
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(painterResource(R.drawable.sym_delete), contentDescription = stringResource(R.string.notes_delete), tint = t.text)
                }
            }
            val titleHint = stringResource(R.string.notes_title_hint)
            Box(Modifier.padding(horizontal = 20.dp)) {
                if (title.isEmpty()) Text(titleHint, fontSize = 26.sp, fontWeight = FontWeight.Medium, color = t.textDim)
                BasicTextField(
                    value = title,
                    onValueChange = { title = it.replace("\n", "").take(Shelf.MAX_NOTE_TITLE_CHARS); dirty = true },
                    textStyle = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, color = t.text),
                    cursorBrush = SolidColor(t.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = titleHint },
                )
            }
            if (tooLong) {
                Text(
                    stringResource(R.string.notes_too_long),
                    fontSize = 13.sp,
                    color = t.coralText,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).background(t.coralContainer, RoundedCornerShape(12.dp)).padding(10.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            val bodyHint = stringResource(R.string.notes_body_hint)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                if (reading) {
                    Text(
                        MarkdownLite.render(body),
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        color = t.text,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).clickable(onClickLabel = stringResource(R.string.notes_edit)) { reading = false },
                    )
                } else {
                    Box {
                        if (body.isEmpty()) Text(bodyHint, fontSize = 16.sp, color = t.textDim)
                        BasicTextField(
                            value = body,
                            onValueChange = { body = it; dirty = true },
                            textStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = t.text),
                            cursorBrush = SolidColor(t.accent),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).semantics { contentDescription = bodyHint },
                        )
                    }
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars).padding(bottom = 24.dp))
            }
        }
    }

}
