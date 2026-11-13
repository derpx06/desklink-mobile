/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.desklink.mobile.plugins.share.TransferRecord
import org.desklink.mobile.ui.compose.DeskLinkFixedTheme
import org.desklink.mobile.ui.compose.components.QuickActionTile
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.screen.home.ComputerRow
import org.desklink.mobile.ui.compose.screen.home.HomeActions
import org.desklink.mobile.ui.compose.screen.home.HomeScreen
import org.desklink.mobile.ui.compose.screen.home.HomeState
import org.desklink.mobile.ui.compose.screen.home.PaletteScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.desklink.mobile.ui.compose.screen.home.LiveSessions
import org.desklink.mobile.ui.compose.screen.onboarding.FinishSetupCard
import org.desklink.mobile.ui.compose.screen.settings.PermissionRow
import org.desklink.mobile.ui.compose.screen.tabs.TerminalsActions
import org.desklink.mobile.ui.compose.screen.tabs.TerminalsContent
import org.desklink.mobile.ui.compose.screen.tabs.TerminalsPanel
import org.desklink.mobile.webrtc.terminal.TerminalClientState
import org.desklink.mobile.webrtc.terminal.TerminalLinkState
import org.desklink.mobile.webrtc.terminal.TerminalSessionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What a tap on Home and on "Find anything" calls, with a recording HomeActions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class HomeInteractionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()
    private fun log(name: String): () -> Unit = { calls += name }

    private val record = TransferRecord("r1", "d1", "arch-desk", "invoice.pdf", 1000, false, System.currentTimeMillis(), "file:///x/invoice.pdf", null)

    private fun actions(computersRequest: Int = 0) = HomeActions(
        onSelectComputer = { calls += "select:$it" }, onPairAnother = log("pair"), onRetry = log("retry"), onEndLive = log("end"),
        onPlayPause = {}, onNext = {}, onPrevious = {}, onSeek = {}, onVolume = {}, onSelectPlayer = {},
        onDeskScreen = log("screen"), onSendFiles = log("files"), onSendLink = {}, onLock = log("lock"), onShareScreen = log("share"),
        onPing = log("ping"), onRing = log("ring"), onOpenFiles = log("openFiles"), onUnlockDesk = log("unlock"),
        onUnavailable = { calls += "unavailable" },
        onBlocked = { action, _, blocker -> calls += "blocked:$action:$blocker" },
        onNotes = {}, onNote = { calls += "note:$it" }, onNewNote = log("newNote"), onBucket = log("bucket"),
        openComputersRequest = computersRequest, onComputersShown = log("shown"),
        onSendClipboard = log("clipboard"), onCancelTransfer = {}, onOpenRecent = { calls += "open:${it.id}" },
        onTerminals = log("terminals"), onNewTerminal = log("newTerminal"), onOpenSettings = { calls += "settings:$it" },
        onMessage = {},
    )

    private val computers = listOf(ComputerRow("d1", "arch-desk", DeviceConnection.LOCAL), ComputerRow("d2", "work-laptop", DeviceConnection.OFFLINE))
    private fun state(block: HomeState.() -> HomeState = { this }) =
        HomeState("d1", "arch-desk", DeviceConnection.LOCAL, computers, history = listOf(record)).block()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { DeskLinkFixedTheme(compose.activity, false) { content() } }
        compose.waitForIdle()
    }

    private fun tap(text: String, scroll: Boolean = true) {
        val node = compose.onNodeWithText(text)
        (if (scroll) node.performScrollTo() else node).performClick()
    }

    @Test
    fun everyPaletteRowCallsItsAction() {
        val a = actions()
        show { PaletteScreen(state { copy(canUnlock = true) }, a, onClose = { calls += "close" }, onSendLink = log("link")) }
        val expected = listOf(
            "Send files" to "files", "Send clipboard" to "clipboard", "Send a link" to "link", "Desk screen" to "screen",
            "New terminal" to "newTerminal", "Open the terminals" to "terminals", "New note" to "newNote", "Open the Bucket" to "bucket",
            "Ring computer" to "ring", "Ping computer" to "ping", "Lock computer" to "lock", "Unlock arch-desk" to "unlock",
            "Share screen" to "share", "Pair another computer" to "pair",
            "invoice.pdf" to "open:r1", "Connection" to "settings:connection", "work-laptop" to "select:d2",
        )
        for ((text, call) in expected) {
            calls.clear()
            tap(text)
            assertEquals("tapping \"$text\"", listOf("close", call), calls)
        }
    }

    @Test
    fun theComputerAlreadyShownIsNotSelectedAgain() {
        show { PaletteScreen(state(), actions(), onClose = { calls += "close" }, onSendLink = {}) }
        tap("arch-desk")
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun aComputerThatNeedsAnUpdateIsNotSentAnything() {
        show { HomeScreen(state { copy(updateNeeded = true) }, actions()) }
        tap("Clipboard")
        tap("Send files")
        // Said why (with the fix), never sent.
        assertEquals(listOf("blocked:action:send-clipboard:UPDATE", "blocked:action:send-files:UPDATE"), calls)
    }

    @Test
    fun aReadyComputerIsSent() {
        show { HomeScreen(state(), actions()) }
        tap("Clipboard")
        assertEquals(listOf("clipboard"), calls)
    }

    @Test
    fun aDisabledQuickActionTileSaysWhy() {
        show {
            QuickActionTile(org.desklink.mobile.R.drawable.sym_lock, "Lock desk", onClick = log("click"), enabled = false, onDisabledClick = log("why"))
        }
        tap("Lock desk", scroll = false)
        assertEquals(listOf("why"), calls)
    }

    @Test
    fun aDisabledQuickActionTileWithoutAReasonDoesNothing() {
        show { QuickActionTile(org.desklink.mobile.R.drawable.sym_lock, "Lock desk", onClick = log("click"), enabled = false) }
        tap("Lock desk", scroll = false)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun theComputersRequestIsTakenOnce() {
        show { HomeScreen(state(), actions(computersRequest = 1)) }
        assertEquals(listOf("shown"), calls)
    }

    @Test
    fun stoppingThisPhonesScreenShareWorksWithTheLinkDown() {
        // Not usable (an update is needed), but ending the capture here is local: it runs, nothing else does.
        show { HomeScreen(state { copy(updateNeeded = true, live = LiveSessions(phoneScreen = true)) }, actions()) }
        tap("Stop sharing")
        tap("Clipboard")
        assertEquals(listOf("share", "blocked:action:send-clipboard:UPDATE"), calls)
    }

    @Test
    fun theFinishSetupCountIsTheRowsItLists() {
        val rows = listOf("notifications", "camera", "notification_access", "calls", "battery", "oem").map { id ->
            PermissionRow(org.desklink.mobile.R.drawable.sym_info, id, "s", granted = false, unknown = id == "oem", id = id) { }
        }
        show { FinishSetupCard(rows, expanded = true, onExpanded = {}, onAllowAll = {}) }
        compose.onNodeWithText("6 permissions left", substring = true).assertExists()
        rows.forEach { compose.onNodeWithText(it.title).assertExists() }
    }

    private fun finishedSession() = TerminalClientState(
        link = TerminalLinkState.CONNECTED, listLoaded = true,
        sessions = listOf(TerminalSessionState("s1", "", 24, 80, 0, false, false, true, cwd = "~", shell = "zsh")),
    )

    @Test
    fun restartIsIgnoredWhileATerminalIsStarting() {
        val restart = TerminalsActions(onRestart = { calls += "restart:${it.id}" })
        var busy by androidx.compose.runtime.mutableStateOf(true)
        show { TerminalsContent("arch-desk", TerminalsPanel.LIST, finishedSession(), emptyMap(), emptyMap(), restart, busy = busy) }
        tap("Restart")
        assertEquals("a second tap while the first restart starts does nothing", emptyList<String>(), calls)
        busy = false
        compose.waitForIdle()
        tap("Restart")
        assertEquals(listOf("restart:s1"), calls)
    }
}
