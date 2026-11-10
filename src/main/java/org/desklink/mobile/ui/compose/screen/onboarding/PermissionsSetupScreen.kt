/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.onboarding

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkType
import org.desklink.mobile.ui.compose.DeskTextButton
import org.desklink.mobile.ui.compose.PrimaryButton
import org.desklink.mobile.ui.compose.components.ChecklistRow
import org.desklink.mobile.ui.compose.components.ChecklistState
import org.desklink.mobile.ui.compose.components.DeskDialog
import org.desklink.mobile.ui.compose.components.DialogAction
import org.desklink.mobile.ui.compose.components.GroupRow
import org.desklink.mobile.ui.compose.components.RowGroup
import org.desklink.mobile.ui.compose.components.SectionLabel
import org.desklink.mobile.ui.compose.screen.settings.PermissionAsker
import org.desklink.mobile.ui.compose.screen.settings.PermissionNeeds
import org.desklink.mobile.ui.compose.screen.settings.PermissionRow
import org.desklink.mobile.ui.compose.screen.settings.actionLabel
import org.desklink.mobile.ui.compose.screen.settings.permissionRows
import org.desklink.mobile.ui.compose.screen.settings.rememberPermissionAsker
import org.desklink.mobile.ui.compose.screen.settings.rememberResumeTick
import org.desklink.mobile.ui.compose.tokens

/** Rows of the first group: what pairing and a link that survives the screen going off need. The rest is per feature. */
internal val SetupConnectIds = setOf("notifications", "camera", "battery", "oem")

/** The setup's two groups (to pair and stay connected, for the features), each in [permissionRows] order. */
internal fun setupGroups(rows: List<PermissionRow>): Pair<List<PermissionRow>, List<PermissionRow>> =
    checklistRows(rows).partition { it.id in SetupConnectIds }

/** "Allow all": every runtime permission still missing, in one system request. Those Android will not ask for again are left to their row's "Open settings". */
internal fun allowAllRequest(rows: List<PermissionRow>): List<String> =
    checklistRows(rows).filter { !it.granted && !it.blocked }.flatMap { it.permissions }.distinct()

/** Then the special accesses, one system page each, in list order (notification access, battery, the OEM's background setting). */
internal fun specialSteps(rows: List<PermissionRow>): List<String> =
    checklistRows(rows).filter { it.permissions.isEmpty() && !it.granted }.map { it.id }

/** The step to show now: the first of [steps] whose row is still not granted (one granted meanwhile is passed over). */
internal fun currentStep(steps: List<String>, rows: List<PermissionRow>): PermissionRow? =
    steps.firstNotNullOfOrNull { id -> rows.firstOrNull { it.id == id && !it.granted } }

/** What a special-access step says before its system page opens. */
@StringRes
internal fun stepExplanation(id: String): Int? = when (id) {
    "notification_access" -> R.string.setup_step_notification_access
    "battery" -> R.string.setup_step_battery
    "oem" -> R.string.setup_step_oem
    else -> null
}

/** Accesses asked in context, listed so nothing comes as a surprise: none of them has a button here. */
internal class LaterItem(@DrawableRes val icon: Int, @StringRes val title: Int, @StringRes val summary: Int)

internal val SetupLater = listOf(
    LaterItem(R.drawable.sym_mobile_screen_share, R.string.setup_later_screen, R.string.setup_later_screen_summary),
    LaterItem(R.drawable.sym_accessibility_new, R.string.settings_perm_accessibility, R.string.setup_later_accessibility_summary),
    LaterItem(R.drawable.sym_notifications, R.string.setup_later_dnd, R.string.setup_later_dnd_summary),
    LaterItem(R.drawable.sym_public, R.string.setup_later_location, R.string.setup_later_location_summary),
)

/**
 * Before the first pairing: everything DeskLink's features use, with "Allow all" (one system request for the runtime permissions,
 * then each special access with a line of explanation) and an Allow per row. Nothing is asked without a tap and nothing blocks
 * pairing: [onDone] ("Skip for now" or "Continue") goes on to the scan screen. The rows follow the system when the person comes back.
 */
@Composable
fun PermissionsSetupRoute(onDone: () -> Unit) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val asker = rememberPermissionAsker()
    val desk = stringResource(R.string.settings_your_computer)
    val rows = remember(tick, asker.asked) { permissionRows(context, desk, asker.ask, PermissionNeeds.setup) }
    PermissionsSetupScreen(rows, rememberAllowAll(rows, asker), onDone)
}

/**
 * What "Allow all" runs: one system request for every missing runtime permission, then, once it is answered, each special access
 * in turn behind a dialog saying what the next system page is for ("Open" or "Not now"; back ends the walk). Draws that dialog.
 */
@Composable
internal fun rememberAllowAll(rows: List<PermissionRow>, asker: PermissionAsker): () -> Unit {
    // The special accesses still to walk through, and whether the system request is still on screen.
    var steps by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var asking by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(asker.asked) { if (asker.asked > 0) asking = false }
    val step = if (asking) null else currentStep(steps, rows)
    if (step != null) {
        val rest = steps.dropWhile { it != step.id }.drop(1)
        DeskDialog(
            onDismissRequest = { steps = emptyList() },
            title = step.title,
            text = { Text(stringResource(stepExplanation(step.id) ?: R.string.perm_body)) },
            confirm = DialogAction(stringResource(R.string.settings_open)) { steps = rest; step.allow() },
            dismiss = DialogAction(stringResource(R.string.setup_not_now)) { steps = rest },
        )
    }
    return {
        steps = specialSteps(rows)
        val request = allowAllRequest(rows)
        if (request.isNotEmpty()) {
            asking = true
            asker.ask(request.toTypedArray())
        }
    }
}

