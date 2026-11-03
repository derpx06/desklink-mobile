/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.shots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.shell.AppTab
import org.desklink.mobile.ui.compose.shell.TabBadges
import org.desklink.mobile.ui.compose.shell.TabNav
import org.desklink.mobile.ui.compose.shell.TabsShell
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
class PhoneShellShots : ShotTest() {
    override val form = "phone"

    @Test
    fun tabsWithEveryBadge() = shoot("shell-badges") {
        TabsShell(
            TabNav(AppTab.FILES), TabBadges(DeviceConnection.LOCAL, filesProgress = 0.42f, filesBusy = true, terminalsAttention = true, settingsAttention = true),
            onQuickAction = {},
        ) { tab -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(tab.name) } }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h700dp-xhdpi")
class TabletShellShots : ShotTest() {
    override val form = "tablet"

    @Test
    fun railWithBadges() = shoot("shell-badges") {
        TabsShell(
            TabNav(AppTab.HOME), TabBadges(DeviceConnection.CONNECTING, filesBusy = true, terminalsAttention = true),
            onQuickAction = {},
        ) { tab -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(tab.name) } }
    }
}

/** 320 dp wide, system font at 200 %: all five tabs are still on screen, side by side, each at least 48 dp tall. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
class HugeTextShellShots : ShotTest() {
    override val form = "huge"

    @Before fun fontScale() = RuntimeEnvironment.setFontScale(2.0f)

    @Test
    fun everyTabStaysOnScreen() {
        shoot("shell-badges") {
            TabsShell(
                TabNav(AppTab.HOME), TabBadges(DeviceConnection.LOCAL, filesBusy = true, terminalsAttention = true, settingsAttention = true),
                onQuickAction = {},
            ) { tab -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(tab.name) } }
        }
        val tabs = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes()
        assertEquals(AppTab.entries.size, tabs.size)
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        var previousRight = 0f
        for (node in tabs.sortedBy { it.boundsInRoot.left }) {
            val b = node.boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            assertTrue("a tab starts left of the screen: $b", b.left >= -0.5f)
            assertTrue("a tab ends right of the screen: $b", b.right <= width + 0.5f)
            assertTrue("tabs overlap: $b after $previousRight", b.left >= previousRight - 0.5f)
            assertTrue("a tab is under 48 dp tall: $b", b.height >= 48 * density - 0.5f)
            previousRight = b.right
        }
    }
}
