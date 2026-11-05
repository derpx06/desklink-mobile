/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.desklink.mobile.R
import org.desklink.mobile.plugins.mousepad.ControlMode
import org.desklink.mobile.plugins.mousepad.DeskControls
import org.desklink.mobile.plugins.mousepad.KeyCap
import org.desklink.mobile.plugins.mousepad.ViewerStatus
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The desk screen as a second display (Extend), on a phone on its side: the states before the picture and the live
 * chrome, with a camera notch on the left edge. `./gradlew testDebugUnitTest --tests "*ExtendShots*" -Ddesklink.shots=<dir>`;
 * images land as `<form>-ext-<state>-<theme>.png`.
 */
abstract class ExtendShots : ShotTest() {
    private val desk = "arch-desk"
    private val notch = 44.dp

    /** The viewer is always dark and black behind its cards. */
    @Composable
    private fun Stage(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { content() }
    }

    private fun status(stage: String, waiting: Boolean) = @Composable {
        Stage { ViewerStatus(extend = true, deviceName = desk, stage = stage, waiting = waiting, onRetry = {}, onClose = {}) }
    }

    @Test fun asking() = shoot("ext-asking", status("Requesting live screen", waiting = true))
    @Test fun lookAtDesk() = shoot("ext-allow-on-desk", status("Waiting for you to allow it on $desk. Look at its screen.", waiting = true))
    @Test fun nothingHappened() = shoot("ext-nothing-happened", status("Nothing happened yet", waiting = false))
    @Test fun refused() = shoot("ext-refused", status("$desk can't add a display: its screen sharing is switched off.", waiting = false))

    @Composable
    private fun FakeDesktop() {
        // The extended monitor: a wallpaper with two windows, so letterboxing, the notch and the chrome read against content.
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2B3A67), Color(0xFF0F1426))))) {
            Box(Modifier.offset(60.dp, 70.dp).width(280.dp).height(170.dp).background(Color(0xFFF1F1F4), RoundedCornerShape(10.dp)))
            Box(Modifier.offset(320.dp, 110.dp).width(240.dp).height(150.dp).background(Color(0xFF1E1E24), RoundedCornerShape(10.dp)))
        }
    }

    private fun live(granted: Boolean = true, visible: Boolean = true, keys: Boolean = false, reason: String? = null, soundOn: Boolean = false) = @Composable {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // The picture keeps out of the notch strip; the chrome does too.
            Box(Modifier.fillMaxSize().padding(start = notch)) { FakeDesktop() }
            Box(Modifier.align(Alignment.CenterStart).width(26.dp).fillMaxHeight(0.3f).background(Color.Black, RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)))
            DeskControls(
                visible = visible, deviceName = desk, rttMs = 12, live = true, controlEnabled = granted, mode = ControlMode.Touch,
                onMode = {}, onBack = {}, onEnd = {}, onLeft = {}, onRight = {}, onKeyboard = {}, onMore = {},
                reason = reason, onAllowControl = if (!granted) ({}) else null, display = "extend", soundOn = soundOn,
                safeInsets = WindowInsets(left = notch),
                keys = { if (keys) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    KeyCap("esc", null, granted) {}; KeyCap("tab", null, granted) {}; KeyCap("ctrl", null, granted, selected = true) {}; KeyCap("alt", null, granted) {}
                    KeyCap("super", "Super key", granted) {}; KeyCap(null, "Left", granted, R.drawable.sym_arrow_back) {}; KeyCap(null, "Right", granted, R.drawable.sym_arrow_forward) {}
                } },
            )
        }
    }

    @Test fun liveGranted() = shoot("ext-live", live(soundOn = true))
    @Test fun liveWaitingForControl() = shoot("ext-live-allow-control", live(granted = false, reason = "Allow remote interaction on $desk"))
    @Test fun liveKeys() = shoot("ext-live-keys", live(keys = true))
    @Test fun liveHidden() = shoot("ext-live-hidden", live(visible = false))
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-xxhdpi")
class LandscapeExtendShots : ExtendShots() { override val form = "landscape" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h412dp-xxhdpi")
class WideExtendShots : ExtendShots() { override val form = "wide" }

/** Large text on the smallest landscape phone: the card scrolls, nothing is clipped or too small to touch. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w640dp-h320dp-xhdpi")
class HugeExtendShots : ExtendShots() {
    override val form = "landscape-huge"
    @Before fun fontScale() = RuntimeEnvironment.setFontScale(2.0f)
}
