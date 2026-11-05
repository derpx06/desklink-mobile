/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import org.desklink.mobile.ui.compose.model.device.DeviceConnection
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

/** What "Find anything" lists and in what order, without drawing it. */
class PaletteModelTest {
    private fun item(id: String, group: PaletteGroup, title: String, hint: String = "", keywords: String = "") = PaletteItem(id, group, title, 0, hint, keywords)

    private val items = listOf(
        item("action:send-files", PaletteGroup.ACTIONS, "Send files"),
        item("action:send-clipboard", PaletteGroup.ACTIONS, "Send clipboard", keywords = "copy paste"),
        item("action:ring", PaletteGroup.ACTIONS, "Ring desk", hint = "Makes it ring"),
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

    @Test
    fun aReachableComputerOffersItsActions() {
        val ids = PaletteModel.offeredActions(state(DeviceConnection.LOCAL))
        listOf(Action.SEND_FILES, Action.RING, Action.LOCK, Action.DESK_SCREEN, Action.NEW_NOTE).forEach { assertTrue(it, it in ids) }
        assertFalse("unlock only when the desk allows it", Action.UNLOCK in ids)
        assertTrue(Action.UNLOCK in PaletteModel.offeredActions(state(DeviceConnection.LOCAL, canUnlock = true)))
    }

    @Test
    fun anUnreachableComputerOffersOnlyWhatIsOnThePhone() {
        val ids = PaletteModel.offeredActions(state(DeviceConnection.OFFLINE))
        assertEquals(listOf(Action.OPEN_TERMINALS, Action.NEW_NOTE, Action.OPEN_BUCKET, Action.PAIR_ANOTHER), ids)
    }

    @Test
    fun aFeatureTheComputerLacksIsNotOffered() {
        val ids = PaletteModel.offeredActions(state(DeviceConnection.LOCAL, HomeOffers(ring = false, lock = false, screen = false)))
        listOf(Action.RING, Action.LOCK, Action.DESK_SCREEN, Action.SHARE_SCREEN).forEach { assertFalse(it, it in ids) }
        assertTrue(Action.SEND_FILES in ids)
    }
}
