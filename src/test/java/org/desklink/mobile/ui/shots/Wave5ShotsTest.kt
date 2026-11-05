/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.desklink.mobile.R
import org.desklink.mobile.plugins.mousepad.ControlMode
import org.desklink.mobile.plugins.mousepad.DeskControls
import org.desklink.mobile.plugins.mousepad.KeyCap
import org.desklink.mobile.plugins.terminal.TerminalHeader
import org.desklink.mobile.ui.about.AboutData
import org.desklink.mobile.ui.about.AboutPerson
import org.desklink.mobile.ui.compose.DeskTextButton
import org.desklink.mobile.ui.compose.components.DeskTextField
import org.desklink.mobile.ui.compose.components.DialogAction
import org.desklink.mobile.ui.compose.components.GroupRow
import org.desklink.mobile.ui.compose.components.RowGroup
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.components.DeskDialogCard
import org.desklink.mobile.ui.compose.model.device.DeviceUiModel
import org.desklink.mobile.ui.compose.screen.about.AboutScreen
import org.desklink.mobile.ui.compose.screen.settings.SettingsColumn
import org.desklink.mobile.ui.compose.screen.share.ShareScreen
import org.desklink.mobile.ui.compose.tokens
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Wave 5: the dialog card, the text field, the sub-page header, About, the share list, the terminal header and key
 * caps. `./gradlew testDebugUnitTest --tests "*Wave5Shots*"`; images land in `~/.cache/android-ui/shots/` as `<form>-w5-<screen>-<theme>.png`.
 */
abstract class Wave5Shots : ShotTest() {
    private val desk = "arch-desk"

    @Composable
    private fun Backdrop(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(tokens.bg).padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { content() }
    }

    @Test fun dialogConfirm() = shoot("w5-dialog-remove") {
        Backdrop {
            DeskDialogCard(
                "Remove 3 items?",
                DialogAction("Remove", destructive = true) {},
                DialogAction("Cancel") {},
            ) { Text("They are removed from the Bucket on $desk and from this phone. Files already saved elsewhere stay.") }
        }
    }

    @Test fun dialogField() = shoot("w5-dialog-field") {
        Backdrop {
            var text = "Holiday 2026"
            DeskDialogCard("Rename", DialogAction("Rename") {}, DialogAction("Cancel") {}) {
                DeskTextField(text, { text = it }, Modifier.fillMaxWidth(), label = "Rename", singleLine = true)
            }
        }
    }

    @Test fun dialogPasswordWrong() = shoot("w5-dialog-password") {
        Backdrop {
            DeskDialogCard("Session password", DialogAction("Unlock", enabled = false) {}, DialogAction("Cancel") {}) {
                Column {
                    Text("Enter the password you set on $desk.")
                    DeskTextField(
                        "hunter", {}, Modifier.fillMaxWidth().padding(top = 16.dp), label = "Password", singleLine = true, isError = true,
                        supportingText = "That's not the password",
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = { DeskTextButton("Show", {}) },
                    )
                }
            }
        }
    }

    @Test fun dialogInfoOnly() = shoot("w5-dialog-info") {
        Backdrop { DeskDialogCard("Sharing $desk's screen", DialogAction("OK") {}, null) { Text("Open Desk screen from the Control tile to see and control it from here.") } }
    }

    @Test fun subPage() = shoot("w5-subpage") {
        SettingsColumn("Control", onBack = {}, subtitle = "What $desk can do on this phone") {
            RowGroup {
                GroupRow("Mouse and keyboard", icon = R.drawable.sym_touchpad_mouse, subtitle = "Off", chevron = true, onClick = {})
                GroupRow("Glide", icon = R.drawable.sym_mouse, subtitle = "$desk's pointer can cross onto this phone", checked = true, onClick = {})
            }
        }
    }

    @Test fun folderHeader() = shoot("w5-folder-header") {
        Column(Modifier.fillMaxSize().background(tokens.bg)) { ScreenHeader({}, title = "Holidays 2026") {} }
    }

    private val about = AboutData("DeskLink", R.drawable.desklink_mark, "1.3.0", bugURL = "https://example.org/bug", sourceCodeURL = "https://example.org/src", donateURL = "https://example.org/donate", authorsFooterText = R.string.everyone_else).apply {
        authors += AboutPerson("Albert Vaca Cintora", R.string.maintainer_and_developer)
        authors += AboutPerson("Aleix Pol", R.string.developer)
    }

