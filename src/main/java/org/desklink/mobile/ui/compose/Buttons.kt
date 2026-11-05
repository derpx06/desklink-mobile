/*
 * SPDX-FileCopyrightText: 2023 Dmitry Yudin <dgyudin@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow.Companion.Ellipsis
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The design's three stadium buttons. [Primary] = the one action of a screen, [Tonal] = a quiet one, [Outlined] = the other choice beside it, [Danger] = the confirm of something that cannot be undone (coral container). */
enum class DeskButtonStyle { Primary, Tonal, Outlined, Danger }

/** The design presses every tappable down to 97 % for 120 ms. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s = animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "press")
    // Read inside the layer: a press redraws the layer, it does not recompose the button.
    return graphicsLayer { scaleX = s.value; scaleY = s.value }
}

/**
 * The stadium button: 56 dp (16 sp label) by default, 48 dp inside a card (15 sp), never shorter than 48.
 * The label wraps at a large font size instead of being cut: the label is the button.
 */
@Composable
fun StadiumButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: DeskButtonStyle = DeskButtonStyle.Primary,
    height: Dp = 56.dp,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    val t = tokens
    val (bg, fg) = when {
        // Disabled: the content at 38 % on a faint fill, as Material does; never the whole button faded.
        !enabled -> (if (style == DeskButtonStyle.Outlined) Color.Transparent else t.text.copy(alpha = 0.12f)) to t.text.copy(alpha = 0.38f)
        style == DeskButtonStyle.Primary -> t.accent to t.onAccent
        style == DeskButtonStyle.Tonal -> t.accentContainer to t.onAccentContainer
        style == DeskButtonStyle.Danger -> t.coralContainer to t.coralText
        else -> Color.Transparent to t.accentText
    }
    val shape = DeskLinkShape.stadium
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(source)
            .heightIn(min = maxOf(height, 48.dp))
            .clip(shape)
            .background(bg)
            .then(if (style == DeskButtonStyle.Outlined) Modifier.border(1.dp, t.hairlineStrong, shape) else Modifier)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = fg,
            fontSize = if (height >= 52.dp) 16.sp else 15.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

/** Filled accent: the one action of a screen or a dialog. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, height: Dp = 56.dp, enabled: Boolean = true) =
    StadiumButton(text, onClick, modifier, DeskButtonStyle.Primary, height, icon, enabled)

/** The choice beside a primary button: outlined, or [tonal] when it should read as a fill. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, height: Dp = 56.dp, enabled: Boolean = true, tonal: Boolean = false) =
    StadiumButton(text, onClick, modifier, if (tonal) DeskButtonStyle.Tonal else DeskButtonStyle.Outlined, height, icon, enabled)

/** No box: accent text, 48 dp tall ("Skip", "All actions", "Cancel"). */
@Composable
fun DeskTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, @DrawableRes icon: Int? = null) {
    val t = tokens
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = DeskLinkShape.stadium,
        colors = ButtonDefaults.textButtonColors(contentColor = t.accentText, disabledContentColor = t.text.copy(alpha = 0.38f)),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
}

/** A 48 dp round icon button with a 24 dp glyph. [contentDescription] is required: an icon alone says nothing to TalkBack. */
@Composable
fun DeskIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = tokens.text,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
        Icon(painterResource(icon), contentDescription = contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.38f), modifier = Modifier.size(24.dp))
    }
}

/** The extended floating action button ("New note"): 56 dp, radius 18, the accent, an icon and its label. */
@Composable
fun DeskExtendedFab(label: String, @DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = tokens
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .pressScale(source)
            .shadow(6.dp, shape)
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(t.accent)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = t.onAccent, modifier = Modifier.size(24.dp))
        Text(label, color = t.onAccent, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, modifier = Modifier.padding(vertical = 8.dp))
    }
}

// ---- old names, kept so existing call sites compile; deprecated, not delegating: removed with their last caller (wave 6) ----

@Deprecated("Use DeskTextButton", ReplaceWith("DeskTextButton(text, onClick, modifier, enabled)"))
@Composable
fun KdeTextButton(
    onClick: () -> Unit,
    modifier: Modifier,
    text: String,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    iconLeft: ImageVector? = null,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = contentPadding,
        content = {
            iconLeft?.let {
                Icon(imageVector = it, contentDescription = null)
                Spacer(Modifier.width(16.dp))
            }
            Text(text = text)
        }
    )
}

@Deprecated("Use PrimaryButton or SecondaryButton")
@Composable
fun KdeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    text: String? = null,
    contentDescription: String? = null,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = DeskLinkShape.stadium,
        colors = colors,
        content = {
            icon?.let { Icon(imageVector = it, contentDescription = contentDescription ?: text) }
            text?.let { Text(it, maxLines = 1, overflow = Ellipsis) }
        }
    )
}

@Preview
@Composable
private fun IconButtonPreview() {
    @Suppress("DEPRECATION")
    KdeButton({}, Modifier.width(120.dp), ButtonDefaults.buttonColors(Color.Gray, Color.DarkGray), "Button Text", null, Icons.Default.Build)
}
