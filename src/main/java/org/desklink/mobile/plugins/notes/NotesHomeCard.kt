/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.components.IconCircle
import org.desklink.mobile.ui.compose.tokens

/** Home's Notes card: pinned and latest notes (3 rows), one tap to any, and New note. Local-first: always shown. */
@Composable
fun NotesHomeCard(onOpenList: () -> Unit, onOpenNote: (String) -> Unit, onNew: () -> Unit) {
    val t = tokens
    val context = LocalContext.current
    val revision by NotesStore.revision.collectAsState()
    val notes = remember(revision) { NotesStore.notes(context).take(3) }
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(t.surface).border(1.dp, t.hairline, shape)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onOpenList).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconCircle(R.drawable.sym_edit_note, 36.dp, 20.dp)
            Text(stringResource(R.string.notes_title), fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).semantics { heading() })
            IconButton(onClick = onNew, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.sym_add), contentDescription = stringResource(R.string.notes_new), tint = t.accentText)
            }
        }
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.hairline))
            Text(
                stringResource(R.string.notes_home_empty),
                fontSize = 13.sp, color = t.textDim,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onNew).padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        notes.forEach { note ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.hairline))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onOpenNote(note.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(note.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val preview = note.body.lineSequence().map(String::trim).filter(String::isNotEmpty).drop(if (note.title.isBlank()) 1 else 0).firstOrNull()
                    preview?.let { Text(it, fontSize = 12.sp, color = t.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (note.pinned) Icon(painterResource(R.drawable.sym_keep_filled), contentDescription = stringResource(R.string.notes_pinned), tint = t.accentText, modifier = Modifier.size(16.dp))
            }
        }
    }
}
