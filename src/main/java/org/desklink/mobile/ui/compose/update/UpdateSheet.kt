/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskTextButton
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.PrimaryButton
import org.desklink.mobile.ui.compose.SecondaryButton
import org.desklink.mobile.ui.compose.tokens
import org.desklink.mobile.update.InstallSource

/**
 * The sheet's content (docs/UPDATES.md 6.3, owner-approved mock v4): picture, title, version chips, the first three notes, the
 * install source and ONE primary action for it, the checksum on GitHub installs, then Not now and Skip. A required update
 * swaps the notes for its sentence and shows the primary action only. The caller scrolls it (large text) inside the sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UpdateSheetContent(model: UpdateSheetModel, actions: UpdateSheetActions, modifier: Modifier = Modifier) {
    val t = tokens
    val title = stringResource(if (model.required) R.string.update_required_title else R.string.update_title, model.version)
    val focus = remember { FocusRequester() }
    Column(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 22.dp).semantics { paneTitle = title },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        UpdateIllustrationCard(warning = model.required)
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                title, fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, color = t.text,
                modifier = Modifier.semantics { heading() },
            )
            VersionChips(model)
        }
        if (model.required) {
            Text(
                model.requiredReason ?: stringResource(R.string.update_required_body, model.requiredMajorMinor),
                fontSize = 15.sp, lineHeight = 21.sp, color = t.text,
                modifier = Modifier.fillMaxWidth().background(t.surface, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            )
        } else if (model.notes.isNotEmpty()) {
            val shown = model.notes.take(3)
            Column(
                Modifier.semantics { collectionInfo = CollectionInfo(rowCount = shown.size, columnCount = 1) },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { shown.forEach { UpdateNoteRow(it) } }
        }
        SourceRow(model)
        PrimaryButton(
            primaryLabel(model.source), actions.onPrimary, icon = R.drawable.sym_arrow_downward,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        if (model.source == InstallSource.GITHUB && model.sha256 != null) VerifyDownload(model.sha256, actions.onCopyChecksum)
        if (model.source == InstallSource.GITHUB) {
            Text(stringResource(R.string.update_signature_note), fontSize = 12.sp, lineHeight = 16.sp, color = t.textDim)
        }
        if (!model.required) {
            // Side by side; under 320 dp, or when a label would wrap mid-word at a large font, one under the other.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(stringResource(R.string.update_not_now), actions.onNotNow, tonal = true, height = 48.dp, modifier = Modifier.weight(1f))
                DeskTextButton(stringResource(R.string.update_skip), actions.onSkip, modifier = Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VersionChips(model: UpdateSheetModel) {
    val t = tokens
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val spoken = stringResource(if (model.beta) R.string.update_versions_spoken_beta else R.string.update_versions_spoken, model.installed, model.version)
    FlowRow(
        Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.update_have, model.installed), fontSize = 13.sp, color = t.text,
            modifier = Modifier.border(1.dp, t.hairlineStrong, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Icon(painterResource(R.drawable.sym_arrow_forward), contentDescription = null, tint = t.textDim, modifier = Modifier.size(18.dp).graphicsLayer { if (rtl) scaleX = -1f })
        Text(
            stringResource(if (model.beta) R.string.update_new_beta else R.string.update_new, model.version), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = t.onAccent,
            modifier = Modifier.background(t.accent, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceRow(model: UpdateSheetModel) {
    val t = tokens
    val (chip, line) = when (model.source) {
        InstallSource.GOOGLE_PLAY -> R.string.update_source_play to stringResource(R.string.update_source_play_line)
        InstallSource.FDROID -> R.string.update_source_fdroid to stringResource(R.string.update_source_fdroid_line)
        InstallSource.GITHUB -> R.string.update_source_github to
            (model.sizeText?.let { stringResource(R.string.update_source_github_line, it) } ?: stringResource(R.string.update_source_github_line_nosize))
        InstallSource.OTHER_STORE -> R.string.update_source_release to stringResource(R.string.update_source_release_line)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(chip), fontSize = 13.sp, color = t.text,
            modifier = Modifier.border(1.dp, t.hairlineStrong, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Text(line, fontSize = 13.sp, lineHeight = 17.sp, color = t.textDim)
    }
}

@Composable
internal fun primaryLabel(source: InstallSource): String = stringResource(
    when (source) {
        InstallSource.GOOGLE_PLAY -> R.string.update_btn_play
        InstallSource.GITHUB -> R.string.update_btn_download
        InstallSource.FDROID -> R.string.update_btn_fdroid
        InstallSource.OTHER_STORE -> R.string.update_btn_release
    },
)

/** "Verify the download": a disclosure holding the full SHA-256 and a Copy button. */
@Composable
private fun VerifyDownload(sha: String, onCopy: (String) -> Unit) {
    val t = tokens
    var open by rememberSaveable { mutableStateOf(false) }
    val toggle = { open = !open }
    Column(Modifier.fillMaxWidth().background(t.surface, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { if (open) collapse { toggle(); true } else expand { toggle(); true } }
                .clickable(onClick = toggle).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.update_verify), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.accentText, modifier = Modifier.weight(1f))
            Icon(painterResource(if (open) R.drawable.sym_expand_less else R.drawable.sym_expand_more), contentDescription = null, tint = t.textDim, modifier = Modifier.size(22.dp))
        }
        if (open) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.update_checksum, sha), fontFamily = MonoFamily, fontSize = 12.sp, lineHeight = 16.sp, color = t.text, textAlign = TextAlign.Start, modifier = Modifier.weight(1f))
                DeskTextButton(stringResource(R.string.update_copy), { onCopy(sha) })
            }
        }
    }
}
