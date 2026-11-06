/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskButtonStyle
import org.desklink.mobile.ui.compose.DeskLinkShape
import org.desklink.mobile.ui.compose.DeskLinkSpacing
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.StadiumButton
import org.desklink.mobile.ui.compose.tokens

/** Light status-bar icons while a dark screen (scanner, terminal) is up, in either theme. */
@Composable
fun DarkSystemBars() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val old = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { old?.let { controller.isAppearanceLightStatusBars = it } }
    }
}

/**
 * A pushed screen's top row: Back (48 dp), then the [title] (22/600, wraps at a large font, a heading for TalkBack)
 * or space, and optional actions on the right. With no title the screen draws its own [LargeTitle] below.
 */
@Composable
fun ScreenHeader(onBack: () -> Unit, modifier: Modifier = Modifier, tint: Color = tokens.text, title: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.sym_arrow_back), contentDescription = stringResource(R.string.navigate_up), tint = tint)
        }
        if (title != null) {
            Text(title, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp).semantics { heading() })
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
    }
}

/**
 * How far a tab's big title has folded away while its list scrolls: 0 (whole) to 1 (small title, no subtitle). Scrolling up folds
 * it before the list moves, scrolling back down unfolds it once the list is at its top: the top bar of the tab roots.
 * Hand [connection] to `Modifier.nestedScroll` on the screen's root and [fraction] to [LargeTitle].
 */
@androidx.compose.runtime.Stable
class HeaderCollapse(private val maxPx: Float) {
    private var offset by androidx.compose.runtime.mutableFloatStateOf(0f)
    val fraction: Float get() = if (maxPx <= 0f) 0f else (-offset / maxPx).coerceIn(0f, 1f)

    val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
            if (available.y >= 0f || offset <= -maxPx) return Offset.Zero
            val next = (offset + available.y).coerceAtLeast(-maxPx)
            val used = next - offset
            offset = next
            return Offset(0f, used)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
            if (available.y <= 0f || offset >= 0f) return Offset.Zero
            val next = (offset + available.y).coerceAtMost(0f)
            val used = next - offset
            offset = next
            return Offset(0f, used)
        }
    }

    /** What a finger needs to travel to fold the title completely. */
    companion object {
        const val DISTANCE_DP = 56
    }
}

@Composable
fun rememberHeaderCollapse(): HeaderCollapse {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { HeaderCollapse.DISTANCE_DP.dp.toPx() }
    return remember(px) { HeaderCollapse(px) }
}

/**
 * The big title of a tab (30 sp, 22 sp once [collapse] is 1) and its trailing control, then a dim line full width under both
 * (it folds away with [collapse]). The row is as tall as its tallest part, so a 48 dp control sets it and a small chip does not.
 */
@Composable
fun LargeTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null, collapse: Float = 0f, trailing: @Composable RowScope.() -> Unit = {}) {
    val size = (30f - 8f * collapse).sp
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 14.dp - (6 * collapse).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, fontSize = size, lineHeight = size * 1.2f, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            trailing()
        }
        if (subtitle != null) {
            Text(
                subtitle, fontSize = 13.sp, lineHeight = 18.sp, color = tokens.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis,
                // Folds by height and fades, so the list below moves up with the finger instead of jumping.
                modifier = Modifier
                    .padding(top = 2.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val h = (placeable.height * (1f - collapse)).roundToInt()
                        layout(placeable.width, h) { placeable.placeWithLayer(0, 0) { alpha = 1f - collapse } }
                    },
            )
        }
    }
}

/** "Open on arch-desk", "Quick actions": 14/500 dim, a heading for TalkBack. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = tokens.textDim, modifier = Modifier.weight(1f).semantics { heading() })
        trailing()
    }
}

/** Rows on one rounded group, 2dp apart so the window shows between them. */
@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(DeskLinkShape.group), verticalArrangement = Arrangement.spacedBy(DeskLinkSpacing.rowGap), content = content)
}

/**
 * A settings row: icon, title, a dim line (mono for names and keys), and whatever sits on the
 * right. A switch row is one toggle for TalkBack; the switch itself only shows the state.
 */
@Composable
fun GroupRow(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    monoSubtitle: Boolean = false,
    titleColor: Color = tokens.text,
    iconTint: Color = tokens.textDim,
    checked: Boolean? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    /** A trailing chevron: the row opens something. */
    chevron: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val t = tokens
    val haptics = rememberDeskHaptics()
    val action = when {
        checked != null && onClick != null -> Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch) { haptics.toggle(!checked); onClick() }
        onClick != null -> Modifier.clickable(enabled = enabled, onClick = onClick)
        // A switch the phone only shows (set at the desk): still announced as a switch, and as off or on.
        checked != null -> Modifier.toggleable(value = checked, enabled = false, role = Role.Switch) {}
        else -> Modifier
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(t.surface)
            .then(action)
            .alpha(if (enabled) 1f else 0.55f)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, lineHeight = 21.sp, color = titleColor)
            subtitle?.let {
                Text(
                    it,
                    style = if (monoSubtitle) TextStyle(fontFamily = MonoFamily, fontSize = 13.sp) else TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
                    // Never cut: what a setting says is what it does (and a command to copy must be whole).
                    color = t.textDim,
                )
            }
        }
        trailing()
        if (chevron) Icon(painterResource(R.drawable.sym_chevron_right), contentDescription = null, tint = t.textDim, modifier = Modifier.size(22.dp))
        if (checked != null) Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = deskSwitchColors())
    }
}

/** A glyph in a circle of the faint fill: quick actions, the clipboard row, media. */
@Composable
fun IconCircle(@DrawableRes icon: Int, size: Dp, iconSize: Dp, modifier: Modifier = Modifier, background: Color = tokens.fill, tint: Color = tokens.text) {
    Box(modifier.size(size).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}
