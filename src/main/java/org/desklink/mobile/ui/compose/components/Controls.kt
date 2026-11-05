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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DarkTokens
import org.desklink.mobile.ui.compose.DeskLinkShape
import org.desklink.mobile.ui.compose.LightTokens
import org.desklink.mobile.ui.compose.pressScale
import org.desklink.mobile.ui.compose.tokens

// The design's small parts. Buttons and the icon button live in ui/compose/Buttons.kt, cards in DeskLinkComponents.kt,
// rows in ScreenParts.kt (GroupRow). Every tappable here is at least 48 dp to touch, whatever it looks like.

/** A status colour as a dot: mint connected, amber waiting, red failed, faint offline. The word beside it carries the meaning. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

/**
 * Three-way switch between views of one screen (Notes / Snippets / Clipboard): a 44 dp track on the faint fill,
 * the selected segment raised on the card colour. The label scales with the system font up to 1.3 and wraps past it.
 */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val t = tokens
    CappedFontScale(1.3f) {
        Row(
            modifier.fillMaxWidth().background(t.fill, RoundedCornerShape(30.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (on) t.surface else Color.Transparent)
                        .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) })
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = 15.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = if (on) t.text else t.textDim, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** A filter ("All", "Images"): 36 dp, selected on the accent container, otherwise outlined. Exactly one of a row is selected. */
@Composable
fun DeskFilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = tokens
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.minimumInteractiveComponentSize().selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(36.dp)
                .clip(shape)
                .background(if (selected) t.accentContainer else Color.Transparent)
                .border(1.dp, if (selected) Color.Transparent else t.hairlineStrong, shape)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (selected) t.onAccentContainer else t.textDim, maxLines = 1)
        }
    }
}

/** A one-tap suggestion with a glyph ("Send a link"): the card colour with a hairline, 36 dp. */
@Composable
fun AssistChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    val t = tokens
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.minimumInteractiveComponentSize().clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(36.dp).clip(shape).background(t.surface).border(1.dp, t.hairlineStrong, shape).padding(start = if (icon != null) 10.dp else 14.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(painterResource(icon), contentDescription = null, tint = t.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = t.text, maxLines = 1)
        }
    }
}

