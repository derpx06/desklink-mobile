/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.shots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performClick
import org.desklink.mobile.ui.compose.DeskLinkFixedTheme
import org.desklink.mobile.ui.compose.components.HeaderCollapse
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.shell.AppTab
import org.desklink.mobile.ui.compose.shell.TabBadges
import org.desklink.mobile.ui.compose.shell.TabNav
import org.desklink.mobile.ui.compose.shell.TabsShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The polish pass's behaviour: tabs still switch (neighbour and far jump), and the folding title follows the finger. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
class PolishBehaviourTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shell(nav: TabNav) = compose.setContent {
        DeskLinkFixedTheme(compose.activity, false) {
            TabsShell(nav, TabBadges(DeviceConnection.LOCAL), onQuickAction = {}) { tab ->
                Box(Modifier.fillMaxSize(), Alignment.Center) { Text("page ${tab.name}") }
            }
        }
    }

    @Test fun aNeighbouringTabAndAFarTabBothOpen() {
        val nav = TabNav(AppTab.HOME)
        shell(nav)
        compose.onNodeWithContentDescription("Files", substring = true).performClick()
        compose.waitUntil(5_000) { nav.selected == AppTab.FILES }
        compose.onNodeWithText("page FILES").assertExists()
        compose.onNodeWithContentDescription("Settings", substring = true).performClick()
        compose.waitUntil(5_000) { nav.selected == AppTab.SETTINGS }
        compose.waitForIdle()
        compose.onNodeWithText("page SETTINGS").assertExists()
        compose.onNodeWithContentDescription("Settings", substring = true).assertIsSelected()
    }

    @Test fun theTitleFoldsBeforeTheListMovesAndUnfoldsAtTheTop() {
        val c = HeaderCollapse(100f)
        val up = c.connection.onPreScroll(Offset(0f, -60f), NestedScrollSource.UserInput)
        assertEquals("the header took the finger first", -60f, up.y, 0.01f)
        assertEquals(0.6f, c.fraction, 0.01f)
        val rest = c.connection.onPreScroll(Offset(0f, -80f), NestedScrollSource.UserInput)
        assertEquals("only what is left of the fold", -40f, rest.y, 0.01f)
        assertEquals(1f, c.fraction, 0.001f)
        assertEquals("fully folded: the list scrolls", 0f, c.connection.onPreScroll(Offset(0f, -10f), NestedScrollSource.UserInput).y, 0f)
        // Back down: the list goes first (pre-scroll ignores it), the fold opens with what the list could not take.
        assertEquals(0f, c.connection.onPreScroll(Offset(0f, 30f), NestedScrollSource.UserInput).y, 0f)
        val back = c.connection.onPostScroll(Offset.Zero, Offset(0f, 30f), NestedScrollSource.UserInput)
        assertEquals(30f, back.y, 0.01f)
        assertEquals(0.7f, c.fraction, 0.01f)
        assertTrue(c.connection.onPostScroll(Offset.Zero, Offset(0f, 500f), NestedScrollSource.UserInput).y <= 70.01f)
        assertEquals(0f, c.fraction, 0.001f)
    }
}