@Composable
internal fun PermissionsSetupScreen(rows: List<PermissionRow>, onAllowAll: () -> Unit, onDone: () -> Unit) {
    val t = tokens
    val (connect, features) = setupGroups(rows)
    val allDone = checklistCounted(rows).all { it.granted }
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp)) {
                Text(stringResource(R.string.setup_kicker), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = t.accentText)
                Text(
                    stringResource(R.string.setup_title),
                    style = DeskLinkType.screenTitle.copy(fontSize = 32.sp, lineHeight = 38.sp),
                    color = t.text,
                    modifier = Modifier.padding(top = 6.dp).semantics { heading() },
                )
                Text(stringResource(R.string.setup_body), fontSize = 15.sp, lineHeight = 22.sp, color = t.textDim, modifier = Modifier.padding(top = 8.dp))
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SetupGroup(stringResource(R.string.setup_group_connect), connect)
                SetupGroup(stringResource(R.string.setup_group_features), features)
                SectionLabel(stringResource(R.string.setup_group_later))
                RowGroup {
                    SetupLater.forEach { GroupRow(stringResource(it.title), icon = it.icon, subtitle = stringResource(it.summary)) }
                }
            }
        }
        SetupButtons(allDone, onAllowAll, onDone)
    }
}

/** The foot of the setup and of the checklist: "Allow all" over "Skip for now" while something is missing, then "Continue". */
@Composable
internal fun SetupButtons(allDone: Boolean, onAllowAll: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 10.dp, top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (allDone) {
            PrimaryButton(stringResource(R.string.perm_continue), onDone, Modifier.fillMaxWidth())
        } else {
            PrimaryButton(stringResource(R.string.setup_allow_all), onAllowAll, Modifier.fillMaxWidth())
            DeskTextButton(stringResource(R.string.perm_skip), onDone, Modifier.padding(top = 4.dp))
        }
    }
}

/** Home's "Finish setting up" rows: what is still missing, with what it unlocks; the card shows while [hasChecklistWork] says so. */
internal fun finishSetupRows(rows: List<PermissionRow>): List<PermissionRow> = checklistRows(rows).filter { !it.granted }

/**
 * On Home, until everything a feature can use is allowed: a card that says how many are left, opens to the rows (each with its own
 * Allow, or Open settings once Android stops asking) and "Allow all". [expanded] is the person's choice and is kept.
 */
@Composable
internal fun FinishSetupCard(rows: List<PermissionRow>, expanded: Boolean, onExpanded: (Boolean) -> Unit, onAllowAll: () -> Unit) {
    val t = tokens
    val missing = finishSetupRows(rows)
    val left = checklistCounted(rows).count { !it.granted }
    val shape = org.desklink.mobile.ui.compose.DeskLinkShape.group
    val state = stringResource(if (expanded) R.string.home_setup_expanded else R.string.home_setup_collapsed)
    Column(Modifier.fillMaxWidth().clip(shape).background(t.surface).border(1.dp, t.amber.copy(alpha = 0.5f), shape)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { onExpanded(!expanded) }
                .semantics { stateDescription = state }
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(painterResource(R.drawable.sym_tune), contentDescription = null, tint = t.amber, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.home_setup_title), fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.semantics { heading() })
                Text(pluralStringResource(R.plurals.home_setup_left, left, left), fontSize = 13.sp, lineHeight = 17.sp, color = t.textDim)
            }
            Icon(painterResource(if (expanded) R.drawable.sym_expand_less else R.drawable.sym_expand_more), contentDescription = null, tint = t.textDim, modifier = Modifier.size(22.dp))
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.background(t.bg).padding(top = 2.dp)) {
                missing.forEach { row ->
                    ChecklistRow(
                        icon = row.icon,
                        title = row.title,
                        description = row.summary,
                        state = ChecklistState.Todo,
                        actionLabel = stringResource(row.actionLabel()),
                        doneLabel = stringResource(R.string.perm_on),
                        onClick = row.allow,
                    )
                }
            }
            PrimaryButton(stringResource(R.string.setup_allow_all), onAllowAll, Modifier.fillMaxWidth().padding(16.dp), height = 48.dp)
        }
    }
}

@Composable
private fun SetupGroup(label: String, rows: List<PermissionRow>) {
    if (rows.isEmpty()) return
    SectionLabel(label)
    RowGroup {
        rows.forEach { row ->
            ChecklistRow(
                icon = row.icon,
                title = row.title,
                description = row.summary,
                state = if (row.granted) ChecklistState.Done else ChecklistState.Todo,
                actionLabel = stringResource(row.actionLabel()),
                doneLabel = stringResource(R.string.perm_on),
                onClick = if (row.granted) null else row.allow,
            )
        }
    }
}