/** A glyph in a rounded square: file kinds, rows with a tinted icon. [container] and [content] default to the accent pair. */
@Composable
fun IconTile(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    container: Color = tokens.accentContainer,
    content: Color = tokens.onAccentContainer,
    radius: Dp = 12.dp,
) {
    Box(modifier.size(size).background(container, RoundedCornerShape(radius)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * Home's quick action: a 52 dp tonal square with a 24 dp glyph and a 12 sp label below, four to a row.
 * The label wraps (2-3 lines at a large font) so it is never cut.
 */
@Composable
fun QuickActionTile(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val t = tokens
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressScale(source)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = source, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconTile(icon, size = 52.dp, container = t.accentContainer, content = t.onAccentContainer, radius = 18.dp)
        Text(label, fontSize = 12.sp, lineHeight = 15.sp, textAlign = TextAlign.Center, color = t.text)
    }
}

/**
 * The big tile of the hero card (three across): a 22 dp glyph at the top, the label at the bottom, 84 dp tall at least.
 * [primary] fills it with the accent; one per screen. A disabled tile says why in [hint].
 */
@Composable
fun ActionTile(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    hint: String? = null,
) {
    val t = tokens
    val shape = DeskLinkShape.card
    val source = remember { MutableInteractionSource() }
    val fg = if (primary) t.onAccent else t.text
    Column(
        modifier
            .pressScale(source)
            .heightIn(min = 84.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(shape)
            .background(if (primary) t.accent else t.surface)
            .then(if (primary) Modifier else Modifier.border(1.dp, t.hairline, shape))
            .clickable(interactionSource = source, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = if (primary) t.onAccent else t.accent, modifier = Modifier.size(22.dp))
        Column {
            Text(label, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = fg)
            if (hint != null) Text(hint, fontSize = 12.sp, lineHeight = 16.sp, color = if (primary) t.onAccent else t.textDim)
        }
    }
}

/** A linear progress bar: 6 dp, [progress] 0..1, the percentage belongs in the text next to it. */
@Composable
fun DeskProgressBar(progress: Float, modifier: Modifier = Modifier, color: Color = tokens.accent, track: Color = tokens.surface2) {
    val p = progress.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(track).progressSemantics(p)) {
        Box(Modifier.fillMaxWidth(p).height(6.dp).background(color, RoundedCornerShape(3.dp)))
    }
}

/** Whether a setup step (a permission) is done. */
enum class ChecklistState { Todo, Done }

/**
 * A row of the post-pairing checklist: a 22 dp glyph, a title and one line saying what it is for, and at the end a
 * 34 dp button ([actionLabel], "Allow") that turns into the mint [doneLabel] ("On") once done. A Done row stays tappable
 * only if [onClick] still does something (to turn it off); pass null otherwise.
 */
@Composable
fun ChecklistRow(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    state: ChecklistState,
    actionLabel: String,
    doneLabel: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val t = tokens
    val done = state == ChecklistState.Done
    Row(
        modifier.fillMaxWidth().background(t.surface).heightIn(min = 56.dp).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = t.textDim, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, lineHeight = 21.sp, color = t.text)
            Text(description, fontSize = 13.sp, lineHeight = 17.sp, color = t.textDim)
        }
        // 48 dp to touch, 34 dp to look at.
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .widthIn(min = 64.dp)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(17.dp)
            Row(
                Modifier
                    .heightIn(min = 34.dp)
                    .widthIn(min = 64.dp)
                    .clip(shape)
                    .background(if (done) Color.Transparent else t.accentContainer)
                    .border(1.dp, if (done) t.hairlineStrong else Color.Transparent, shape)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (done) {
                    Icon(painterResource(R.drawable.sym_check_circle), contentDescription = null, tint = t.mintText, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (done) doneLabel else actionLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (done) t.mintText else t.onAccentContainer)
            }
        }
    }
}

/** One line of a checklist note: a ring (a check once [done], struck through and dimmer). Draws in the colour of its container. */
@Composable
fun CheckItem(label: String, done: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier, color: Color = tokens.onAccentContainer) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = done, role = Role.Checkbox, onValueChange = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (done) Icon(painterResource(R.drawable.sym_check_circle), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            else Box(Modifier.size(18.dp).border(2.dp, color.copy(alpha = 0.7f), CircleShape))
        }
        Text(
            label,
            fontSize = 14.sp,
            color = if (done) color.copy(alpha = 0.75f) else color,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The colour of a snackbar's action: the accent of the opposite theme, so it reads on the inverse surface. */
@Composable
fun snackbarActionColor(): Color = if (tokens.isDark) LightTokens.accentText else DarkTokens.accentText

/** The inverse snackbar: [message] in past tense, no full stop, and at most one action. */
@Composable
fun DeskSnackbar(message: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: () -> Unit = {}) {
    val t = tokens
    Row(
        modifier
            .shadow(6.dp, DeskLinkShape.small)
            .background(t.inverse, DeskLinkShape.small)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = if (actionLabel != null) 6.dp else 16.dp)
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, fontSize = 14.sp, lineHeight = 20.sp, color = t.onInverse, modifier = Modifier.weight(1f).padding(vertical = 8.dp), overflow = TextOverflow.Ellipsis, maxLines = 4)
        if (actionLabel != null) {
            Box(Modifier.heightIn(min = 48.dp).clip(DeskLinkShape.stadium).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(actionLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = snackbarActionColor())
            }
        }
    }
}

/** A host that shows [SnackbarHostState] messages as [DeskSnackbar]s (place it 12 dp from the sides, above the bar). */
@Composable
fun DeskSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        DeskSnackbar(data.visuals.message, actionLabel = data.visuals.actionLabel, onAction = { data.performAction() })
    }
}
