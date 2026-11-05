/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkType
import org.desklink.mobile.ui.compose.components.rememberReducedMotion
import org.desklink.mobile.ui.compose.tokens

/** The app mark on its navy tile (the same tile in both themes: the mark is drawn for it). */
@Composable
fun LogoTile(size: Dp, modifier: Modifier = Modifier, corner: Dp = size * 0.29f, mark: Dp = size * 0.6f) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.size(size).background(Color(0xFF14213D), shape).border(1.dp, tokens.hairline, shape), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.desklink_mark), contentDescription = null, modifier = Modifier.size(mark))
    }
}

/**
 * Shown over the app on a cold start while the paired computer is being looked for (see [FirstRun.showSplash]: it is gone the moment
 * the computer answers, and after a second whatever happens). The spinner is real: it stands for the search that is running.
 */
@Composable
fun SplashScreen(computerName: String) {
    val t = tokens
    val reduced = rememberReducedMotion()
    val looking = stringResource(R.string.splash_looking_for, computerName)
    Box(Modifier.fillMaxSize().pointerInput(Unit) { }.background(t.bg).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.padding(horizontal = 32.dp)) {
            LogoTile(96.dp, corner = 28.dp, mark = 58.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("DeskLink", style = DeskLinkType.splash.copy(fontWeight = FontWeight.Medium), color = t.text)
                Text(stringResource(R.string.splash_tagline), fontSize = 14.sp, lineHeight = 20.sp, color = t.textDim, textAlign = TextAlign.Center)
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(start = 32.dp, end = 32.dp, bottom = 56.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // "Remove animations": a still ring instead of a spinning one.
            if (reduced) CircularProgressIndicator(progress = { 0.28f }, modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = t.accent, trackColor = t.hairlineStrong)
            else CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = t.accent, trackColor = t.hairlineStrong)
            Text(looking, fontSize = 13.sp, lineHeight = 18.sp, color = t.textDim, textAlign = TextAlign.Center)
        }
    }
}
