/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.shots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import org.desklink.mobile.QrPairing
import org.desklink.mobile.R
import org.desklink.mobile.ui.QrScanFlow
import org.desklink.mobile.ui.compose.screen.onboarding.OnboardingScreen
import org.desklink.mobile.ui.compose.screen.onboarding.PermissionsChecklistScreen
import org.desklink.mobile.ui.compose.screen.onboarding.SplashScreen
import org.desklink.mobile.ui.compose.screen.pairing.InstallLinkContent
import org.desklink.mobile.ui.compose.screen.pairing.NearbyComputer
import org.desklink.mobile.ui.compose.screen.pairing.PairCodeScreen
import org.desklink.mobile.ui.compose.screen.pairing.PairCodeState
import org.desklink.mobile.ui.compose.screen.pairing.PairStage
import org.desklink.mobile.ui.compose.screen.pairing.ScanScreen
import org.desklink.mobile.ui.compose.screen.pairing.WelcomeScreen
import org.desklink.mobile.ui.compose.screen.settings.PermissionRow
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.screen.home.ComputerRow
import org.desklink.mobile.ui.compose.screen.home.HomeActions
import org.desklink.mobile.ui.compose.screen.home.HomeScreen
import org.desklink.mobile.ui.compose.screen.home.HomeState
import org.desklink.mobile.ui.compose.tokens
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The first-run screens, light and dark: splash, the five tour pages, welcome, scan, compare codes and the permissions checklist.
 * `./gradlew testDebugUnitTest --tests "*FirstRunShots*"`; images land in `~/.cache/android-ui/shots/` as `<form>-fr-<screen>-<theme>.png`.
 */
abstract class FirstRunShots : ShotTest() {
    private fun tour(page: Int) = @Composable { OnboardingScreen(page, {}, paired = false, onFinish = {}, onSkip = {}) }

    @Test fun splash() = shoot("fr-splash") { SplashScreen("arch-desk") }
    @Test fun tour1() = shoot("fr-tour-1", tour(0))
    @Test fun tour2() = shoot("fr-tour-2", tour(1))
    @Test fun tour3() = shoot("fr-tour-3", tour(2))
    @Test fun tour4() = shoot("fr-tour-4", tour(3))
    @Test fun tour5() = shoot("fr-tour-5", tour(4))
    @Test fun tourReplayPaired() = shoot("fr-tour-5-replay") { OnboardingScreen(4, {}, paired = true, onFinish = {}, onSkip = {}) }

    @Test fun homeNotificationsNudge() = shoot("fr-home-nudge") {
        val state = HomeState("d1", "arch-desk", DeviceConnection.LOCAL, listOf(ComputerRow("d1", "arch-desk", DeviceConnection.LOCAL)), rttMs = 12, notificationsOff = true)
        HomeScreen(state, HomeActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}))
    }

    @Test fun welcome() = shoot("fr-welcome") { WelcomeScreen(onPair = {}, onSettings = {}) }

    @Test fun installSheet() = shoot("fr-install-sheet") {
        Box(androidx.compose.ui.Modifier.fillMaxSize().background(tokens.surface2)) { InstallLinkContent() }
    }

    private val nearby = listOf(NearbyComputer("d1", "arch-desk"))

    @Test fun scanNoCamera() = shoot("fr-scan-no-camera") {
        ScanScreen(nearby, camera = false, onAllowCamera = {}, onCode = {}, onPairNearby = {}, onClose = {})
    }

    @Test fun scanNoCameraNobodyNearby() = shoot("fr-scan-alone") {
        ScanScreen(emptyList(), camera = false, onAllowCamera = {}, onCode = {}, onPairNearby = {}, onClose = {})
    }

    @Test fun scanStopped() = shoot("fr-scan-stopped") {
        ScanScreen(nearby, camera = true, scanning = false, stopped = QrScanFlow.State.Stopped("That is not a DeskLink code. Look for the one under Pair a phone."), onAllowCamera = {}, onCode = {}, onPairNearby = {}, onClose = {})
    }

    @Test fun compareCodes() = shoot("fr-codes") {
        PairCodeScreen(PairCodeState("arch-desk", "482913", PairStage.ASKED), {}, {}, {})
    }

    @Test fun compareCodesWaiting() = shoot("fr-codes-waiting") {
        PairCodeScreen(PairCodeState("arch-desk", "482913", PairStage.WAITING), {}, {}, {})
    }

    @Test fun compareCodesRefused() = shoot("fr-codes-refused") {
        PairCodeScreen(PairCodeState("arch-desk", null, PairStage.IDLE, "arch-desk said no. Try again, and accept the request on your computer."), {}, {}, {})
    }

    private fun row(icon: Int, title: String, summary: String, granted: Boolean, unknown: Boolean = false) =
        PermissionRow(icon, title, summary, granted, unknown) { }

    private val perms = listOf(
        row(R.drawable.sym_notifications_active, "Notifications", "Transfers, pairing and live sessions", granted = true),
        row(R.drawable.sym_notifications, "Notification access", "Mirror and reply from arch-desk", granted = false),
        row(R.drawable.sym_person, "Contacts", "Share contacts and caller names with arch-desk", granted = false),
        row(R.drawable.sym_battery_full, "Unrestricted battery", "Stay reachable in the background", granted = false),
        row(R.drawable.sym_restart_alt, "Keep DeskLink running", "This phone stops apps in the background. Allow background activity and auto-launch so arch-desk stays connected.", granted = false, unknown = true),
        row(R.drawable.sym_accessibility_new, "Accessibility", "Let arch-desk control this phone", granted = false),
    )

    @Test fun permissions() = shoot("fr-permissions") { PermissionsChecklistScreen("arch-desk", perms) {} }

    @Test fun permissionsAllOn() = shoot("fr-permissions-done") {
        PermissionsChecklistScreen("arch-desk", perms.map { PermissionRow(it.icon, it.title, it.summary, true) { } }) {}
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
class PhoneFirstRunShots : FirstRunShots() { override val form = "phone" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
class NarrowFirstRunShots : FirstRunShots() { override val form = "narrow" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
class HugeTextFirstRunShots : FirstRunShots() {
    override val form = "huge"
    @Before fun fontScale() = RuntimeEnvironment.setFontScale(2.0f)
}
