/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.mousepad

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.components.rememberReducedMotion

/** How a touch on the desk's picture acts. */
enum class ControlMode {
    /** Directly where it lands. */
    Touch,

    /** Moves the desk's pointer like a laptop's trackpad. */
    Trackpad,

    /** Watch only: nothing is sent to the desk. */
    View,
}

/** The mouse buttons, keyboard and Super key work exactly when the computer has granted control and the view is not watch-only. */
fun controlInputEnabled(controlGranted: Boolean, mode: ControlMode): Boolean =
    controlGranted && mode != ControlMode.View

private val PillBg = Color(0xD1141416)
private val PillText = Color(0xFFF2F2F3)
private val PillSelected = Color(0xFFF2F2F3)
private val PillOnSelected = Color(0xFF18181B)
private val AmberNotice = Color(0xEBF5B84A)
private val OnAmber = Color(0xFF2A1D00)
/** The design's End: the coral container with coral text (6.6:1), not a solid coral slab over the picture. */
private val EndBg = Color(0xFF34201C)
private val EndText = Color(0xFFFF9A88)
private val ChipBg = Color.White.copy(alpha = 0.08f)
private val LiveDot = Color(0xFF3FD1A2)
private val KeyBg = Color(0xEB1E1E21)
private val KeyEdge = Color(0xFF38383D)

/**
 * The desk screen's floating pills: back, name and latency top left; End (coral) top right; the
 * amber notice while the desk has not allowed control; and the mode, clicks and keyboard
 * at the bottom, with [keys] above them while the keyboard is up.
 */
