/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.shots

import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.screen.home.ComputerRow
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.screen.home.HomeActions
import org.desklink.mobile.ui.compose.screen.home.HomeScreen
import org.desklink.mobile.ui.compose.screen.home.HomeState
import org.desklink.mobile.ui.compose.screen.onboarding.FinishSetupCard
import org.desklink.mobile.ui.compose.screen.settings.PermissionRow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Home with "Finish setting up" showing, connected and offline, on a phone (one column) and a tablet (two columns). */
abstract class HomeSetupShots : ShotTest() {
    private val rows = listOf(
        PermissionRow(R.drawable.sym_notifications, "Notification access", "Mirror and reply from your computer", false, id = "notification_access") { },
        PermissionRow(R.drawable.sym_call, "Calls", "Show, answer and dial calls on your computer", false, id = "calls", permissions = listOf("p")) { },
    )
    private val actions = HomeActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    private fun state(c: DeviceConnection) = HomeState("d1", "arch-desk", c, listOf(ComputerRow("d1", "arch-desk", c)), rttMs = 12)

    @Test fun connected() = shoot("home-setup-connected") {
        HomeScreen(state(DeviceConnection.LOCAL), actions) { FinishSetupCard(rows, expanded = false, onExpanded = {}, onAllowAll = {}) }
    }

    /** Home's search for an everyday word: Glide is found by "mouse" with the actions and settings that mean the same. */
    @Test fun searchMouse() = shoot("palette-mouse") {
        org.desklink.mobile.ui.compose.screen.home.PaletteScreen(state(DeviceConnection.LOCAL), actions, onClose = {}, onSendLink = {}, initialQuery = "mouse")
    }

    /** A feature's state and step ("Off. Turn it on…") are shown whole, under its name. */
    @Test fun searchGlide() = shoot("palette-glide") {
        org.desklink.mobile.ui.compose.screen.home.PaletteScreen(state(DeviceConnection.LOCAL), actions, onClose = {}, onSendLink = {}, initialQuery = "glide")
    }

    @Test fun searchGlideLargeText() = shoot("palette-glide-1.3") {
        val d = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, 1.3f)) {
            org.desklink.mobile.ui.compose.screen.home.PaletteScreen(state(DeviceConnection.LOCAL), actions, onClose = {}, onSendLink = {}, initialQuery = "glide")
        }
    }

    @Test fun offline() = shoot("home-setup-offline") {
        HomeScreen(state(DeviceConnection.OFFLINE), actions) { FinishSetupCard(rows, expanded = false, onExpanded = {}, onAllowAll = {}) }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h1300dp-xxhdpi")
class PhoneHomeSetupShots : HomeSetupShots() { override val form = "phone" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w840dp-h1100dp-xhdpi")
class TabletHomeSetupShots : HomeSetupShots() { override val form = "tablet" }
