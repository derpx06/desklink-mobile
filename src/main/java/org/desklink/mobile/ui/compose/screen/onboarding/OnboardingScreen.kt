/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.onboarding

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkType
import org.desklink.mobile.ui.compose.PrimaryButton
import org.desklink.mobile.ui.compose.DeskTextButton
import org.desklink.mobile.ui.compose.components.rememberReducedMotion
import org.desklink.mobile.ui.compose.tokens
import kotlin.math.min

/** What one page of the tour says. */
class TourPage(@StringRes val kicker: Int, @StringRes val title: Int, @StringRes val body: Int)

val TourPages = listOf(
    TourPage(R.string.ob_p1_kicker, R.string.ob_p1_title, R.string.ob_p1_body),
    TourPage(R.string.ob_p2_kicker, R.string.ob_p2_title, R.string.ob_p2_body),
    TourPage(R.string.ob_p3_kicker, R.string.ob_p3_title, R.string.ob_p3_body),
    TourPage(R.string.ob_p4_kicker, R.string.ob_p4_title, R.string.ob_p4_body),
    TourPage(R.string.ob_p5_kicker, R.string.ob_p5_title, R.string.ob_p5_body),
)

/** The tour with its own page memory (kept across a rotation). */
@Composable
fun OnboardingRoute(paired: Boolean, onFinish: () -> Unit, onSkip: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    OnboardingScreen(page, { page = it.coerceIn(0, TourPages.lastIndex) }, paired, onFinish, onSkip)
}

/**
 * The five-page tour: segment bars on top (tap one to jump), Skip, an animated scene card, the page's words, and Back/Next. The last
 * page ends with [onFinish] ("Pair with a Computer", or Done when replayed on a paired phone). Pages change only when the person
 * asks: there is no timer. [onSkip] leaves the tour from any page.
 */
@Composable
fun OnboardingScreen(page: Int, onPage: (Int) -> Unit, paired: Boolean, onFinish: () -> Unit, onSkip: () -> Unit) {
    val t = tokens
    val reduced = rememberReducedMotion()
    val last = page == TourPages.lastIndex
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            val pageLabel = stringResource(R.string.ob_page_of, page + 1, TourPages.size)
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics(mergeDescendants = false) { stateDescription = pageLabel }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TourPages.indices.forEach { i -> SegmentBar(i, filled = i <= page, selected = i == page, reduced = reduced, modifier = Modifier.weight(1f)) { onPage(i) } }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                LogoTile(30.dp, corner = 9.dp, mark = 22.dp)
                Text("DeskLink", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.padding(start = 8.dp).weight(1f))
                // Not on the last page: nothing is left to skip.
                if (!last) DeskTextButton(stringResource(R.string.ob_skip), onSkip, Modifier.padding(end = 0.dp))
            }
            val cardHeight = (LocalConfiguration.current.screenHeightDp * 0.42f).coerceIn(240f, SceneHeight.toFloat()).dp
            val shape = RoundedCornerShape(32.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .clip(shape)
                    .background(t.surface)
                    .border(1.dp, t.hairline, shape)
                    .pointerInput(page, rtl) {
                        var dragged = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragged = 0f },
                            onDragEnd = {
                                val forward = if (rtl) dragged > 80f else dragged < -80f
                                val back = if (rtl) dragged < -80f else dragged > 80f
                                if (forward) onPage(page + 1) else if (back) onPage(page - 1)
                            },
                        ) { _, delta -> dragged += delta }
                    }
                    .clearAndSetSemantics { },
            ) {
                // A fresh scene per page; the 328 x 350 canvas is scaled to the card.
                AnimatedContent(
                    page,
                    transitionSpec = { if (reduced) EnterTransition.None togetherWith ExitTransition.None else fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
                    label = "scene",
                ) { p ->
                    // An illustration, not text to read: it keeps its size when the system font is large (the page's words do not).
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
                        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val s = min(maxWidth.value / SceneWidth, maxHeight.value / SceneHeight).coerceIn(0.5f, 1.15f)
                            OnboardingScene(p, reduced, Modifier.graphicsLayer { scaleX = s; scaleY = s })
                        }
                    }
                }
            }
            val copy = TourPages[page]
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 22.dp, bottom = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(stringResource(copy.kicker), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = t.accentText)
                Text(
                    stringResource(copy.title),
                    style = DeskLinkType.screenTitle,
                    color = t.text,
                    modifier = Modifier.padding(top = 6.dp).semantics { heading() },
                )
                Text(stringResource(copy.body), fontSize = 16.sp, lineHeight = 24.sp, color = t.textDim, modifier = Modifier.padding(top = 10.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 18.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page > 0) {
                val back = stringResource(R.string.ob_back)
                Box(
                    Modifier.size(56.dp).clip(CircleShape).border(1.dp, t.hairlineStrong, CircleShape).clickable(role = Role.Button, onClick = { onPage(page - 1) }).semantics { contentDescription = back },
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.sym_arrow_back), contentDescription = null, tint = t.text, modifier = Modifier.size(22.dp)) }
            }
            if (last) {
                PrimaryButton(
                    stringResource(if (paired) R.string.ob_done else R.string.welcome_pair),
                    onFinish,
                    Modifier.weight(1f),
                    icon = if (paired) null else R.drawable.sym_qr_code_scanner,
                )
            } else {
                PrimaryButton(stringResource(R.string.ob_next), { onPage(page + 1) }, Modifier.weight(1f))
            }
        }
    }
}

/** One of the five bars: 4 dp to look at, 48 dp tall to touch, filled up to the current page. */
@Composable
private fun SegmentBar(index: Int, filled: Boolean, selected: Boolean, reduced: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = tokens
    val fill by animateFloatAsState(if (filled) 1f else 0f, if (reduced) snap() else tween(300), label = "fill")
    val go = stringResource(R.string.ob_go_to_page, index + 1)
    Box(
        modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = go; this.selected = selected },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(t.hairlineStrong)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(t.accent))
        }
    }
}
