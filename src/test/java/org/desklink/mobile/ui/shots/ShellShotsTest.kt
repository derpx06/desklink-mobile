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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
