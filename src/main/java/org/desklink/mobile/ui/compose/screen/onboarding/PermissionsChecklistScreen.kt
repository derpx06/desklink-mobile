/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.onboarding

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkType
import org.desklink.mobile.ui.compose.PrimaryButton
import org.desklink.mobile.ui.compose.components.ChecklistRow
import org.desklink.mobile.ui.compose.components.ChecklistState
import org.desklink.mobile.ui.compose.screen.settings.PermissionRow
import org.desklink.mobile.ui.compose.screen.settings.permissionRows
import org.desklink.mobile.ui.compose.screen.settings.rememberPermissionAsker
import org.desklink.mobile.ui.compose.screen.settings.rememberResumeTick
import org.desklink.mobile.ui.compose.tokens

/** The rows this screen lists: what the app really asks for, from the same list as Settings, minus what nothing the person turned on uses. */
internal fun checklistRows(rows: List<PermissionRow>): List<PermissionRow> = rows.filter { it.relevant }

/** Whether the screen has anything to offer after pairing: a listed permission that is not granted yet. */
internal fun hasChecklistWork(rows: List<PermissionRow>): Boolean = checklistRows(rows).any { !it.granted }

/** Read once when a pairing completes, to decide whether to show the checklist at all. */
internal fun needsPermissionsChecklist(context: Context, deskName: String): Boolean =
    hasChecklistWork(permissionRows(context, deskName, request = { }))

/**
 * After pairing: the permissions DeskLink can use, each with its own button. Nothing is asked until a button is tapped, nothing is
 * required, and the same list stays in Settings under Advanced. It follows the system's answers when the person comes back.
 */
@Composable
fun PermissionsChecklistRoute(deskName: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val asker = rememberPermissionAsker()
    val rows = remember(tick, asker.asked) { permissionRows(context, deskName, asker.ask) }
    PermissionsChecklistScreen(deskName, rows, onDone)
}

@Composable
internal fun PermissionsChecklistScreen(deskName: String, rows: List<PermissionRow>, onDone: () -> Unit) {
    val t = tokens
    val shown = checklistRows(rows)
    val allDone = shown.all { it.granted }
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
                Row(
                    Modifier
                        .background(t.mint.copy(alpha = 0.16f).compositeOver(t.surface), RoundedCornerShape(16.dp))
                        .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(painterResource(R.drawable.sym_check_circle), contentDescription = null, tint = t.mintText, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.pair_paired_with, deskName), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = t.mintText)
                }
                Text(
                    stringResource(R.string.perm_title),
                    style = DeskLinkType.screenTitle.copy(fontSize = 32.sp, lineHeight = 38.sp),
                    color = t.text,
                    modifier = Modifier.padding(top = 16.dp).semantics { heading() },
                )
                Text(stringResource(R.string.perm_body), fontSize = 15.sp, lineHeight = 22.sp, color = t.textDim, modifier = Modifier.padding(top = 8.dp))
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp).clip(RoundedCornerShape(24.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                shown.forEach { row ->
                    ChecklistRow(
                        icon = row.icon,
                        title = row.title,
                        description = row.summary,
                        state = if (row.granted) ChecklistState.Done else ChecklistState.Todo,
                        actionLabel = stringResource(if (row.unknown) R.string.settings_open else R.string.settings_allow),
                        doneLabel = stringResource(R.string.perm_on),
                        onClick = if (row.granted) null else row.allow,
                    )
                }
            }
        }
        PrimaryButton(
            stringResource(if (allDone) R.string.perm_continue else R.string.perm_skip),
            onDone,
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 18.dp, top = 8.dp),
        )
    }
}
