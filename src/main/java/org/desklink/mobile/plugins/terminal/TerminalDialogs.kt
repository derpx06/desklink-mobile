/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.PrimaryButton
import org.desklink.mobile.ui.compose.components.DeskDialog
import org.desklink.mobile.ui.compose.components.DialogAction
import org.desklink.mobile.ui.compose.components.DeskTextField
import org.desklink.mobile.ui.compose.tokens

// The pieces shared by the Terminals tab (the one list of sessions) and the live terminal screen.
// Both start sessions and rename them; neither owns a copy.

/**
 * A new shell on the desk: the "Start in" folder chips go above the button once the desktop reports folders. Agent CLIs are not
 * offered (the person starts them in the shell); a session the desk started with one still shows with its name.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewTerminalSheet(
    deskName: String,
    folders: List<String>,
    onDismiss: () -> Unit,
    /** Always a shell (null launcher), in the folder (null for the desk's default). */
    onStart: (launcher: String?, cwd: String?) -> Unit,
) {
    val t = tokens
    // "Start in": the desk's folders ("~" first), the first one picked. An older desk sends none.
    var folder by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.surface2,
        // The stock handle: TalkBack names it and it expands or collapses the sheet.
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle(width = 32.dp, height = 4.dp, color = t.hairlineStrong) },
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.terminal_new_terminal), fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.terminal_new_runs_on, deskName), fontSize = 14.sp, color = t.textDim)
            }
            if (folders.isNotEmpty()) {
                Text(stringResource(R.string.terminal_new_start_in), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = t.textDim)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
                    folders.forEachIndexed { index, path ->
                        val selected = index == folder
                        Box(
                            Modifier
                                .heightIn(min = 48.dp)
                                .selectable(selected = selected, role = Role.RadioButton) { folder = index },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                path,
                                fontFamily = MonoFamily,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                                color = if (selected) t.onAccentContainer else t.text,
                                modifier = Modifier
                                    .widthIn(max = 220.dp)
                                    .background(if (selected) t.accentContainer else Color.Transparent, RoundedCornerShape(18.dp))
                                    .border(1.dp, if (selected) Color.Transparent else t.hairlineStrong, RoundedCornerShape(18.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
            PrimaryButton(
                stringResource(R.string.terminal_new_start_button),
                { onStart(null, folders.getOrNull(folder)) },
                Modifier.fillMaxWidth().padding(top = 6.dp),
                icon = R.drawable.sym_terminal,
            )
        }
    }
}

@Composable
fun TerminalRenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    DeskDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.terminal_rename),
        text = {
            DeskTextField(
                value = text,
                onValueChange = { text = it.take(60) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = stringResource(R.string.terminal_rename_hint),
            )
        },
        confirm = DialogAction(stringResource(android.R.string.ok)) { onConfirm(text) },
        dismiss = DialogAction(stringResource(android.R.string.cancel)) { onDismiss() },
    )
}

/** "End this session?": the shell and anything running in it stop on the computer. */
@Composable
fun EndSessionDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    DeskDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.terminal_close_title),
        text = { Text(stringResource(R.string.terminal_close_message)) },
        confirm = DialogAction(stringResource(R.string.terminal_close_confirm), destructive = true) { onConfirm() },
        dismiss = DialogAction(stringResource(android.R.string.cancel)) { onDismiss() },
    )
}
