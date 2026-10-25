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
        setContent { DeskLinkTheme(this) { org.desklink.mobile.ui.compose.SecureWindow(); Root() } }
    }

    override fun onStop() {
        NotesStore.flush(this)
        super.onStop()
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
                val (body, cut) = NotesStore.fit(text)
                val id = NotesStore.newNote(this, subject.lineSequence().first(), body)
                // A text that is too long is never cut without saying so.
                Toast.makeText(this, if (id == null) R.string.notes_full else if (cut) R.string.notes_saved_shared_cut else R.string.notes_saved_shared, if (cut) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                tab.value = TAB_NOTES
                editing.value = id
            }
            intent.getBooleanExtra(EXTRA_NEW, false) -> {
                tab.value = TAB_NOTES
                editing.value = NEW
            }
            intent.hasExtra(EXTRA_NOTE) -> {
                tab.value = TAB_NOTES
                editing.value = intent.getStringExtra(EXTRA_NOTE)
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

    private val ui by lazy { NotesUi(this, editing) }

    @Composable
    private fun NotesList() = ui.NotesList()

    @Composable
    private fun SnippetsList() = ui.SnippetsList()

    @Composable
    private fun Editor(id: String, onClose: () -> Unit) = ui.Editor(id, onClose)

    companion object {
        const val NEW = "\u0000new"
        private const val TAB_NOTES = 0
        private const val TAB_SNIPPETS = 1
        private const val STATE_EDITING = "editing"
        private const val STATE_TAB = "tab"
        const val EXTRA_SNIPPETS = "snippets"

        const val EXTRA_NOTE = "note"
        const val EXTRA_NEW = "new"

        fun intent(context: Context, snippets: Boolean = false, noteId: String? = null, newNote: Boolean = false): Intent =
            Intent(context, NotesActivity::class.java).putExtra(EXTRA_SNIPPETS, snippets).putExtra(EXTRA_NEW, newNote)
                .apply { noteId?.let { putExtra(EXTRA_NOTE, it) } }

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
                NotesStore.PinResult.Full -> R.string.snippets_full
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
