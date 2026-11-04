/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.shots

import androidx.compose.runtime.mutableStateOf
import org.desklink.mobile.plugins.desksettings.DeskSettingsState
import org.desklink.mobile.plugins.folders.BucketUi
import org.desklink.mobile.plugins.notes.NotesUi
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.screen.home.ComputerRow
import org.desklink.mobile.ui.compose.screen.home.HomeActions
import org.desklink.mobile.ui.compose.screen.home.HomeScreen
import org.desklink.mobile.ui.compose.screen.home.HomeState
import org.desklink.mobile.ui.compose.screen.home.HomeTransfer
import org.desklink.mobile.ui.compose.screen.home.LiveSessions
import org.desklink.mobile.ui.compose.screen.settings.DeskSettings
import org.desklink.mobile.ui.compose.screen.settings.SettingsActions
import org.desklink.mobile.ui.compose.screen.settings.SettingsPage
import org.desklink.mobile.ui.compose.screen.settings.SettingsTab
import org.desklink.mobile.ui.compose.screen.tabs.FilesTab
import org.desklink.mobile.ui.compose.screen.tabs.NotesTab
import org.desklink.mobile.ui.compose.screen.tabs.TerminalsTab
import org.desklink.mobile.ui.compose.screen.transfers.TransferActions
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every screen, light and dark, at three sizes: a 412 dp phone, a 360 dp phone (the narrowest we design for)
 * and a tablet. Run with `./gradlew testDebugUnitTest --tests "*ScreenShots*"`; the images land in
 * `~/.cache/android-ui/shots/`. They are for review, not assertions: nothing here compares pixels.
 */
abstract class ScreenShots : ShotTest() {
    private val desk = "arch-desk"

    private val noopHome = HomeActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    private val computers = listOf(ComputerRow("d1", desk, DeviceConnection.LOCAL), ComputerRow("d2", "work-laptop", DeviceConnection.OFFLINE))

    private fun home(connection: DeviceConnection, block: HomeState.() -> HomeState = { this }) =
        HomeState("d1", desk, connection, computers).block()

    private fun record(i: Int, name: String, sent: Boolean, minutesAgo: Long, size: Long) =
        org.desklink.mobile.plugins.share.TransferRecord("r$i", "d1", desk, name, size, sent, System.currentTimeMillis() - minutesAgo * 60_000, "file:///x/$name", null)

    private val sampleFiles = listOf(
        record(1, "invoice-october.pdf", sent = false, minutesAgo = 4, size = 482_000),
        record(2, "holiday-photo.jpg", sent = true, minutesAgo = 95, size = 3_400_000),
        record(3, "notes.txt", sent = false, minutesAgo = 60 * 30, size = 2_100),
    )

    @Test fun homeConnected() = shoot("home-connected") {
        HomeScreen(
            home(DeviceConnection.LOCAL) {
                copy(rttMs = 12, historyCount = 7, clipboardOn = true, recent = sampleFiles, clipboardPreview = "https://example.com/a/long/address/that/goes/on", deskNotifications = 3)
            },
            noopHome,
        )
    }

    @Test fun homeLiveAndTransferring() = shoot("home-live") {
        HomeScreen(
            home(DeviceConnection.AWAY) {
                copy(
                    live = LiveSessions(screen = true, terminals = 2), transfer = HomeTransfer("holiday-photos.zip", "42%", 0.42f, upload = true),
                    historyCount = 3, recent = sampleFiles.take(2), clipboardOn = true,
                )
            },
            noopHome,
        )
    }

    @Test fun homeNotPaired() = shoot("home-not-paired") {
        org.desklink.mobile.ui.compose.screen.pairing.WelcomeScreen(onPair = {}, onSettings = {})
    }

    @Test fun homeConnecting() = shoot("home-connecting") { HomeScreen(home(DeviceConnection.CONNECTING), noopHome) }

    @Test fun homeCannotConnect() = shoot("home-cannot-connect") { HomeScreen(home(DeviceConnection.OFFLINE), noopHome) }

    // ---- settings

    private fun deskSettings(ready: Boolean = true) = DeskSettings(
        id = "d1", name = desk, clipboardOn = true, notificationsOn = true, key = "3C:91:0B:7E",
        remote = DeskSettingsState(keepAwake = true, clipboard = true, notifications = true, relayConfigured = true, relay = false, commandAlerts = true, unlockNearby = false, sessionPasswordSet = true),
        callsOn = true, deskSoundOn = true, deskSoundVolume = 70, glideOn = true, contactsOn = true, ready = ready,
    )

    private val noopSettings = SettingsActions(onPhoneClipboard = {}, onPhoneNotifications = {}, onDeskSetting = { _, _ -> }, onPhoneMedia = {}, onUnpair = {}, onEncryptionInfo = {}, onRecreate = {}, onMessage = {})

    private fun settings(route: String?) = @androidx.compose.runtime.Composable {
        SettingsTab(deskSettings(), noopSettings, route, onOpen = {}, onBack = {}, waitingPermissions = 2, about = {})
    }

    @Test fun settingsList() = shoot("settings-list", settings(null))

    @Test fun settingsEveryGroup() {
        // One composition per page: a rule sets its content once, so each page is its own test method run in turn.
        SettingsPage.entries.forEach { page -> check(page.route.isNotEmpty()) }
    }

    @Test fun settingsConnection() = shoot("settings-connection", settings("connection"))
    @Test fun settingsNotifications() = shoot("settings-notifications", settings("notifications"))
    @Test fun settingsClipboard() = shoot("settings-clipboard", settings("clipboard"))
    @Test fun settingsCalls() = shoot("settings-calls", settings("calls"))
    @Test fun settingsSound() = shoot("settings-sound", settings("sound"))
    @Test fun settingsControl() = shoot("settings-control", settings("control"))
    @Test fun settingsFiles() = shoot("settings-files", settings("files"))
    @Test fun settingsPrivacy() = shoot("settings-privacy", settings("privacy"))
    @Test fun settingsAppearance() = shoot("settings-appearance", settings("appearance"))
    @Test fun settingsAdvanced() = shoot("settings-advanced", settings("advanced"))
    @Test fun settingsPermissions() = shoot("settings-permissions", settings(SettingsPage.PERMISSIONS))

    // ---- the other tabs

    @Test fun filesTab() = shoot("files") {
        FilesTab(
            BucketUi(compose.activity), "d1", desk, live = null, records = emptyList(), revision = 0,
            actions = TransferActions({}, {}, {}, {}, {}, {}, {}),
        )
    }

    @Test fun notesTab() = shoot("notes") {
        NotesTab(androidx.compose.runtime.remember { NotesUi(compose.activity, mutableStateOf(null)) }, compose.activity, desk, clipboardOn = true, onClipboard = {}, onSendClipboard = {}, onMessage = {})
    }

    @Test fun terminalsTab() = shoot("terminals") { TerminalsTab("d1", desk, onOpenSession = {}, onOpenTerminals = {}, onNew = {}) }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class PhoneScreenShots : ScreenShots() { override val form = "phone" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class NarrowScreenShots : ScreenShots() { override val form = "narrow" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h700dp-xhdpi")
class TabletScreenShots : ScreenShots() { override val form = "tablet" }

/** A phone on its side: wide enough for two columns, not tall. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-xxhdpi")
class LandscapeScreenShots : ScreenShots() { override val form = "landscape" }

/** The narrowest phone at the largest system font size: where a label gets cut or a row spills over its neighbour. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
class HugeTextScreenShots : ScreenShots() {
    override val form = "huge"

    @Before fun fontScale() = RuntimeEnvironment.setFontScale(2.0f)
}
