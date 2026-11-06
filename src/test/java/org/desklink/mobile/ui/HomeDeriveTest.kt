/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import org.desklink.mobile.plugins.share.TransferRecord
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.screen.home.HomeDerive
import org.desklink.mobile.ui.compose.screen.home.HomeTransfer
import org.desklink.mobile.ui.compose.screen.home.HomeState
import org.desklink.mobile.ui.compose.screen.home.LiveSessions
import org.desklink.mobile.ui.compose.screen.home.NotReady
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Home's Recent, Happening now and tile areas show for a given state. */
class HomeDeriveTest {
    private fun state(connection: DeviceConnection = DeviceConnection.LOCAL, block: HomeState.() -> HomeState = { this }) =
        HomeState("d1", "arch-desk", connection, emptyList()).block()

    private fun record(i: Int) = TransferRecord("r$i", "d1", "arch-desk", "file$i.txt", 10L * i, i % 2 == 0, 1000L * i, "file:///f$i", null)

    @Test fun recentFilesAreTheFirstThreeOfTheNewestFirstList() {
        val history = (5 downTo 1).map(::record)
        assertEquals(listOf("r5", "r4", "r3"), HomeDerive.recentFiles(history).map { it.id })
        assertEquals(2, HomeDerive.recentFiles(history.take(2)).size)
        assertTrue(HomeDerive.recentFiles(emptyList()).isEmpty())
    }

    @Test fun theSameFileSentOrReceivedAgainIsOneRecentCard() {
        val again = listOf(1, 2, 3, 4).map { TransferRecord("x$it", "d1", "arch-desk", "dl-300m.bin", 300L, false, 1000L * it, "file:///f$it", null) }
        val other = record(9)
        val shown = HomeDerive.recentFiles(again + other)
        assertEquals(listOf("x1", "r9"), shown.map { it.id })
        // sent and received are different things, and so are two sizes
        assertEquals(2, HomeDerive.recentFiles(listOf(again[0], again[1].copy(sent = true))).size)
    }

    @Test fun seeAllShowsOnlyWhenThereIsMoreThanTheRowsShown() {
        val history = (5 downTo 1).map(::record)
        assertTrue(HomeDerive.moreFiles(state { copy(historyCount = 5, recent = HomeDerive.recentFiles(history)) }))
        assertFalse(HomeDerive.moreFiles(state { copy(historyCount = 3, recent = HomeDerive.recentFiles(history.take(3))) }))
    }

    @Test fun theClipboardPreviewIsOneShortLine() {
        assertNull(HomeDerive.clipboardPreview(null))
        assertNull(HomeDerive.clipboardPreview(" \n\t "))
        assertEquals("hello world", HomeDerive.clipboardPreview("  hello\n\n  world \t"))
        val long = "x".repeat(200)
        val shown = HomeDerive.clipboardPreview(long)!!
        assertEquals(HomeDerive.PREVIEW_CHARS, shown.length)
        assertTrue(shown.endsWith("…"))
        assertEquals("a".repeat(HomeDerive.PREVIEW_CHARS), HomeDerive.clipboardPreview("a".repeat(HomeDerive.PREVIEW_CHARS)))
    }

    @Test fun actionsSayWhyTheyAreOff() {
        assertNull(HomeDerive.whyNot(state(DeviceConnection.LOCAL)))
        assertNull(HomeDerive.whyNot(state(DeviceConnection.AWAY)))
        assertEquals(NotReady.CONNECTING, HomeDerive.whyNot(state(DeviceConnection.CONNECTING)))
        assertEquals(NotReady.CANT_REACH, HomeDerive.whyNot(state(DeviceConnection.UNAVAILABLE)))
        assertEquals(NotReady.OFFLINE, HomeDerive.whyNot(state(DeviceConnection.OFFLINE)))
        // A version mismatch wins over everything: the person must update, not wait.
        assertEquals(NotReady.UPDATE, HomeDerive.whyNot(state(DeviceConnection.LOCAL) { copy(updateNeeded = true) }))
    }

    @Test fun happeningNowAppearsOnlyWhileSomethingIs() {
        assertFalse(HomeDerive.happeningNow(state()))
        assertTrue(HomeDerive.happeningNow(state { copy(live = LiveSessions(terminals = 1)) }))
        assertTrue(HomeDerive.happeningNow(state { copy(transfer = HomeTransfer("a.zip", "42%", 0.42f, upload = true)) }))
    }

    @Test fun cancelIsOfferedOnlyForATransferStillGoing() {
        assertFalse(HomeDerive.canCancel(null))
        assertTrue(HomeDerive.canCancel(HomeTransfer("a", "1%", 0.01f, true)))
        assertFalse(HomeDerive.canCancel(HomeTransfer("a", "Sent", 1f, true, active = false)))
    }

    @Test fun theMirroredLineNeedsAtLeastOneNotification() {
        assertFalse(HomeDerive.showMirrored(state()))
        assertTrue(HomeDerive.showMirrored(state { copy(deskNotifications = 2) }))
        assertFalse(HomeDerive.showRecentFiles(state()))
        assertTrue(HomeDerive.showRecentFiles(state { copy(recent = listOf(record(1))) }))
    }

    @Test fun theNotificationsNudgeIsForAndroid13AndUpUntilGranted() {
        assertTrue(HomeDerive.notificationsOff(sdk = 33, granted = false))
        assertTrue(HomeDerive.notificationsOff(sdk = 36, granted = false))
        assertFalse(HomeDerive.notificationsOff(sdk = 33, granted = true))
        assertFalse("before 13 there is nothing to ask", HomeDerive.notificationsOff(sdk = 32, granted = false))
    }
}
