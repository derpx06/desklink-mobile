/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.screen.home.Blocker
import org.desklink.mobile.ui.compose.screen.home.Fix
import org.desklink.mobile.ui.compose.screen.home.HomeDerive
import org.desklink.mobile.ui.compose.screen.home.HomeModel
import org.desklink.mobile.ui.compose.screen.home.HomeOffers
import org.desklink.mobile.ui.compose.screen.home.HomeState
import org.desklink.mobile.ui.compose.screen.home.PaletteGroup
import org.desklink.mobile.ui.compose.screen.home.PaletteItem
import org.desklink.mobile.ui.compose.screen.home.PaletteModel
import org.desklink.mobile.ui.compose.screen.home.PaletteModel.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What "Find anything" lists and in what order, without drawing it. */
@RunWith(RobolectricTestRunner::class)
class PaletteModelTest {
    private fun item(id: String, group: PaletteGroup, title: String, hint: String = "", keywords: String = "") = PaletteItem(id, group, title, 0, hint, keywords)

    private val items = listOf(
        item("action:send-files", PaletteGroup.ACTIONS, "Send files"),
        item("action:send-clipboard", PaletteGroup.ACTIONS, "Send clipboard", keywords = "copy paste"),
        item("action:ring", PaletteGroup.ACTIONS, "Ring computer", hint = "Makes it ring"),
        item("file:1", PaletteGroup.FILES, "invoice-october.pdf", hint = "Received"),
        item("file:2", PaletteGroup.FILES, "october-photos.zip", hint = "Sent"),
        item("file:3", PaletteGroup.FILES, "notes.txt"),
        item("file:4", PaletteGroup.FILES, "extra.txt"),
        item("note:a", PaletteGroup.NOTES, "Release checklist"),
        item("note:b", PaletteGroup.NOTES, "Café ideas"),
        item("snippet:a", PaletteGroup.SNIPPETS, "ssh arch-desk -L 5173"),
        item("settings:connection", PaletteGroup.SETTINGS, "Connection", hint = "This phone's name"),
        item("settings:files", PaletteGroup.SETTINGS, "Files", hint = "Where received files go"),
        item("computer:d1", PaletteGroup.COMPUTERS, "arch-desk"),
    )

    private fun ids(query: String) = PaletteModel.search(items, query).flatMap { s -> s.items.map { it.id } }

    @Test
    fun aBlankQueryListsEveryActionAndAFewOfTheRest() {
        val sections = PaletteModel.search(items, "  ")
        assertEquals(PaletteGroup.entries, sections.map { it.group })
        assertEquals(3, sections.first { it.group == PaletteGroup.ACTIONS }.items.size)
        assertEquals(PaletteModel.IDLE_PER_GROUP, sections.first { it.group == PaletteGroup.FILES }.items.size)
    }

    @Test
    fun groupsComeInTheirOwnOrderWhateverTheInputOrder() {
        val shuffled = items.reversed()
        assertEquals(PaletteGroup.entries.filter { g -> items.any { it.group == g } }, PaletteModel.search(shuffled, "").map { it.group })
    }

    @Test
    fun everyWordMustMatchAndTheTitleBeatsTheHint() {
        // "october" starts file:2's title and only a word of file:1's.
        assertEquals(listOf("file:2", "file:1"), ids("october"))
        assertEquals(listOf("file:2"), ids("october zip"))
        assertEquals(emptyList<String>(), ids("october nothing"))
    }

    @Test
    fun aHintOrKeywordFindsWhatTheTitleDoesNot() {
        assertTrue("action:send-clipboard" in ids("copy"))
        assertTrue("action:ring" in ids("makes"))
        assertTrue("settings:files" in ids("received"))
    }

    @Test
    fun caseAndAccentsDoNotMatter() {
        assertEquals(listOf("note:b"), ids("CAFE"))
        assertEquals(listOf("note:b"), ids("café"))
    }

    @Test
    fun equalScoresKeepTheirGivenOrderAndAGroupIsCut() {
        val many = (1..10).map { item("file:$it", PaletteGroup.FILES, "report $it") }
        val found = PaletteModel.search(many, "report").single().items
        assertEquals(PaletteModel.FOUND_PER_GROUP, found.size)
        assertEquals((1..PaletteModel.FOUND_PER_GROUP).map { "file:$it" }, found.map { it.id })
    }

    @Test
    fun nothingMatchesGivesNoSections() {
        assertTrue(PaletteModel.search(items, "zzzz").isEmpty())
        assertTrue(PaletteModel.search(emptyList(), "").isEmpty())
    }

    // ---- which actions the computer offers

    private fun state(connection: DeviceConnection, offers: HomeOffers = HomeOffers(), canUnlock: Boolean = false) =
        HomeState("d1", "arch-desk", connection, emptyList(), offers = offers, canUnlock = canUnlock)

    private val everything = listOf(
        Action.SEND_FILES, Action.SEND_CLIPBOARD, Action.SEND_LINK, Action.DESK_SCREEN, Action.NEW_TERMINAL,
        Action.OPEN_TERMINALS, Action.NEW_NOTE, Action.OPEN_BUCKET, Action.RING, Action.PING, Action.LOCK, Action.UNLOCK, Action.SHARE_SCREEN, Action.PAIR_ANOTHER, Action.GUIDE,
    )

