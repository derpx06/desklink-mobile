/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.shots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import org.desklink.mobile.plugins.mousepad.ControlMode
import org.desklink.mobile.plugins.mousepad.DeskControls
import org.desklink.mobile.ui.compose.DeskLinkFixedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The walker behind every shot (`A11y.violations`) catches what it is for, and the desk screen's tool pill obeys the rules
 * the shots cannot show: narrow screens move Fit and Sound into More, and the mode switch reads left to right, one selected.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class A11yWalkerTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun flagsASmallUnnamedTarget() {
        compose.setContent { DeskLinkFixedTheme(compose.activity, false) { Column { androidx.compose.foundation.layout.Box(Modifier.size(30.dp).clickable {}) } } }
        val bad = A11y.violations(compose)
        assertTrue(bad.toString(), bad.any { it.startsWith("touch target") } && bad.any { it.startsWith("no name") })
    }

    @Test fun acceptsANamedFullSizeTarget() {
        compose.setContent { DeskLinkFixedTheme(compose.activity, false) { Text("Open", Modifier.size(48.dp).clickable {}) } }
        assertEquals(emptyList<String>(), A11y.violations(compose))
    }

    private fun desk() = compose.setContent {
        DeskLinkFixedTheme(compose.activity, true) {
            DeskControls(
                visible = true, deviceName = "arch-desk", rttMs = 12, live = true, controlEnabled = true, mode = ControlMode.Touch,
                onMode = {}, onBack = {}, onEnd = {}, onLeft = {}, onRight = {}, onKeyboard = {}, onMore = {},
            )
        }
    }

    @Test fun narrowScreenKeepsFitAndSoundInMore() {
        desk()
        compose.onNodeWithContentDescription("Edge to edge").assertDoesNotExist()
        compose.onNodeWithContentDescription("Desk sound").assertDoesNotExist()
        compose.onNodeWithContentDescription("Extra options").assertExists()
    }

    @Test fun modeSwitchReadsInOrderWithOneSelected() {
        desk()
        val radios = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("radio") { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }).fetchSemanticsNodes()
        val modes = radios.map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
        // Mirror/Extend come first (top of the screen), then Trackpad, Touch, View.
        assertEquals(listOf("Mirror", "Extend", "Trackpad", "Touch", "View"), modes)
        assertEquals(listOf(true, false), radios.take(2).map { it.config.getOrNull(SemanticsProperties.Selected) })
        assertEquals(listOf(false, true, false), radios.drop(2).map { it.config.getOrNull(SemanticsProperties.Selected) })
    }
}
