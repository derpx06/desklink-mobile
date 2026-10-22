/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.shell

import org.desklink.mobile.ui.compose.shell.AppTab
import org.desklink.mobile.ui.compose.shell.QuickAction
import org.desklink.mobile.ui.compose.shell.TabNav
import org.desklink.mobile.ui.compose.shell.filesBadge
import org.desklink.mobile.ui.compose.shell.quickAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabNavTest {
    @Test
    fun theFiveTabsAreInTheDesksOrder() {
        assertEquals(listOf("home", "files", "notes", "terminals", "settings"), AppTab.entries.map { it.key })
    }

    @Test
    fun eachTabKeepsItsOwnBackStackWhileAnotherIsShown() {
        val nav = TabNav()
        nav.push(AppTab.SETTINGS, "settings/connection")
        nav.push(AppTab.SETTINGS, "settings/connection/advanced")
        nav.push(AppTab.FILES, "files/viewer/1")
        nav.select(AppTab.NOTES)
        assertEquals(listOf("settings/connection", "settings/connection/advanced"), nav.stack(AppTab.SETTINGS))
        assertEquals("files/viewer/1", nav.top(AppTab.FILES))
        assertNull(nav.top(AppTab.NOTES))
    }

    @Test
    fun pushingTheRouteAlreadyOnTopDoesNothing() {
        val nav = TabNav()
        nav.push(AppTab.SETTINGS, "a"); nav.push(AppTab.SETTINGS, "a")
        assertEquals(listOf("a"), nav.stack(AppTab.SETTINGS))
    }

    @Test
    fun backPopsTheSelectedTabThenGoesHomeThenLeaves() {
        val nav = TabNav(AppTab.SETTINGS)
        nav.push(AppTab.SETTINGS, "settings/privacy")
        assertTrue(nav.canGoBack)
        assertTrue(nav.back())
        assertEquals(AppTab.SETTINGS, nav.selected)
        assertTrue("from a tab's root, back goes Home", nav.back())
        assertEquals(AppTab.HOME, nav.selected)
        assertFalse("at Home's root the app is left", nav.canGoBack)
        assertFalse(nav.back())
    }

    @Test
    fun anotherTabsStackIsNotTouchedByBackOnTheSelectedOne() {
        val nav = TabNav(AppTab.NOTES)
        nav.push(AppTab.SETTINGS, "settings/sound")
        nav.back() // Notes is at its root: Home
        assertEquals(listOf("settings/sound"), nav.stack(AppTab.SETTINGS))
    }

    @Test
    fun theStateSurvivesRotationAndProcessDeath() {
        val nav = TabNav(AppTab.SETTINGS)
        nav.push(AppTab.SETTINGS, "settings/files")
        nav.push(AppTab.FILES, "files/viewer/a|b") // a route may contain the separator
        val restored = TabNav.restore(nav.snapshot())
        assertEquals(AppTab.SETTINGS, restored.selected)
        assertEquals(listOf("settings/files"), restored.stack(AppTab.SETTINGS))
        assertEquals(listOf("files/viewer/a|b"), restored.stack(AppTab.FILES))
        assertEquals(emptyList<String>(), restored.stack(AppTab.NOTES))
    }

    @Test
    fun garbageInTheSavedStateIsIgnored() {
        val restored = TabNav.restore(listOf("nonsense", "||", "nope", "settings|ok"))
        assertEquals(AppTab.HOME, restored.selected)
        assertEquals(listOf("ok"), restored.stack(AppTab.SETTINGS))
        assertEquals(AppTab.HOME, TabNav.restore(emptyList()).selected)
    }

    @Test
    fun longPressQuickActionsAreTheOnesTheOwnerAsked() {
        assertEquals(QuickAction.SEND_FILES, AppTab.FILES.quickAction())
        assertEquals(QuickAction.NEW_NOTE, AppTab.NOTES.quickAction())
        assertEquals(QuickAction.NEW_TERMINAL, AppTab.TERMINALS.quickAction())
        assertEquals(QuickAction.SWITCH_COMPUTER, AppTab.HOME.quickAction())
        assertNull(AppTab.SETTINGS.quickAction())
    }

    @Test
    fun theFilesRingShowsTheBucketAndTheRunningTransfer() {
        assertEquals(false to null, filesBadge(false, null, false, null))
        assertEquals(true to 0.5f, filesBadge(true, 0.5f, false, null))
        assertEquals("busy with no size: a turning ring", true to null, filesBadge(true, null, false, null))
        assertEquals(true to 0.25f, filesBadge(false, 0.9f, true, 0.25f))
        assertEquals("both moving: their mean", true to 0.5f, filesBadge(true, 0.25f, true, 0.75f))
        assertEquals("an idle Bucket's old fraction is ignored", false to null, filesBadge(false, 0.9f, false, 0.2f))
    }
}
