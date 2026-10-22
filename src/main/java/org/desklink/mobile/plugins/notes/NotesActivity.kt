/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.notes

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.semantics.Role
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
import org.desklink.mobile.ui.compose.components.PillButton
import org.desklink.mobile.ui.compose.components.PillStyle
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.tokens

/**
 * Notes and snippets (F6): a list with search, pinned notes first; an editor that saves as you type
 * and reads markdown-lite; snippets that copy on a tap. Also "Save to DeskLink notes" in the share
 * sheet, and the clipboard tile's long-press (snippets).
 */
class NotesActivity : AppCompatActivity() {
    /** The note being edited: an id, [NEW] for one not written yet, or null for the list. */
    private val editing = mutableStateOf<String?>(null)
    private val tab = mutableStateOf(TAB_NOTES)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            editing.value = it.getString(STATE_EDITING)
            tab.value = it.getInt(STATE_TAB)
        } ?: handle(intent)
        setContent { DeskLinkTheme(this) { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_EDITING, editing.value)
        outState.putInt(STATE_TAB, tab.value)
    }

    private fun handle(intent: Intent) {
        when {
            intent.action == Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
                if (text.isBlank() && subject.isBlank()) return
                val id = NotesStore.newNote(this, subject.lineSequence().first(), NotesStore.clampBody(text))
                Toast.makeText(this, if (id != null) R.string.notes_saved_shared else R.string.notes_full, Toast.LENGTH_SHORT).show()
                tab.value = TAB_NOTES
                editing.value = id
            }
            intent.getBooleanExtra(EXTRA_SNIPPETS, false) -> {
                tab.value = TAB_SNIPPETS
                editing.value = null
            }
        }
    }

    @Composable
    private fun Root() {
        val id = editing.value
        if (id == null) {
            Lists()
        } else {
            BackHandler { editing.value = null }
            Editor(id) { editing.value = null }
        }
    }

    // ---- lists --------------------------------------------------------------------------------------------

    @Composable
    private fun Lists() {
        val t = tokens
        Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
            ScreenHeader(::finish)
            LargeTitle(stringResource(R.string.notes_title), subtitle = stringResource(R.string.notes_subtitle))
            Tabs(tab.value) { tab.value = it }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f)) {
                if (tab.value == TAB_NOTES) NotesList() else SnippetsList()
            }
        }
    }

    @Composable
    private fun Tabs(selected: Int, onSelect: (Int) -> Unit) {
        val t = tokens
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(50)).background(t.fill).padding(4.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(R.string.notes_tab_notes, R.string.notes_tab_snippets).forEachIndexed { index, label ->
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

    @Composable
    private fun NotesList() {
        val t = tokens
        val revision by NotesStore.revision.collectAsState()
        var query by rememberSaveable { mutableStateOf("") }
        val notes = remember(revision, query) {
            NotesStore.notes(this).filter { query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true) }
        }
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(painterResource(R.drawable.sym_search), contentDescription = null) },
                placeholder = { Text(stringResource(R.string.notes_search)) },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Box(Modifier.weight(1f)) {
                if (notes.isEmpty()) {
                    EmptyState(
                        R.drawable.sym_edit_note,
                        stringResource(if (query.isBlank()) R.string.notes_empty_title else R.string.notes_no_match),
                        stringResource(R.string.notes_empty_body),
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(notes, key = { it.id }) { note -> NoteCard(note) { editing.value = note.id } }
                    }
                }
                PillButton(
                    stringResource(R.string.notes_new),
                    { editing.value = NEW },
                    icon = R.drawable.sym_add,
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
                )
            }
        }
    }

    @Composable
    private fun NoteCard(note: Note, onOpen: () -> Unit) {
        val t = tokens
        val pinned = stringResource(R.string.notes_pinned)
        DeskCard(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { if (note.pinned) contentDescription = "${note.label}, $pinned" }, onClick = onOpen) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(note.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (note.pinned) Icon(painterResource(R.drawable.sym_keep_filled), contentDescription = null, tint = t.accentText, modifier = Modifier.size(18.dp))
                }
                val preview = note.body.lineSequence().map(String::trim).filter(String::isNotEmpty).drop(if (note.title.isBlank()) 1 else 0).joinToString("  ")
                if (preview.isNotEmpty()) Text(preview, fontSize = 13.sp, lineHeight = 18.sp, color = t.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(DateUtils.getRelativeTimeSpanString(note.modified).toString(), fontSize = 12.sp, color = t.textFaint)
            }
        }
    }

    @Composable
    private fun SnippetsList() {
        val t = tokens
        val revision by NotesStore.revision.collectAsState()
        val snippets = remember(revision) { NotesStore.snippets(this) }
        Column(Modifier.fillMaxSize()) {
            PillButton(
                stringResource(R.string.snippets_pin_clipboard),
                { pinClipboard(this@NotesActivity) },
                style = PillStyle.Tonal,
                icon = R.drawable.sym_keep,
                height = 48.dp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (snippets.isEmpty()) {
                EmptyState(R.drawable.sym_content_paste, stringResource(R.string.snippets_empty_title), stringResource(R.string.snippets_empty_body))
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(snippets, key = { it.id }) { snippet ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(t.surface)
                                .clickable(onClickLabel = stringResource(R.string.snippets_copy)) { copy(this@NotesActivity, snippet.text) }
                                .heightIn(min = 56.dp)
                                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(snippet.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (snippet.text.trim() != snippet.label) {
                                    Text(snippet.text.trim(), fontFamily = MonoFamily, fontSize = 12.sp, color = t.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            IconButton(onClick = { NotesStore.deleteSnippet(this@NotesActivity, snippet.id) }, modifier = Modifier.size(48.dp)) {
                                Icon(painterResource(R.drawable.sym_delete), contentDescription = stringResource(R.string.snippets_delete, snippet.label), tint = t.textDim)
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- editor ---------------------------------------------------------------------------------------------

    @Composable
    private fun Editor(start: String, onDone: () -> Unit) {
        val t = tokens
        val context = LocalContext.current
        var id by rememberSaveable { mutableStateOf(start.takeIf { it != NEW }) }
        val first = remember { id?.let { NotesStore.note(context, it) } }
        var title by rememberSaveable { mutableStateOf(first?.title.orEmpty()) }
        var body by rememberSaveable { mutableStateOf(first?.body.orEmpty()) }
        var pinned by rememberSaveable { mutableStateOf(first?.pinned ?: false) }
        var reading by rememberSaveable { mutableStateOf(first != null && first.body.isNotBlank()) }
        /** The version shown: a newer one from the desk replaces it while nothing here is unsaved. */
        var shown by rememberSaveable { mutableStateOf(first?.modified ?: 0L) }
        var dirty by remember { mutableStateOf(false) }

        fun save() {
            if (!dirty) return
            dirty = false
            val current = id
            if (current == null) {
                if (title.isBlank() && body.isBlank()) return
                id = NotesStore.newNote(context, title, body) ?: return Toast.makeText(context, R.string.notes_full, Toast.LENGTH_SHORT).show()
            } else {
                NotesStore.editNote(context, current) { it.copy(title = title, body = body, pinned = pinned) }
            }
            shown = id?.let { NotesStore.note(context, it)?.modified } ?: shown
        }

        // Autosave half a second after the last keystroke, and on leaving.
        LaunchedEffect(title, body, pinned) {
            if (dirty) {
                delay(500)
                save()
            }
        }
        val latestSave by rememberUpdatedState(::save)
        DisposableEffect(Unit) { onDispose { latestSave() } }

        val revision by NotesStore.revision.collectAsState()
        LaunchedEffect(revision) {
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

        Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding().imePadding()) {
            ScreenHeader({ save(); onDone() }) {
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
                        id?.let { NotesStore.editNote(context, it) { note -> note.copy(deleted = true) } }
                        dirty = false
                        Toast.makeText(context, R.string.notes_deleted, Toast.LENGTH_SHORT).show()
                        onDone()
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(painterResource(R.drawable.sym_delete), contentDescription = stringResource(R.string.notes_delete), tint = t.text)
                }
            }
            val titleHint = stringResource(R.string.notes_title_hint)
            Box(Modifier.padding(horizontal = 20.dp)) {
                if (title.isEmpty()) Text(titleHint, fontSize = 26.sp, fontWeight = FontWeight.Medium, color = t.textFaint)
                BasicTextField(
                    value = title,
                    onValueChange = { title = it.replace("\n", "").take(Shelf.MAX_NOTE_TITLE_CHARS); dirty = true },
                    textStyle = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, color = t.text),
                    cursorBrush = SolidColor(t.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = titleHint },
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
                        if (body.isEmpty()) Text(bodyHint, fontSize = 16.sp, color = t.textFaint)
                        BasicTextField(
                            value = body,
                            onValueChange = { if (it.toByteArray().size <= Shelf.MAX_NOTE_BYTES) { body = it; dirty = true } },
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

    companion object {
        private const val NEW = "\u0000new"
        private const val TAB_NOTES = 0
        private const val TAB_SNIPPETS = 1
        private const val STATE_EDITING = "editing"
        private const val STATE_TAB = "tab"
        const val EXTRA_SNIPPETS = "snippets"

        fun intent(context: Context, snippets: Boolean = false): Intent =
            Intent(context, NotesActivity::class.java).putExtra(EXTRA_SNIPPETS, snippets)

        /** One tap on a snippet: on the clipboard (Android 13+ shows its own confirmation). */
        fun copy(context: Context, text: String) {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.snippets_tab_label), text))
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, R.string.snippets_copied, Toast.LENGTH_SHORT).show()
            }
        }

        /** Pins the clipboard's text (read while this app is in front, as Android requires). */
        fun pinClipboard(context: Context) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            val message = when (NotesStore.pin(context, text)) {
                NotesStore.PinResult.Pinned -> R.string.snippets_pinned
                NotesStore.PinResult.Empty -> R.string.snippets_clipboard_empty
                NotesStore.PinResult.TooLong -> R.string.snippets_too_long
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
