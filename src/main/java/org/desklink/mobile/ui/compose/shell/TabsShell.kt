/* SPDX-License-Identifier: GPL-3.0-or-later */
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package org.desklink.mobile.ui.compose.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.components.CappedFontScale
import org.desklink.mobile.ui.compose.components.Haptic
import org.desklink.mobile.ui.compose.components.rememberDeskHaptics
import org.desklink.mobile.ui.compose.components.rememberReducedMotion
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.tokens

/** Width from which the tabs become a rail on the side and the content a centred column. */
const val WIDE_DP = 600

/** The widest a screen's content gets on a tablet. */
val MaxContentWidth = 840.dp

/** The bar itself, a floating pill of this height. */
val TabBarPillHeight = 68.dp

/** How far above the bar the scrolling content fades out. */
val ScrollFadeHeight = 20.dp

/** The tab changes, as motion: a neighbour slides, a far jump crossfades. Pure, so a test can pin the rule. */
object TabMotion {
    const val CROSSFADE_MS = 180
    const val INDICATOR_MS = 180
    fun slides(from: Int, to: Int) = kotlin.math.abs(from - to) <= 1
}

/** What the bar takes from the bottom of the screen: the pill and the 8 dp above and below it (the navigation bar is extra). */
val TabBarHeight = TabBarPillHeight + 16.dp

@Suppress("ConfigurationScreenWidthHeight") // the one question asked: tab bar or rail
@Composable
fun isWide(): Boolean = LocalConfiguration.current.screenWidthDp >= WIDE_DP

/**
 * Home · Files · Notes · Terminals · Settings. A bottom bar on a phone (a rail on a tablet), swiping
 * between tabs, and a long-press on a tab for its quick action. Each tab's screen is built by [content];
 * its scroll position and back stack survive switching tabs.
 */
