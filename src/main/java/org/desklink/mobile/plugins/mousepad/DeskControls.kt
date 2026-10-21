/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.mousepad

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
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

/** How a touch on the desk's picture acts. */
enum class ControlMode {
    /** Directly where it lands. */
    Touch,

    /** Moves the desk's pointer like a laptop's trackpad. */
    Trackpad,

    /** Watch only: nothing is sent to the desk. */
    View,
}

private val PillBg = Color(0xD1141416)
private val PillText = Color(0xFFF2F2F3)
private val PillSelected = Color(0xFFF2F2F3)
private val PillOnSelected = Color(0xFF18181B)
private val AmberNotice = Color(0xEBF5B84A)
private val OnAmber = Color(0xFF2A1D00)
private val EndCoral = Color(0xFFFF7C66)
private val OnCoral = Color(0xFF2A0D06)

/**
 * The desk screen's floating pills: back, name and latency top left; End (coral) top right; the
 * amber notice while the desk has not allowed control; and the mode, clicks, keyboard and Super
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
    onSuper: () -> Unit,
    onMore: () -> Unit,
    /** Why control was refused (the desk's portal, a password), shown inside the amber notice. */
    reason: String? = null,
    keys: @Composable () -> Unit = {},
    moreMenu: @Composable () -> Unit = {},
) {
    val input = controlEnabled && mode != ControlMode.View
    Box(Modifier.fillMaxSize()) {
        // Control is what the desk grants; until then, say so where the eye already is.
        if (live && !controlEnabled && mode != ControlMode.View) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp)
                    .background(AmberNotice, RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(painterResource(R.drawable.sym_front_hand), contentDescription = null, tint = OnAmber, modifier = Modifier.size(16.dp))
                Column(Modifier.widthIn(max = 460.dp)) {
                    Text(stringResource(R.string.remote_allow_control_first, deviceName), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnAmber, maxLines = 2)
                    reason?.takeIf(String::isNotBlank)?.let {
                        Text(it, fontSize = 11.sp, color = OnAmber, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        AnimatedVisibility(visible = visible, modifier = Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                Pill(Modifier.align(Alignment.TopStart)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 14.dp)) {
                        PillIcon(R.drawable.sym_arrow_back, stringResource(R.string.remote_close), onClick = onBack)
                        Text(deviceName, fontFamily = MonoFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                        rttMs?.let {
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.home_meta_rtt, it.toInt()), fontFamily = MonoFamily, fontSize = 11.sp, color = PillText.copy(alpha = 0.7f))
                        }
                    }
                }
                // End: coral, as everywhere a session is live.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClick = onEnd),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Row(
                        Modifier.height(32.dp).background(EndCoral, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(6.dp).background(OnCoral, CircleShape))
                        Text(stringResource(R.string.active_session_end), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = OnCoral)
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    keys()
                    Pill {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 3.dp)) {
                            ModeSwitch(mode, onMode)
                            Box(Modifier.padding(horizontal = 4.dp).size(1.dp, 22.dp).background(Color.White.copy(alpha = 0.14f)))
                            PillText(stringResource(R.string.remote_left_click), input, description = stringResource(R.string.remote_left_click_description), onClick = onLeft)
                            PillText(stringResource(R.string.remote_right_click), input, description = stringResource(R.string.remote_right_click_description), onClick = onRight)
                            PillIcon(R.drawable.sym_keyboard, stringResource(R.string.remote_key_keyboard), enabled = input, onClick = onKeyboard)
                            PillText("super", input, mono = true, description = stringResource(R.string.remote_key_super), onClick = onSuper)
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
                .background(if (selected) PillSelected else Color.Transparent, RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        )
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
