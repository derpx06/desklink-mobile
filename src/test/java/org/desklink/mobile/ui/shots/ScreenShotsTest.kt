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
import org.desklink.mobile.ui.compose.screen.home.PaletteScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
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
                    historyCount = 3, recent = sampleFiles.take(2), clipboardOn = true, terminalAttention = true,
                )
            },
            noopHome,
        )
    }

    @Test fun homeTransferOnly() = shoot("home-transfer") {
        HomeScreen(
            home(DeviceConnection.LOCAL) {
                copy(rttMs = 9, transfer = HomeTransfer("holiday-photos.zip", "42% · fast lane", 0.42f, upload = false), historyCount = 7, recent = sampleFiles, clipboardOn = true)
            },
            noopHome,
        )
    }

    @Test fun homeNoFilesYet() = shoot("home-no-files") { HomeScreen(home(DeviceConnection.LOCAL) { copy(rttMs = 12) }, noopHome) }

    @Test fun homeNotPaired() = shoot("home-not-paired") {
        org.desklink.mobile.ui.compose.screen.pairing.WelcomeScreen(onPair = {}, onSettings = {})
    }

    @Test fun homeConnecting() = shoot("home-connecting") { HomeScreen(home(DeviceConnection.CONNECTING), noopHome) }

    @Test fun homeCannotConnect() = shoot("home-cannot-connect") { HomeScreen(home(DeviceConnection.OFFLINE) { copy(recent = sampleFiles.take(2), historyCount = 2) }, noopHome) }

    @Test fun homeCannotConnectRelayOn() = shoot("home-offline-relay") {
        HomeScreen(home(DeviceConnection.UNAVAILABLE) { copy(relayAvailable = true, updateNeeded = true) }, noopHome)
    }

    // ---- Find anything, the computers sheet

    private fun palette(query: String) = @androidx.compose.runtime.Composable {
        PaletteScreen(
            home(DeviceConnection.LOCAL) { copy(history = sampleFiles, recent = sampleFiles, canUnlock = true) },
            noopHome, onClose = {}, onSendLink = {}, initialQuery = query,
        )
    }

    @Test fun paletteIdle() = shoot("palette", palette(""))
    @Test fun paletteQuery() = shoot("palette-query", palette("o"))
    @Test fun paletteNothing() = shoot("palette-nothing", palette("zzzq"))

    @Test fun paletteWhileOffline() = shoot("palette-offline") {
        PaletteScreen(home(DeviceConnection.OFFLINE), noopHome, onClose = {}, onSendLink = {})
    }

    @Test fun computersSheet() = shoot("sheet-computers") {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
            HomeScreen(home(DeviceConnection.LOCAL) { copy(rttMs = 12) }, noopHome)
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f)))
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth()
                    .background(org.desklink.mobile.ui.compose.tokens.surface2, org.desklink.mobile.ui.compose.DeskLinkShape.sheet),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.padding(top = 16.dp, bottom = 12.dp).size(width = 32.dp, height = 4.dp)
                        .background(org.desklink.mobile.ui.compose.tokens.hairlineStrong, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                )
                org.desklink.mobile.ui.compose.screen.home.ComputersContent(
                    home(DeviceConnection.LOCAL), {}, noopHome,
                )
            }
        }
    }

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

    private val busyFiles = sampleFiles + listOf(
        record(4, "dataset-2026.zip", sent = true, minutesAgo = 60 * 27, size = 812_000_000),
        record(5, "budget.xlsx", sent = false, minutesAgo = 60 * 28, size = 52_000),
    )

    @Test fun filesTabBusy() = shoot("files-busy") {
        FilesTab(
            BucketUi(compose.activity), "d1", desk,
            live = org.desklink.mobile.ui.compose.screen.transfers.LiveTransfer("dataset-2026.zip", true, 0.64f, "64% · 38 MiB/s · fast, over Wi-Fi"),
            records = busyFiles, revision = 0, actions = TransferActions({}, {}, {}, {}, {}, {}, {}),
            savedIn = "the Downloads folder",
            unsaved = listOf(org.desklink.mobile.plugins.share.UnpublishedFiles.Item("u1", "report.pdf", 1_000_000, null, null, "d1", java.io.File("report.pdf"), storageFull = true)),
            sendAgain = listOf(org.desklink.mobile.plugins.share.SendAgain.Item("s1", "d1", "holiday.mov", unreadable = false, at = 0L)),
        )
    }

    @Test fun filesTabInternet() = shoot("files-internet") {
        FilesTab(
            BucketUi(compose.activity), "d1", desk,
            live = org.desklink.mobile.ui.compose.screen.transfers.LiveTransfer("holiday-photos.zip", false, null, "Saving…"),
            records = busyFiles.take(2), revision = 0, actions = TransferActions({}, {}, {}, {}, {}, {}, {}),
        )
    }

    @Test fun notesTabFilled() {
        // The store lives for the whole test JVM: add each sample once, however many classes run this.
        fun seed(title: String, body: String) {
            if (org.desklink.mobile.plugins.notes.NotesStore.notes(compose.activity).none { it.title == title }) {
                org.desklink.mobile.plugins.notes.NotesStore.newNote(compose.activity, title, body)
            }
        }
        seed("Release 1.4 checklist", "- [x] Tag the build\n- [x] Update the changelog\n- [ ] Ship to the beta\n- [ ] Tell the team")
        seed("Wi-Fi at the flat", "Network DeskLink-5G, the password is on the fridge, router in the hall cupboard.")
        seed("Ideas", "Mirror the phone screen to the desk when charging.")
        org.desklink.mobile.plugins.notes.NotesStore.pin(compose.activity, "git push origin main")
        shoot("notes-filled") {
            NotesTab(androidx.compose.runtime.remember { NotesUi(compose.activity, mutableStateOf(null)) }, compose.activity, desk, clipboardOn = true, onClipboard = {}, onSendClipboard = {}, onMessage = {})
        }
    }

    @Test fun notesTab() = shoot("notes") {
        NotesTab(androidx.compose.runtime.remember { NotesUi(compose.activity, mutableStateOf(null)) }, compose.activity, desk, clipboardOn = true, onClipboard = {}, onSendClipboard = {}, onMessage = {})
    }

    @Test fun terminalCards() = shoot("terminal-cards") {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.fillMaxSize().background(org.desklink.mobile.ui.compose.tokens.bg).padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            org.desklink.mobile.ui.compose.components.TerminalTile(
                org.desklink.mobile.ui.compose.components.SessionPreview("s1", "Claude Code", "CC", true, listOf("Do you want to proceed?", "❯ 1. Yes"), "80×38", waiting = true), androidx.compose.ui.Modifier.fillMaxWidth(),
            ) {}
            org.desklink.mobile.ui.compose.components.TerminalTile(
                org.desklink.mobile.ui.compose.components.SessionPreview("s2", "Shell", ">_", true, listOf("BUILD SUCCESSFUL in 42s", "$ "), "80×24"), androidx.compose.ui.Modifier.fillMaxWidth(),
            ) {}
        }
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