@Composable
fun TabsShell(
    nav: TabNav,
    badges: TabBadges,
    onQuickAction: (QuickAction) -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = isWide(),
    content: @Composable (AppTab) -> Unit,
) {
    val tabs = AppTab.entries
    val pager = rememberPagerState(initialPage = nav.selected.ordinal) { tabs.size }
    val reduced = rememberReducedMotion()
    val haptics = rememberDeskHaptics()
    // A jump of more than one tab crossfades instead of sliding through every tab in between (which would build them all).
    val fade = remember { Animatable(1f) }
    // Tapping a tab scrolls the pager; swiping the pager selects the tab.
    LaunchedEffect(nav.selected) {
        val target = nav.selected.ordinal
        if (pager.currentPage != target && !pager.isScrollInProgress) {
            when {
                reduced -> pager.scrollToPage(target)
                TabMotion.slides(pager.currentPage, target) -> pager.animateScrollToPage(target)
                else -> {
                    fade.snapTo(0f)
                    pager.scrollToPage(target)
                    fade.animateTo(1f, tween(TabMotion.CROSSFADE_MS))
                }
            }
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { nav.select(tabs[it]) }
    }
    val bar: @Composable (Modifier) -> Unit = { itemModifier ->
        TabItems(
            nav.selected, badges, vertical = wide, itemModifier,
            onSelect = { tab -> if (tab != nav.selected) haptics.perform(Haptic.TICK); nav.select(tab) },
            onQuickAction = { action -> haptics.perform(Haptic.LONG_PRESS); onQuickAction(action) },
        )
    }
    val pages: @Composable (Modifier) -> Unit = { mod ->
        HorizontalPager(
            state = pager,
            modifier = mod,
            beyondViewportPageCount = 0,
            key = { tabs[it].key },
        ) { index ->
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }, contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = MaxContentWidth).fillMaxSize()) { content(tabs[index]) }
            }
        }
    }
    if (wide) {
        Row(modifier.fillMaxSize().background(tokens.bg)) {
            Column(
                // Scrolls: a phone turned sideways is wide enough for the rail but not tall enough for five tabs.
                Modifier.width(88.dp).fillMaxHeight().background(tokens.surface).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CappedFontScale(1.2f) { bar(Modifier) } }
            pages(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(modifier.fillMaxSize().background(tokens.bg)) {
            // The bar owns the navigation-bar inset, so the screens above it must not pad for it twice.
            // Content that scrolls under the bar fades out into the window instead of being cut by a hard edge.
            val bg = tokens.bg
            pages(
                Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars).drawWithContent {
                    drawContent()
                    val h = ScrollFadeHeight.toPx()
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, bg), startY = size.height - h, endY = size.height), topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                },
            )
            // The design's bar: a pill floating 12 dp from the sides on the window, with a hairline and a soft shadow.
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)) {
                val shape = RoundedCornerShape(30.dp)
                // Five equal slots: a tab is never pushed off the edge by a long label or a large font size.
                BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .height(TabBarPillHeight)
                        .shadow(if (tokens.isDark) 0.dp else 8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.12f))
                        .clip(shape)
                        .background(tokens.surface)
                        .border(1.dp, tokens.hairline, shape),
                ) {
                    val slot = maxWidth / tabs.size
                    Row(Modifier.fillMaxSize().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                        CappedFontScale(if (slot < 72.dp) 1f else 1.2f) { bar(Modifier.width(slot)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabItems(
    selected: AppTab,
    badges: TabBadges,
    vertical: Boolean,
    itemModifier: Modifier,
    onSelect: (AppTab) -> Unit,
    onQuickAction: (QuickAction) -> Unit,
) {
    AppTab.entries.forEach { tab ->
        val modifier = if (vertical) Modifier.fillMaxWidth().heightIn(min = 72.dp) else itemModifier.fillMaxHeight()
        TabItem(tab, tab == selected, badges, modifier, onClick = { onSelect(tab) }, onLongClick = tab.quickAction()?.let { action -> { onQuickAction(action) } })
    }
}

@Composable
private fun TabItem(
    tab: AppTab,
    selected: Boolean,
    badges: TabBadges,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val t = tokens
    val label = stringResource(tab.label)
    val state = tabState(tab, badges)
    val quick = tab.quickAction()?.let { quickLabel(it) }
    val description = if (state != null) "$label, $state" else label
    val reduced = rememberReducedMotion()
    // The selected pill grows out of the icon and the icon and word change colour, over 180 ms (nothing moves with "Remove animations").
    val ms = if (reduced) 0 else TabMotion.INDICATOR_MS
    val pillWidth by animateDpAsState(if (selected) 56.dp else 32.dp, tween(ms), label = "tab-pill")
    val pillColor by animateColorAsState(if (selected) t.accentContainer else Color.Transparent, tween(ms), label = "tab-pill-color")
    val iconColor by animateColorAsState(if (selected) t.onAccentContainer else t.textDim, tween(ms), label = "tab-icon")
    val textColor by animateColorAsState(if (selected) t.text else t.textDim, tween(ms), label = "tab-label")
    Box(
        modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = quick)
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                this.selected = selected
                contentDescription = description
                if (onLongClick != null && quick != null) onLongClick(label = quick) { onLongClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.height(32.dp).width(56.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.height(32.dp).width(pillWidth).clip(RoundedCornerShape(16.dp)).background(pillColor))
                Icon(painterResource(tab.icon), contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
                TabBadge(tab, badges)
            }
            // One weight for both states: a bold selected word is wider than the slot it was measured in.
            Text(label, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, color = textColor, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TabBadge(tab: AppTab, badges: TabBadges) {
    val t = tokens
    when (tab) {
        AppTab.HOME -> Dot(linkColor(badges.link), Modifier.padding(start = 26.dp, bottom = 14.dp))
        AppTab.TERMINALS -> if (badges.terminalsAttention) Dot(t.coral, Modifier.padding(start = 26.dp, bottom = 14.dp))
        AppTab.SETTINGS -> if (badges.settingsAttention) Dot(t.amber, Modifier.padding(start = 26.dp, bottom = 14.dp))
        AppTab.FILES -> if (badges.filesBusy || badges.filesProgress != null) ProgressRing(badges.filesProgress, t.accent, t.hairlineStrong)
        AppTab.NOTES -> Unit
    }
}

@Composable
private fun linkColor(link: DeviceConnection): Color {
    val t = tokens
    return when (link) {
        DeviceConnection.LOCAL, DeviceConnection.AWAY, DeviceConnection.CONNECTED -> t.mint
        DeviceConnection.CONNECTING -> t.accent
        DeviceConnection.UNAVAILABLE -> t.amber
        DeviceConnection.OFFLINE -> t.textFaint
    }
}

@Composable
private fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 9.dp) {
    Box(modifier.size(size).background(tokens.surface, CircleShape).padding(1.5.dp).background(color, CircleShape))
}

/** The Files icon's ring: a determinate arc when the size is known, a turning arc when it is not. */
@Composable
private fun ProgressRing(progress: Float?, color: Color, track: Color) {
    val reduced = rememberReducedMotion()
    val turn = if (progress == null && !reduced) {
        rememberInfiniteTransition(label = "files-ring").animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "turn").value
    } else {
        0f
    }
    Canvas(Modifier.size(34.dp)) {
        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
        val sweep = progress?.let { (it.coerceIn(0f, 1f) * 360f).coerceAtLeast(8f) } ?: 100f
        drawArc(color, -90f + turn, sweep, false, Offset(inset, inset), arcSize, style = stroke)
    }
}

@Composable
private fun tabState(tab: AppTab, badges: TabBadges): String? = when (tab) {
    AppTab.HOME -> stringResource(
        when (badges.link) {
            DeviceConnection.LOCAL, DeviceConnection.AWAY, DeviceConnection.CONNECTED -> R.string.connection_connected
            DeviceConnection.CONNECTING -> R.string.connection_connecting
            DeviceConnection.UNAVAILABLE -> R.string.connection_unavailable
            DeviceConnection.OFFLINE -> R.string.connection_offline
        },
    )
    AppTab.FILES -> when {
        badges.filesProgress != null -> stringResource(R.string.tab_files_syncing_percent, (badges.filesProgress * 100).toInt())
        badges.filesBusy -> stringResource(R.string.tab_files_syncing)
        else -> null
    }
    AppTab.TERMINALS -> stringResource(R.string.tab_terminals_attention).takeIf { badges.terminalsAttention }
    AppTab.SETTINGS -> stringResource(R.string.tab_settings_attention).takeIf { badges.settingsAttention }
    AppTab.NOTES -> null
}

@Composable
fun quickLabel(action: QuickAction): String = stringResource(
    when (action) {
        QuickAction.SWITCH_COMPUTER -> R.string.tab_quick_switch
        QuickAction.SEND_FILES -> R.string.tab_quick_send_files
        QuickAction.NEW_NOTE -> R.string.tab_quick_new_note
        QuickAction.NEW_TERMINAL -> R.string.tab_quick_new_terminal
    },
)
