/*
 * SPDX-FileCopyrightText: 2026 Tanish Ranjan <tanishranjan4@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.extensions.safeDrawingBottomPadding
import org.desklink.mobile.ui.about.AboutData
import org.desklink.mobile.ui.about.AboutPerson
import org.desklink.mobile.ui.compose.DeskLinkShape
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.DeskLinkType
import org.desklink.mobile.ui.compose.DeskTextButton
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.components.DeskCard
import org.desklink.mobile.ui.compose.components.DeskLinkThemePreviews
import org.desklink.mobile.ui.compose.components.GroupRow
import org.desklink.mobile.ui.compose.components.RowGroup
import org.desklink.mobile.ui.compose.components.SectionLabel
import org.desklink.mobile.ui.compose.tokens

/**
 * About: the app, what's new, help, links, then credits. derpx06 made DeskLink; the KDE Connect authors are
 * credited under "Based on KDE Connect", never as its makers, and DeskLink says it is not made or endorsed by KDE.
 */
@Composable
fun AboutScreen(
    aboutData: AboutData,
    onOpenUrl: (String) -> Unit,
    onCopyDetails: () -> Unit,
    onLicensesClicked: () -> Unit,
    onEasterEggTriggered: () -> Unit = {},
    /** Plays the first-run tour again; null hides the row. */
    onReplayTour: (() -> Unit)? = null,
    whatsNewOpen: Boolean = false,
    /** The Updates controls (a section here and nowhere else); null hides them. */
    updates: (@Composable () -> Unit)? = null,
) {
    val bottomPadding = safeDrawingBottomPadding()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding.calculateBottomPadding() + 24.dp),
    ) {
        item { AppCard(aboutData, onOpenUrl, onEasterEggTriggered) }
        item { WhatsNew(aboutData.versionName.substringBeforeLast('.'), whatsNewOpen) }
        updates?.let { content ->
            item { SectionLabel(stringResource(R.string.update_section)) }
            item { content() }
        }
        item {
            RowGroup {
                aboutData.bugURL?.let { url ->
                    GroupRow(stringResource(R.string.about_report_problem), icon = R.drawable.ic_baseline_bug_report_24, subtitle = stringResource(R.string.about_report_problem_summary), onClick = { onOpenUrl(url) }) { OpenIcon() }
                }
                GroupRow(stringResource(R.string.about_copy_details), icon = R.drawable.sym_content_paste, subtitle = stringResource(R.string.about_copy_details_summary), onClick = onCopyDetails)
            }
        }
        item {
            RowGroup {
                aboutData.sourceCodeURL?.let { url ->
                    GroupRow(stringResource(R.string.source_code), icon = R.drawable.ic_baseline_code_24, subtitle = stringResource(R.string.about_source_code_summary), onClick = { onOpenUrl(url) }) { OpenIcon() }
                }
                aboutData.privacyURL?.let { url ->
                    GroupRow(stringResource(R.string.privacy_policy), icon = R.drawable.sym_encrypted, subtitle = stringResource(R.string.privacy_policy_summary), onClick = { onOpenUrl(url) }) { OpenIcon() }
                }
                GroupRow(stringResource(R.string.licenses), icon = R.drawable.ic_baseline_gavel_24, subtitle = stringResource(R.string.about_licenses_summary), onClick = onLicensesClicked, chevron = true)
                onReplayTour?.let { replay ->
                    GroupRow(stringResource(R.string.about_welcome_tour), icon = R.drawable.sym_restart_alt, subtitle = stringResource(R.string.about_welcome_tour_summary), onClick = replay, chevron = true)
                }
            }
        }
        item { SectionLabel(stringResource(R.string.about_credits)) }
        item {
            RowGroup {
                val url = aboutData.developerURL
                GroupRow(aboutData.developer, icon = R.drawable.sym_person, subtitle = stringResource(R.string.about_developer_role), onClick = url?.let { { onOpenUrl(it) } }) {
                    if (url != null) OpenIcon()
                }
            }
        }
        if (aboutData.upstreamAuthors.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.about_based_on)) }
            item { UpstreamCard(aboutData.upstreamAuthors) }
        }
    }
}

