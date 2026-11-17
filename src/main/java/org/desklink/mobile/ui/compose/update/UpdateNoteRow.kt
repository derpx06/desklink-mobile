/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.tokens
import org.desklink.mobile.update.Note

/** One "what's new" row: a 38 dp glyph tile, a bold line and one grey line. Plain text only: never markup from the manifest. */
@Composable
internal fun UpdateNoteRow(note: Note, modifier: Modifier = Modifier) {
    val t = tokens
    Row(
        modifier.fillMaxWidth().background(t.surface, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 9.dp)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(note.title, note.detail).joinToString(". ") },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp).background(t.accentContainer, RoundedCornerShape(12.dp)).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            Icon(painterResource(glyphRes(note.glyph)), contentDescription = null, tint = t.onAccentContainer, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(note.title, fontSize = 14.5.sp, lineHeight = 19.sp, fontWeight = FontWeight(650), color = t.text)
            note.detail?.let { Text(it, fontSize = 12.5.sp, lineHeight = 17.sp, color = t.textDim) }
        }
    }
}

/** The glyph names a manifest may use (`Component.GLYPHS`) to the app's own icons; anything else is the star. */
internal fun glyphRes(glyph: String): Int = when (glyph) {
    "refresh" -> R.drawable.sym_refresh
    "swap" -> R.drawable.sym_swap_vert
    "check" -> R.drawable.sym_check_circle
    "files" -> R.drawable.sym_folder
    "terminal" -> R.drawable.sym_terminal
    "speed" -> R.drawable.sym_speed
    "shield" -> R.drawable.sym_shield
    else -> R.drawable.sym_star
}