    @Test
    fun everyActionIsListedWhateverTheComputerCanTake() {
        assertEquals(everything, PaletteModel.offeredActions(state(DeviceConnection.LOCAL)))
        assertEquals("offline: listed, dimmed", everything, PaletteModel.offeredActions(state(DeviceConnection.OFFLINE)))
        assertEquals(everything, PaletteModel.offeredActions(state(DeviceConnection.LOCAL, HomeOffers(ring = false, lock = false, screen = false))))
    }

    @Test
    fun anUnreachableComputerBlocksWhatNeedsItAndNothingElse() {
        val s = state(DeviceConnection.OFFLINE)
        listOf(Action.SEND_FILES, Action.RING, Action.LOCK, Action.UNLOCK, Action.NEW_TERMINAL).forEach { assertEquals(it, Blocker.OFFLINE, HomeDerive.blocker(s, it)) }
        listOf(Action.OPEN_TERMINALS, Action.NEW_NOTE, Action.OPEN_BUCKET, Action.PAIR_ANOTHER, Action.GUIDE).forEach { assertEquals(it, null, HomeDerive.blocker(s, it)) }
    }

    @Test
    fun aFeatureTheComputerLacksIsBlockedWithItsReason() {
        val offers = HomeOffers(ring = false, lock = false, screen = false, why = mapOf(Action.RING to Blocker.PHONE_OFF, Action.LOCK to Blocker.PERMISSION))
        val s = state(DeviceConnection.LOCAL, offers)
        assertEquals(Blocker.PHONE_OFF, HomeDerive.blocker(s, Action.RING))
        assertEquals(Blocker.PERMISSION, HomeDerive.blocker(s, Action.LOCK))
        assertEquals("no reason known: the computer's", Blocker.DESK_OFF, HomeDerive.blocker(s, Action.DESK_SCREEN))
        assertEquals(null, HomeDerive.blocker(s, Action.SEND_FILES))
        assertEquals("unlock says why it is off", Blocker.NOT_SAME_WIFI, HomeDerive.blocker(s.copy(unlockBlocker = Blocker.NOT_SAME_WIFI), Action.UNLOCK))
        assertEquals(null, HomeDerive.blocker(state(DeviceConnection.LOCAL, canUnlock = true), Action.UNLOCK))
    }

    @Test
    fun aComputerThatNeedsAnUpdateBlocksEverythingThatSends() {
        // Connected, but its DeskLink is another version: nothing that sends a packet can run (it would only queue).
        val s = state(DeviceConnection.LOCAL).copy(updateNeeded = true)
        assertFalse(HomeDerive.usable(s))
        assertEquals(Blocker.UPDATE, HomeDerive.blocker(s, Action.SEND_FILES))
        assertTrue(HomeDerive.usable(state(DeviceConnection.LOCAL)))
        assertEquals(Blocker.CONNECTING, HomeDerive.blocker(state(DeviceConnection.CONNECTING), Action.PING))
        val sharing = state(DeviceConnection.OFFLINE).copy(live = org.desklink.mobile.ui.compose.screen.home.LiveSessions(phoneScreen = true))
        assertEquals("stopping this phone's share is local", null, HomeDerive.blocker(sharing, Action.SHARE_SCREEN))
        assertEquals(Blocker.OFFLINE, HomeDerive.blocker(sharing, Action.PING))
    }

    @Test
    fun eachReasonHasItsFixAndWords() {
        val fixes = Blocker.entries.associateWith(HomeDerive::fix)
        assertEquals(Fix.RETRY, fixes[Blocker.OFFLINE])
        assertEquals(Fix.RETRY, fixes[Blocker.CANT_REACH])
        assertEquals(Fix.TURN_ON_HERE, fixes[Blocker.PHONE_OFF])
        assertEquals(Fix.ALLOW, fixes[Blocker.PERMISSION])
        assertEquals(Fix.NONE, fixes[Blocker.DESK_OFF])
        assertEquals(Fix.NONE, fixes[Blocker.UNLOCK_OFF])
        val app = ApplicationProvider.getApplicationContext<Application>()
        Blocker.entries.forEach { b ->
            val line = app.getString(HomeDerive.message(b), "Ring", "arch-desk")
            assertTrue("$b: $line", "Ring" in line || "Unlock" in line || b == Blocker.UNLOCK_OFF)
            assertTrue(app.getString(HomeDerive.needs(b), "arch-desk").startsWith("Needs"))
        }
        assertEquals(null, HomeDerive.fixLabel(Fix.ALLOW))
    }

    @Test
    fun aPluginIsOffHereWaitingForAPermissionOrMissingAtTheComputer() {
        assertEquals(null, HomeModel.blocker(loaded = true, enabledHere = true, needsPermission = false))
        assertEquals(Blocker.PHONE_OFF, HomeModel.blocker(loaded = false, enabledHere = false, needsPermission = false))
        assertEquals(Blocker.PERMISSION, HomeModel.blocker(loaded = false, enabledHere = true, needsPermission = true))
        assertEquals(Blocker.DESK_OFF, HomeModel.blocker(loaded = false, enabledHere = true, needsPermission = false))
    }
}