    @Test fun about() = shoot("w5-about") {
        Column(Modifier.fillMaxSize().background(tokens.bg)) {
            ScreenHeader({})
            AboutScreen(about, {}, {}, {}, {}, {}, onReplayTour = {})
        }
    }

    @Test fun share() = shoot("w5-share") {
        Column(Modifier.fillMaxSize().background(tokens.bg)) {
            ShareScreen(
                listOf(
                    DeviceUiModel("d1", R.drawable.ic_device_laptop_32dp, desk, 0, isReachable = true, isPaired = true),
                    DeviceUiModel("d2", R.drawable.ic_device_laptop_32dp, "office-laptop", 0, isReachable = false, isPaired = true),
                ),
                intentHasUrl = false, isRefreshing = false, onDeviceClick = {}, onRefresh = {},
            )
        }
    }

    @Test fun terminalHeader() = shoot("w5-terminal-header") {
        Column(Modifier.fillMaxSize().background(Color(0xFF0E0F11)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TerminalHeader("zsh", "~/desklink-mobile · $desk · 12 ms", null, "80×38", "Back to sessions", {}) {}
            TerminalHeader("Claude Code with a long name that must not push the size off", "~/a/very/deep/folder/that/needs/a/middle/ellipsis/to/fit · $desk", "Reconnecting…", "120×40", "Back to sessions", {}) {}
        }
    }

    @Test fun keyCaps() = shoot("w5-keycaps") {
        Column(Modifier.fillMaxSize().background(Color(0xFF111113)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                KeyCap("esc", null, true) {}
                KeyCap("tab", null, true) {}
                KeyCap("ctrl", null, true, selected = true) {}
                KeyCap("alt", null, true) {}
                KeyCap("super", "Super key", true) {}
                KeyCap(null, "Left", true, R.drawable.sym_arrow_back) {}
                KeyCap(null, "Up", true, R.drawable.sym_arrow_upward) {}
                KeyCap(null, "Down", false, R.drawable.sym_arrow_downward) {}
                KeyCap(null, "Right", true, R.drawable.sym_arrow_forward) {}
            }
        }
    }

    private fun controls(mode: ControlMode, granted: Boolean, visible: Boolean = true, fill: Boolean = false, soundOn: Boolean = false, keys: Boolean = false, reason: String? = null) = @Composable {
        Box(
            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF4A5B86), Color(0xFF151A28)))),
        ) {
            DeskControls(
                visible = visible, deviceName = desk, rttMs = 12, live = true, controlEnabled = granted, mode = mode,
                onMode = {}, onBack = {}, onEnd = {}, onLeft = {}, onRight = {}, onKeyboard = {}, onMore = {},
                reason = reason, onAllowControl = if (!granted) ({}) else null, display = "mirror",
                fill = fill, soundOn = soundOn,
                keys = { if (keys) androidx.compose.foundation.layout.Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    KeyCap("esc", null, granted) {}; KeyCap("tab", null, granted) {}; KeyCap("ctrl", null, granted, selected = true) {}; KeyCap("alt", null, granted) {}
                    KeyCap("super", "Super key", granted) {}; KeyCap(null, "Left", granted, R.drawable.sym_arrow_back) {}; KeyCap(null, "Right", granted, R.drawable.sym_arrow_forward) {}
                } },
            )
        }
    }

    @Test fun deskGranted() = shoot("w5-desk-granted", controls(ControlMode.Touch, granted = true, soundOn = true))
    @Test fun deskWaiting() = shoot("w5-desk-waiting", controls(ControlMode.Touch, granted = false, reason = "Allow remote interaction on $desk"))
    @Test fun deskViewOnly() = shoot("w5-desk-view", controls(ControlMode.View, granted = true, fill = true))
    @Test fun deskKeyboardUp() = shoot("w5-desk-keys", controls(ControlMode.Trackpad, granted = true, keys = true))
    @Test fun deskHidden() = shoot("w5-desk-hidden", controls(ControlMode.Touch, granted = true, visible = false))
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class PhoneWave5Shots : Wave5Shots() { override val form = "phone" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class NarrowWave5Shots : Wave5Shots() { override val form = "narrow" }

/** A phone on its side: the desk screen's real orientation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-xxhdpi")
class LandscapeWave5Shots : Wave5Shots() { override val form = "landscape" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
class HugeTextWave5Shots : Wave5Shots() {
    override val form = "huge"
    @Before fun fontScale() = RuntimeEnvironment.setFontScale(2.0f)
}