@Composable
private fun AppCard(aboutData: AboutData, onOpenUrl: (String) -> Unit, onEasterEggTriggered: () -> Unit) {
    val t = tokens
    DeskCard(modifier = Modifier.fillMaxWidth(), shape = DeskLinkShape.group) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Three quick taps on the mark open the easter egg: a gesture, not a button, so TalkBack has nothing to announce.
            var taps by remember { mutableStateOf(listOf<Long>()) }
            Image(
                painter = painterResource(aboutData.icon),
                contentDescription = null,
                modifier = Modifier.size(72.dp).pointerInput(Unit) {
                    detectTapGestures {
                        val now = System.currentTimeMillis()
                        taps = (taps + now).filter { now - it <= 600 }
                        if (taps.size >= 3) { taps = emptyList(); onEasterEggTriggered() }
                    }
                },
            )
            Text(aboutData.name, style = DeskLinkType.screenTitle, color = t.text, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
            Text(stringResource(R.string.about_tagline), fontSize = 15.sp, lineHeight = 20.sp, color = t.textDim, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.version, aboutData.versionName) + " · " + stringResource(R.string.about_build, aboutData.build),
                fontSize = 13.sp, lineHeight = 18.sp, fontFamily = MonoFamily, color = t.textDim, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
            aboutData.developerURL?.let { url ->
                DeskTextButton(stringResource(R.string.about_made_by, aboutData.developer), onClick = { onOpenUrl(url) }, icon = R.drawable.sym_open_in_new)
            }
        }
    }
}

/** "What's new in <version>": one row that opens a short list in plain words. */
@Composable
private fun WhatsNew(version: String, startOpen: Boolean) {
    val t = tokens
    var open by rememberSaveable { mutableStateOf(startOpen) }
    val toggle = { open = !open }
    RowGroup {
        GroupRow(
            stringResource(R.string.about_whats_new, version),
            // TalkBack reads "expanded" or "collapsed" from these.
            modifier = Modifier.semantics { if (open) collapse { toggle(); true } else expand { toggle(); true } },
            icon = R.drawable.sym_description,
            subtitle = if (open) null else stringResource(R.string.about_whats_new_show),
            onClick = toggle,
        ) {
            Icon(painterResource(if (open) R.drawable.sym_expand_less else R.drawable.sym_expand_more), contentDescription = null, tint = t.textDim, modifier = Modifier.size(22.dp))
        }
        if (open) {
            Column(Modifier.fillMaxWidth().background(t.surface).padding(start = 52.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                stringArrayResource(R.array.about_whats_new_items).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("•", fontSize = 15.sp, lineHeight = 21.sp, color = t.accentText)
                        Text(line, fontSize = 15.sp, lineHeight = 21.sp, color = t.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpstreamCard(authors: List<AboutPerson>) {
    val t = tokens
    DeskCard(modifier = Modifier.fillMaxWidth(), shape = DeskLinkShape.group) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.about_based_on_body), fontSize = 15.sp, lineHeight = 21.sp, color = t.text)
            authors.forEach { author ->
                Column(Modifier.fillMaxWidth()) {
                    Text(author.name, fontSize = 15.sp, lineHeight = 20.sp, color = t.text)
                    author.task?.let { Text(stringResource(it), fontSize = 13.sp, lineHeight = 18.sp, color = t.textDim) }
                }
            }
            Text(stringResource(R.string.everyone_else), fontSize = 13.sp, lineHeight = 18.sp, color = t.textDim)
            Text(stringResource(R.string.about_not_kde), fontSize = 13.sp, lineHeight = 18.sp, color = t.textDim)
        }
    }
}

/** The row opens a web page outside the app. */
@Composable
private fun RowScope.OpenIcon() {
    Icon(painterResource(R.drawable.sym_open_in_new), contentDescription = null, tint = tokens.textDim, modifier = Modifier.size(20.dp))
}

@DeskLinkThemePreviews
@Composable
private fun AboutScreenPreview() {
    val sample = AboutData(
        name = "DeskLink",
        icon = R.drawable.desklink_mark,
        versionName = "0.3.0",
        build = "20300 · debug",
        developer = "derpx06",
        developerURL = "https://github.com/derpx06",
        bugURL = "https://github.com/derpx06/desklink-mobile/issues",
        sourceCodeURL = "https://github.com/derpx06/desklink-mobile",
        upstreamAuthors = listOf(AboutPerson("KDE Connect author", R.string.developer)),
    )
    DeskLinkTheme(context = LocalContext.current) {
        Surface { AboutScreen(sample, onOpenUrl = {}, onCopyDetails = {}, onLicensesClicked = {}) }
    }
}
