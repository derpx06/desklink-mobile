/* SPDX-License-Identifier: GPL-3.0-or-later */
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.ui.compose.DeskLinkShape
import org.desklink.mobile.ui.compose.tokens

// Wave 2's parts: the hero card, the coral live strip, the bottom sheet and the search field.

/**
 * The hero card of Home: radius 28, the accent container fading to the card colour from the top left, with a soft accent
 * glow in the top right corner, and a hairline. Nothing is written over the glow: it is 20 % at its strongest.
 */
@Composable
fun HeroCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val t = tokens
    val shape = DeskLinkShape.hero
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .drawWithCache {
                val base = Brush.linearGradient(
                    0f to t.accentContainer, 0.8f to t.surface, 1f to t.surface,
                    start = Offset.Zero, end = Offset(size.width * 0.35f, size.height),
                )
                val glow = Brush.radialGradient(
                    0f to t.accent.copy(alpha = if (t.isDark) 0.14f else 0.20f), 0.55f to Color.Transparent,
                    center = Offset(size.width, 0f), radius = size.width * 1.1f,
                )
                onDrawBehind {
                    drawRect(base)
                    drawRect(glow)
                }
            }
            .border(1.dp, t.hairline, shape)
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

/**
 * A coral strip: the other device is using this one (a session is live, a terminal wants you). 22 dp corners, a 40 dp
 * circle with the glyph and a coral dot, [title] (14/500) and a dim [detail], and one button at the end, 36 dp to look at
 * and 48 to touch. Live strips always carry the action that ends or opens the thing. At a large font the button goes
 * under the words.
 */
@Composable
fun LiveBanner(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int,
    detail: String? = null,
) {
    val t = tokens
    val stacked = LocalDensity.current.fontScale > 1.3f
    val action: @Composable () -> Unit = {
        Box(Modifier.heightIn(min = 48.dp).clip(DeskLinkShape.stadium).clickable(role = Role.Button, onClick = onAction), contentAlignment = Alignment.Center) {
            Text(
                actionLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = t.coralText,
                modifier = Modifier.background(t.surface, DeskLinkShape.stadium).heightIn(min = 36.dp).padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }
    }
    val words: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Text(title, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, color = t.text)
            if (detail != null) Text(detail, fontSize = 12.sp, lineHeight = 16.sp, color = t.textDim)
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(t.coralContainer, DeskLinkShape.banner)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 10.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(t.surface, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = null, tint = t.coralText, modifier = Modifier.size(20.dp))
                Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(8.dp).background(t.coral, CircleShape))
            }
            words(Modifier.weight(1f).padding(vertical = 2.dp))
            if (!stacked) action()
        }
        if (stacked) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { action() }
    }
}

/**
 * The design's bottom sheet: the raised surface, 28 dp top corners, a 32 x 4 handle (TalkBack names it and it expands or
 * collapses the sheet), a 32 % scrim. Opens fully: its content scrolls, it is never half hidden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeskBottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val t = tokens
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.surface2,
        shape = DeskLinkShape.sheet,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        dragHandle = { BottomSheetDefaults.DragHandle(width = 32.dp, height = 4.dp, color = t.hairlineStrong) },
        content = content,
    )
}

/**
 * A 52 dp stadium field on the raised surface for searching: the text at 16 sp, the accent caret, a dim [placeholder],
 * and a clear button once something is typed. TalkBack calls the field [label] (the placeholder by default: a placeholder
 * is not a label once something is typed). The IME's Search key calls [onSearch].
 */
@Composable
fun DeskSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onSearch: () -> Unit = {},
    label: String = placeholder,
) {
    val t = tokens
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).background(t.surface2, DeskLinkShape.stadium).padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, fontSize = 16.sp, color = t.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = t.text),
                cursorBrush = SolidColor(t.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth()
                    .semantics { contentDescription = label }
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = clearLabel) { onValueChange("") }, contentAlignment = Alignment.Center) {
                Icon(painterResource(org.desklink.mobile.R.drawable.sym_close), contentDescription = clearLabel, tint = t.textDim, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** A laptop drawn from two shapes (its screen and its base) in the text colour: the hero card's picture of the computer. */
@Composable
fun LaptopIllustration(modifier: Modifier = Modifier, dimmed: Boolean = false) {
    val t = tokens
    val ink = if (dimmed) t.textFaint else t.text
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = 76.dp, height = 50.dp).background(if (t.isDark) t.surface2 else Color(0xFF2A2A2E), RoundedCornerShape(7.dp)).border(3.dp, ink, RoundedCornerShape(7.dp)))
        Box(Modifier.size(width = 96.dp, height = 6.dp).background(ink, RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)))
    }
}
