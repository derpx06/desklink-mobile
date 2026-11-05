/*
 * SPDX-FileCopyrightText: 2026 Tanish Ranjan <tanishranjan4@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.about

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.desklink.mobile.extensions.safeDrawingBottomPadding
import org.desklink.mobile.ui.about.AboutData
import org.desklink.mobile.ui.about.AboutPerson
import org.desklink.mobile.ui.compose.DeskLinkShape
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.DeskLinkType
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.components.DeskCard
import org.desklink.mobile.ui.compose.components.GroupRow
import org.desklink.mobile.ui.compose.components.RowGroup
import org.desklink.mobile.ui.compose.tokens
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import org.desklink.mobile.ui.compose.components.DeskLinkThemePreviews
import org.desklink.mobile.R

@Composable
fun AboutScreen(
    aboutData: AboutData,
    onEasterEggTriggered: () -> Unit,
    onReportBugClicked: () -> Unit,
    onDonateClicked: () -> Unit,
    onSourceCodeClicked: () -> Unit,
    onLicensesClicked: () -> Unit,
    /** Plays the first-run tour again; null hides the button. */
    onReplayTour: (() -> Unit)? = null,
) {
    val bottomPadding = safeDrawingBottomPadding()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = bottomPadding.calculateBottomPadding() + 16.dp
        )
    ) {
        item {
            AppInfoCard(
                aboutData = aboutData,
                onEasterEggTriggered = onEasterEggTriggered
            )
        }

        item {
            ActionButtons(
                aboutData = aboutData,
                onReportBugClicked = onReportBugClicked,
                onDonateClicked = onDonateClicked,
                onSourceCodeClicked = onSourceCodeClicked,
                onLicensesClicked = onLicensesClicked
            )
        }

        onReplayTour?.let { replay ->
            item {
                org.desklink.mobile.ui.compose.SecondaryButton(stringResource(R.string.about_welcome_tour), replay, Modifier.fillMaxWidth())
            }
        }

        item {
            AuthorsCard(aboutData = aboutData)
        }
    }
}

@Composable
private fun AppInfoCard(
    aboutData: AboutData,
    onEasterEggTriggered: () -> Unit
) {
    var tapCount by remember { mutableIntStateOf(0) }
    var firstTapMillis by remember { mutableStateOf<Long?>(null) }
    val t = tokens

    DeskCard(
        modifier = Modifier.fillMaxWidth(),
        shape = DeskLinkShape.group,
        onClick = {
            val currentMillis = System.currentTimeMillis()
            if (firstTapMillis == null) {
                firstTapMillis = currentMillis
            }

            tapCount++

            if (tapCount == 3) {
                if (currentMillis - firstTapMillis!! <= 500) {
                    onEasterEggTriggered()
                }
                tapCount = 0
                firstTapMillis = null
            } else if (currentMillis - firstTapMillis!! > 500) {
                tapCount = 1
                firstTapMillis = currentMillis
            }
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(id = aboutData.icon),
                contentDescription = null,
                modifier = Modifier.size(56.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = aboutData.name,
                    style = DeskLinkType.screenTitle,
                    color = t.text,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(id = R.string.version, aboutData.versionName),
                    fontSize = 14.sp,
                    fontFamily = MonoFamily,
                    color = t.textDim,
                )
            }
        }
    }
}

/** Report a bug, Donate, Source code, Licenses: rows on one rounded group, like Settings. */
@Composable
private fun ActionButtons(
    aboutData: AboutData,
    onReportBugClicked: () -> Unit,
    onDonateClicked: () -> Unit,
    onSourceCodeClicked: () -> Unit,
    onLicensesClicked: () -> Unit
) {
    RowGroup {
        if (aboutData.bugURL != null) {
            GroupRow(stringResource(R.string.report_bug), icon = R.drawable.ic_baseline_bug_report_24, onClick = onReportBugClicked, chevron = true)
        }
        if (aboutData.donateURL != null) {
            GroupRow(stringResource(R.string.donate), icon = R.drawable.ic_baseline_attach_money_24, onClick = onDonateClicked, chevron = true)
        }
        if (aboutData.sourceCodeURL != null) {
            GroupRow(stringResource(R.string.source_code), icon = R.drawable.ic_baseline_code_24, onClick = onSourceCodeClicked, chevron = true)
        }
        GroupRow(stringResource(R.string.licenses), icon = R.drawable.ic_baseline_gavel_24, onClick = onLicensesClicked, chevron = true)
    }
}

@Composable
private fun AuthorsCard(aboutData: AboutData) {
    val t = tokens
    DeskCard(modifier = Modifier.fillMaxWidth(), shape = DeskLinkShape.group) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(id = R.string.authors),
                style = DeskLinkType.cardTitle,
                color = t.text,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(modifier = Modifier.height(8.dp))

            aboutData.authors.forEach { author ->
                AuthorItemRow(author = author)
            }

            aboutData.authorsFooterText?.let { footerResId ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(id = footerResId),
                    fontSize = 14.sp,
                    color = t.textDim,
                )
            }
        }
    }
}

@Composable
private fun AuthorItemRow(author: AboutPerson) {
    val t = tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = author.name,
            fontSize = 16.sp,
            color = t.text,
        )
        if (author.task != null) {
            Text(
                text = stringResource(id = author.task),
                fontSize = 14.sp,
                color = t.textDim,
            )
        }
    }
}

@DeskLinkThemePreviews
@Composable
private fun AboutScreenPreview() {
    val sampleAboutData = AboutData(
        name = "DeskLink",
        icon = R.drawable.desklink_mark,
        versionName = "1.27.0",
        sourceCodeURL = "https://github.com/derpx06/desklink-mobile",
        authorsFooterText = R.string.everyone_else
    ).apply {
        authors += AboutPerson("Albert Vaca Cintora", R.string.maintainer_and_developer)
        authors += AboutPerson("Aleix Pol", R.string.developer)
    }

    DeskLinkTheme(context = LocalContext.current) {
        Surface {
            AboutScreen(
                aboutData = sampleAboutData,
                onEasterEggTriggered = {},
                onReportBugClicked = {},
                onDonateClicked = {},
                onSourceCodeClicked = {},
                onLicensesClicked = {}
            )
        }
    }
}
