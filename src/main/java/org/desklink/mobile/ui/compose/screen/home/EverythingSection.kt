/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.components.IconTile
import org.desklink.mobile.ui.compose.components.RowGroup
import org.desklink.mobile.ui.compose.components.SectionHeader
import org.desklink.mobile.ui.compose.components.SectionLabel
import org.desklink.mobile.ui.compose.tokens

/**
 * The end of Home: every feature for this computer, grouped, each with where it stands and the one thing that starts it.
 * A row is one target: [onRow] gets the feature and its status ([Everything.action] says what to do with it).
 */
@Composable
internal fun EverythingSection(state: HomeState, onRow: (Feature, FeatureStatus) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(stringResource(R.string.everything_title))
        Text(
            stringResource(R.string.everything_subtitle, state.name),
            fontSize = 13.sp, lineHeight = 18.sp, color = tokens.textDim, modifier = Modifier.padding(horizontal = 6.dp),
        )
        Everything.grouped.forEach { (group, features) ->
            SectionLabel(stringResource(group.title), Modifier.padding(top = 8.dp))
            RowGroup {
                features.forEach { f -> FeatureRow(f, Everything.status(f, state), state, onRow) }
            }
        }
    }
}

@Composable
private fun FeatureRow(f: Feature, status: FeatureStatus, state: HomeState, onRow: (Feature, FeatureStatus) -> Unit) {
    val t = tokens
    val name = stringResource(f.name)
    val line = stringResource(Everything.line(f, status), state.name)
    val label = stringResource(if (f.id == "share-screen" && status == FeatureStatus.Ready && state.live.phoneScreen) R.string.home_stop_sharing else Everything.label(f, status))
    val word = stringResource(Everything.statusWord(status))
    val off = status is FeatureStatus.Off
    val dot = when (status) {
        FeatureStatus.Ready -> t.mint
        is FeatureStatus.Step -> t.amber
        is FeatureStatus.Off -> t.textFaint
    }
    val scale = LocalDensity.current.fontScale
    // At a large font the button goes under the words and the icon goes, so the name keeps the row's width.
    val stacked = scale > 1.3f
    val run = { onRow(f, status) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(t.surface)
            .clickable(role = Role.Button, onClickLabel = label, onClick = run)
            // One stop for TalkBack: "Glide. One step needed. arch-desk is asking to use Glide", then "double-tap to Allow".
            .clearAndSetSemantics {
                contentDescription = "$name. $word. $line"
                role = Role.Button
                onClick(label) { run(); true }
            }
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (scale <= 1.4f) {
                if (off) IconTile(f.icon, size = 40.dp, container = t.fill, content = t.textDim) else IconTile(f.icon, size = 40.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, fontSize = 16.sp, lineHeight = 21.sp, color = if (off) t.textDim else t.text)
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Centred on the first line at any font size (17 sp line, 6 dp dot).
                    val top = with(LocalDensity.current) { 17.sp.toDp() / 2 - 3.dp }
                    Box(Modifier.padding(top = top).size(6.dp).background(dot, CircleShape))
                    Text(line, fontSize = 13.sp, lineHeight = 17.sp, color = if (status is FeatureStatus.Step) t.text else t.textDim, modifier = Modifier.weight(1f))
                }
            }
            if (!stacked) Pill(label, status)
        }
        if (stacked) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { Pill(label, status) }
    }
}

/** What the row's tap does, drawn as a pill: tonal to open, filled for the one step, outlined to explain. Not a target of its own. */
@Composable
private fun Pill(label: String, status: FeatureStatus) {
    val t = tokens
    val shape = RoundedCornerShape(17.dp)
    val (background, ink, edge) = when (status) {
        FeatureStatus.Ready -> Triple(t.accentContainer, t.onAccentContainer, Color.Transparent)
        is FeatureStatus.Step -> Triple(t.accent, t.onAccent, Color.Transparent)
        is FeatureStatus.Off -> Triple(Color.Transparent, t.text, t.hairlineStrong)
    }
    Box(
        Modifier.heightIn(min = 34.dp).widthIn(min = 64.dp).clip(shape).background(background).border(1.dp, edge, shape).padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = ink)
    }
}
