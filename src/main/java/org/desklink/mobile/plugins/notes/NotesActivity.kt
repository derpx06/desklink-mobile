/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.notes

import org.desklink.mobile.ui.compose.components.DialogAction
import org.desklink.mobile.ui.compose.components.DeskDialog
import org.desklink.mobile.extensions.isReadable
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
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.tokens

/**
 * The note editor (F6): saves as you type and reads markdown-lite. Also "Save to DeskLink notes" in the share sheet.
 * It lists nothing: the Notes tab is the one list of notes and snippets, so when there is no note to edit (or the
 * editor closes) this goes there.
 */
class NotesActivity : AppCompatActivity() {
    /** The note being edited: an id, [NEW] for one not written yet, or null for none (then the Notes tab shows). */
    private val editing = mutableStateOf<String?>(null)

    /** Which part of the Notes tab to land on when this closes. */
    private var snippets = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (!intent.isReadable()) return finish()
        savedInstanceState?.let {
            editing.value = it.getString(STATE_EDITING)
        } ?: handle(intent)
        setContent { DeskLinkTheme(this) { org.desklink.mobile.ui.compose.SecureWindow(); Root() } }
    }

    override fun onStop() {
        NotesStore.flush(this)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.isReadable()) handle(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_EDITING, editing.value)
    }

    private val pendingShare = mutableStateOf<Pair<String, String>?>(null)

    private fun saveShared(subject: String, text: String) {
        val (body, cut) = NotesStore.fit(text)
        val id = NotesStore.newNote(this, subject.lineSequence().first(), body)
        // A text that is too long is never cut without saying so.
        Toast.makeText(this, if (id == null) R.string.notes_full else if (cut) R.string.notes_saved_shared_cut else R.string.notes_saved_shared, if (cut) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        editing.value = id
    }

    private fun handle(intent: Intent) {
        when {
            intent.action == Intent.ACTION_SEND -> {
                // Any app can send this intent: asked first (see Root), saved only on a yes.
                pendingShare.value = org.desklink.mobile.plugins.share.ShareIntake.forNotes(intent) ?: return
            }
            intent.getBooleanExtra(EXTRA_NEW, false) -> editing.value = NEW
            intent.hasExtra(EXTRA_NOTE) -> editing.value = intent.getStringExtra(EXTRA_NOTE)
            // No note to edit: the Notes tab, on its Snippets segment if that was asked.
            else -> {
                snippets = intent.getBooleanExtra(EXTRA_SNIPPETS, false)
                editing.value = null
            }
        }
    }

    @Composable
    private fun Root() {
        pendingShare.value?.let { (subject, text) ->
            DeskDialog(
                onDismissRequest = { pendingShare.value = null },
                title = stringResource(R.string.notes_confirm_save_title),
                text = { androidx.compose.material3.Text((subject.ifBlank { text }).take(200)) },
                confirm = DialogAction(stringResource(R.string.notes_confirm_save)) { pendingShare.value = null; saveShared(subject, text) },
                dismiss = DialogAction(stringResource(R.string.cancel)) { pendingShare.value = null },
            )
        }
        val id = editing.value
        // Nothing to edit, or the editor closed (and no share question open): the Notes tab, which is where the notes are listed.
        LaunchedEffect(id, pendingShare.value) {
            if (id == null && pendingShare.value == null) {
                startActivity(org.desklink.mobile.ui.MainActivity.notesIntent(this@NotesActivity, snippets))
                finish()
            }
        }
        if (id == null) {
            Box(Modifier.fillMaxSize().background(tokens.bg))
        } else {
            BackHandler { editing.value = null }
            androidx.compose.runtime.key(id) { Editor(id) { editing.value = null } }
        }
    }

    private val ui by lazy { NotesUi(this, editing) }

    @Composable
    private fun Editor(id: String, onClose: () -> Unit) = ui.Editor(id, onDone = onClose)

    companion object {
        const val NEW = "\u0000new"
        private const val STATE_EDITING = "editing"
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
