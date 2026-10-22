/* SPDX-License-Identifier: GPL-3.0-or-later */
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package org.desklink.mobile.ui.compose.shell

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.components.rememberReducedMotion
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.tokens

/** Width from which the tabs become a rail on the side and the content a centred column. */
const val WIDE_DP = 600

/** The widest a screen's content gets on a tablet. */
val MaxContentWidth = 840.dp

val TabBarHeight = 64.dp

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
    // Tapping a tab scrolls the pager; swiping the pager selects the tab.
    LaunchedEffect(nav.selected) {
        if (pager.currentPage != nav.selected.ordinal && !pager.isScrollInProgress) pager.animateScrollToPage(nav.selected.ordinal)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { nav.select(tabs[it]) }
    }
    val bar: @Composable () -> Unit = {
        TabItems(nav.selected, badges, vertical = wide, onSelect = nav::select, onQuickAction = onQuickAction)
    }
    val pages: @Composable (Modifier) -> Unit = { mod ->
        HorizontalPager(
            state = pager,
            modifier = mod,
            beyondViewportPageCount = 0,
            key = { tabs[it].key },
        ) { index ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = MaxContentWidth).fillMaxSize()) { content(tabs[index]) }
            }
        }
    }
    if (wide) {
        Row(modifier.fillMaxSize().background(tokens.bg)) {
            Column(
                Modifier.width(88.dp).fillMaxHeight().background(tokens.surface).statusBarsPadding().navigationBarsPadding(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { bar() }
            pages(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(modifier.fillMaxSize().background(tokens.bg)) {
            // The bar owns the navigation-bar inset, so the screens above it must not pad for it twice.
            pages(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars))
            Box(Modifier.fillMaxWidth().background(tokens.surface)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.hairline))
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().height(TabBarHeight).selectableGroup(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) { bar() }
            }
        }
    }
}

@Composable
private fun TabItems(
    selected: AppTab,
    badges: TabBadges,
    vertical: Boolean,
    onSelect: (AppTab) -> Unit,
    onQuickAction: (QuickAction) -> Unit,
) {
    AppTab.entries.forEach { tab ->
        val modifier = if (vertical) Modifier.fillMaxWidth().height(72.dp) else Modifier
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
    Box(
        modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
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
            Box(
                Modifier.height(32.dp).width(56.dp).clip(RoundedCornerShape(16.dp)).background(if (selected) t.accentContainer else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(tab.icon),
                    contentDescription = null,
                    tint = if (selected) t.onAccentContainer else t.textDim,
                    modifier = Modifier.size(24.dp),
                )
                TabBadge(tab, badges)
            }
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) t.text else t.textDim,
                maxLines = 1,
            )
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
