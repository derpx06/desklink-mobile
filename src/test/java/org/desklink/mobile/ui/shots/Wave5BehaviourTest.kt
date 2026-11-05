/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.shots

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.desklink.mobile.plugins.mousepad.ControlMode
import org.desklink.mobile.plugins.mousepad.DeskControls
import org.desklink.mobile.ui.compose.DeskLinkFixedTheme
import org.desklink.mobile.ui.compose.components.DeskDialogCard
import org.desklink.mobile.ui.compose.components.DialogAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The restyled chrome still does what it did: the desk screen's buttons are enabled exactly when control is granted, and the kit dialog's buttons call their actions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-xxhdpi")
class Wave5BehaviourTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun desk(granted: Boolean, mode: ControlMode = ControlMode.Touch, log: MutableList<String> = mutableListOf()) {
        compose.setContent {
            DeskLinkFixedTheme(compose.activity, true) {
                DeskControls(
                    visible = true, deviceName = "arch-desk", rttMs = 12, live = true, controlEnabled = granted, mode = mode,
                    onMode = {}, onBack = { log += "back" }, onEnd = { log += "end" }, onLeft = { log += "left" }, onRight = { log += "right" },
                    onKeyboard = { log += "keyboard" }, onSuper = { log += "super" }, onMore = {},
                    fill = false, onFill = { log += "fill" }, soundOn = false, onSound = { log += "sound" },
                )
            }
        }
    }

    @Test fun controlsAreEnabledOnlyWhenGranted() {
        desk(granted = true)
        for (label in listOf("Left click", "Right click", "Super key", "Keyboard")) compose.onNodeWithContentDescription(label).assertIsEnabled()
        compose.onNodeWithContentDescription("Edge to edge").assertIsEnabled()
    }

    @Test fun controlsAreDisabledUntilGranted() {
        desk(granted = false)
        for (label in listOf("Left click", "Right click", "Super key", "Keyboard")) compose.onNodeWithContentDescription(label).assertIsNotEnabled()
        // Viewing options never need control.
        compose.onNodeWithContentDescription("Edge to edge").assertIsEnabled()
        compose.onNodeWithContentDescription("Close remote control").assertIsEnabled()
    }

    @Test fun watchOnlyDisablesInput() {
        desk(granted = true, mode = ControlMode.View)
        compose.onNodeWithContentDescription("Left click").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Keyboard").assertIsNotEnabled()
    }

    @Test fun chipsCallTheirActions() {
        val log = mutableListOf<String>()
        desk(granted = true, log = log)
        compose.onNodeWithContentDescription("Edge to edge").performClick()
        compose.onNodeWithContentDescription("Play its sound here").performClick()
        compose.onNodeWithContentDescription("Close remote control").performClick()
        compose.onNodeWithText("End").performClick()
        assertEquals(listOf("fill", "sound", "back", "end"), log)
    }

    @Test fun terminalHeaderAnnouncesOnlyItsStatus() {
        compose.setContent {
            DeskLinkFixedTheme(compose.activity, true) {
                org.desklink.mobile.plugins.terminal.TerminalHeader("build", "src · arch-desk · 12 ms", "Reconnecting", "80x24", "Back", {}, {})
            }
        }
        val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
        compose.onAllNodes(live).assertCountEquals(1)
        compose.onNode(live).assertTextEquals("Reconnecting")
    }

    @Test fun dialogButtonsCallTheirActions() {
        val log = mutableListOf<String>()
        compose.setContent {
            DeskLinkFixedTheme(compose.activity, false) {
                DeskDialogCard("Unpair?", DialogAction("Unpair", destructive = true) { log += "confirm" }, DialogAction("Cancel") { log += "cancel" }, null)
            }
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Unpair").performClick()
        assertEquals(listOf("cancel", "confirm"), log)
    }

    @Test fun disabledConfirmDoesNothing() {
        val log = mutableListOf<String>()
        compose.setContent {
            DeskLinkFixedTheme(compose.activity, false) {
                DeskDialogCard("Password", DialogAction("Unlock", enabled = false) { log += "confirm" }, null, null)
            }
        }
        compose.onNodeWithText("Unlock").assertIsNotEnabled()
        assertEquals(emptyList<String>(), log)
    }
}