@Composable
fun DeskControls(
    visible: Boolean,
    deviceName: String,
    rttMs: Long?,
    live: Boolean,
    controlEnabled: Boolean,
    mode: ControlMode,
    onMode: (ControlMode) -> Unit,
    onBack: () -> Unit,
    onEnd: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onKeyboard: () -> Unit,
    onMore: () -> Unit,
    /** Why control was refused (the desk's portal, a password), shown inside the amber notice. */
    reason: String? = null,
    /** The strip is shown (not dismissed, not timed out); it also vanishes by itself the moment control is granted. */
    noticeVisible: Boolean = true,
    onDismissNotice: () -> Unit = {},
    /** Set once the computer has refused: asks it to share again (its permission prompt comes back). */
    onAllowControl: (() -> Unit)? = null,
    /** One-time line above the bottom pill saying what it is for. */
    hint: String? = null,
    /** F2: "mirror" or "extend", and switching between them (re-asks the desk). */
    display: String = "mirror",
    onDisplay: (String) -> Unit = {},
    /** Edge to edge (true) or the whole desktop (false), and flipping it. */
    fill: Boolean = false,
    onFill: () -> Unit = {},
    /** Whether the desk's sound plays here, and flipping it. */
    soundOn: Boolean = false,
    onSound: () -> Unit = {},
    keys: @Composable () -> Unit = {},
    moreMenu: @Composable () -> Unit = {},
) {
    val input = controlInputEnabled(controlEnabled, mode)
    Box(Modifier.fillMaxSize()) {
        // One compact strip, only while something is missing: control not yet granted (or watch-only).
        // Granted, dismissed or timed out, it is gone; it never repeats for the same state.
        if (live && noticeVisible && (!controlEnabled || mode == ControlMode.View)) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .background(AmberNotice, RoundedCornerShape(16.dp))
                    .padding(start = 12.dp, end = 4.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(painterResource(R.drawable.sym_front_hand), contentDescription = null, tint = OnAmber, modifier = Modifier.size(16.dp))
                Column(Modifier.widthIn(max = 380.dp)) {
                    if (!controlEnabled) {
                        Text(stringResource(R.string.remote_allow_control_first, deviceName), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnAmber, maxLines = 2)
                        reason?.takeIf(String::isNotBlank)?.let {
                            Text(it, fontSize = 11.sp, color = OnAmber, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        Text(stringResource(R.string.remote_view_only_hint), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnAmber, maxLines = 2)
                    }
                }
                if (onAllowControl != null && !controlEnabled) {
                    Box(
                        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onAllowControl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.remote_allow_control_action), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = OnAmber, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
                IconButton(onClick = onDismissNotice, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.sym_close), contentDescription = stringResource(R.string.remote_notice_dismiss), tint = OnAmber, modifier = Modifier.size(16.dp))
                }
            }
        }
        val still = rememberReducedMotion()
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.fillMaxSize(),
            enter = if (still) EnterTransition.None else fadeIn(),
            exit = if (still) ExitTransition.None else fadeOut(),
        ) {
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                // Back, then the computer's name with its live dot and the round trip: one glass capsule each, like the design.
                Row(Modifier.align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill { PillIcon(R.drawable.sym_arrow_back, stringResource(R.string.remote_close), onClick = onBack) }
                    Pill {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp),
                        ) {
                            Box(Modifier.size(8.dp).background(if (live) LiveDot else PillText.copy(alpha = 0.45f), CircleShape))
                            Text(deviceName, fontFamily = MonoFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                            rttMs?.let {
                                Text(stringResource(R.string.home_meta_rtt, it.toInt()), fontFamily = MonoFamily, fontSize = 11.sp, color = PillText.copy(alpha = 0.7f), maxLines = 1)
                            }
                        }
                    }
                }
                // F2: Mirror | Extend, under the name (the top centre is the amber notice's).
                Pill(Modifier.align(Alignment.TopStart).padding(top = 56.dp)) {
                    Row(Modifier.padding(horizontal = 3.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                        listOf("mirror" to R.string.remote_display_mirror, "extend" to R.string.remote_display_extend).forEach { (value, label) ->
                            val selected = display == value
                            Box(
                                Modifier
                                    .heightIn(min = 48.dp)
                                    .selectable(selected = selected, role = Role.RadioButton) { onDisplay(value) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(label),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) PillOnSelected else PillText,
                                    modifier = Modifier
                                        .background(if (selected) PillSelected else Color.Transparent, RoundedCornerShape(16.dp))
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
                // End: the coral container, as everywhere a session is live; 48 dp to touch.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClick = onEnd),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        Modifier.heightIn(min = 44.dp).background(EndBg, CircleShape).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(painterResource(R.drawable.sym_close), contentDescription = null, tint = EndText, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.active_session_end), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = EndText)
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    keys()
                    hint?.let {
                        Text(it, fontSize = 11.sp, color = PillText, modifier = Modifier.background(PillBg, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                    Pill {
                        val toolbarLabel = stringResource(R.string.remote_toolbar_label, deviceName)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 3.dp).semantics { contentDescription = toolbarLabel }) {
                            ModeSwitch(mode, onMode)
                            Box(Modifier.padding(horizontal = 4.dp).size(1.dp, 22.dp).background(Color.White.copy(alpha = 0.14f)))
                            PillText(stringResource(R.string.remote_left_click), input, description = stringResource(R.string.remote_left_click_description), onClick = onLeft)
                            PillText(stringResource(R.string.remote_right_click), input, description = stringResource(R.string.remote_right_click_description), onClick = onRight)
                            ToolChip(R.drawable.sym_keyboard, stringResource(R.string.remote_key_keyboard), enabled = input, onClick = onKeyboard)
                            // Switches with fixed names: a screen reader says "Desk sound, on", not a label that flips with the state.
                            ToolChip(R.drawable.sym_aspect_ratio, stringResource(R.string.remote_zoom_fill), selected = fill, toggle = true, onClick = onFill)
                            ToolChip(if (soundOn) R.drawable.sym_volume_up else R.drawable.sym_volume_mute, stringResource(R.string.remote_desk_sound), selected = soundOn, toggle = true, onClick = onSound)
                            Box {
                                PillIcon(R.drawable.sym_more_vert, stringResource(R.string.extra_options), onClick = onMore)
                                moreMenu()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A floating control over the picture: near-black glass, so it reads on any desktop. */
@Composable
internal fun Pill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = PillBg,
        contentColor = PillText,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = modifier,
        content = content,
    )
}

/** A text control inside a pill: 36dp to see, 48dp to touch. */
@Composable
internal fun PillText(
    label: String,
    enabled: Boolean,
    selected: Boolean = false,
    mono: Boolean = false,
    description: String? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                this.selected = selected
                description?.let { contentDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = if (mono) 11.sp else 12.sp,
            fontWeight = if (mono) FontWeight.Bold else FontWeight.SemiBold,
            fontFamily = if (mono) MonoFamily else null,
            color = when {
                selected -> PillOnSelected
                enabled -> PillText
                else -> PillText.copy(alpha = 0.45f)
            },
            maxLines = 1,
            modifier = Modifier
                .background(if (selected) PillSelected else ChipBg, RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

/** A key cap of the keyboard row: a 40 dp rounded rectangle on near-black glass with a hairline, 48 dp to touch. A [label] or an [icon]. */
@Composable
internal fun KeyCap(label: String?, description: String?, enabled: Boolean, @DrawableRes icon: Int? = null, selected: Boolean = false, onClick: () -> Unit) {
    val ink = if (selected) PillOnSelected else if (enabled) PillText else PillText.copy(alpha = 0.45f)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected; description?.let { contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = 40.dp)
                .widthIn(min = 44.dp)
                .background(if (selected) PillSelected else KeyBg, RoundedCornerShape(10.dp))
                .border(1.dp, KeyEdge, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (label != null) Text(label, fontFamily = MonoFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1)
            if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        }
    }
}

/** An icon chip (a 36 dp round, 48 dp to touch): selected is the light fill, like the mode switch. A [toggle] is a switch with a fixed [description]. */
@Composable
internal fun ToolChip(@DrawableRes icon: Int, description: String, enabled: Boolean = true, selected: Boolean = false, toggle: Boolean = false, onClick: () -> Unit) {
    val base = Modifier.size(48.dp)
    Box(
        (if (toggle) base.toggleable(value = selected, enabled = enabled, role = Role.Switch) { onClick() }
        else base.clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { this.selected = selected })
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp).background(if (selected) PillSelected else ChipBg, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = if (selected) PillOnSelected else if (enabled) PillText else PillText.copy(alpha = 0.45f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun PillIcon(@DrawableRes icon: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = PillText, disabledContentColor = PillText.copy(alpha = 0.45f)),
        modifier = Modifier.size(48.dp),
    ) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

/** Trackpad, Touch or View (nothing is sent), as one segmented control. */
@Composable
private fun ModeSwitch(mode: ControlMode, onMode: (ControlMode) -> Unit) {
    val modes = listOf(
        Triple(ControlMode.Trackpad, R.string.remote_mode_trackpad, R.drawable.sym_touchpad_mouse),
        Triple(ControlMode.Touch, R.string.remote_mode_touch, R.drawable.sym_touch_app),
        Triple(ControlMode.View, R.string.remote_mode_view, R.drawable.sym_visibility),
    )
    Row(
        Modifier
            .padding(start = 2.dp)
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        modes.forEach { (m, label, icon) ->
            val selected = mode == m
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected, role = Role.RadioButton) { onMode(m) },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .height(32.dp)
                        .background(if (selected) PillSelected else Color.Transparent, RoundedCornerShape(16.dp))
                        .padding(start = 10.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(painterResource(icon), contentDescription = null, tint = if (selected) PillOnSelected else PillText, modifier = Modifier.size(17.dp))
                    Text(stringResource(label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (selected) PillOnSelected else PillText)
                }
            }
        }
    }
}
