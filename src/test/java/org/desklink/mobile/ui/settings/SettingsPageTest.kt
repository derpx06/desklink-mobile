/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.settings

import org.desklink.mobile.ui.compose.screen.settings.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsPageTest {
    @Test
    fun theGroupsAreTheDesksTenInTheDesksOrder() {
        assertEquals(
            listOf("connection", "notifications", "clipboard", "calls", "sound", "control", "files", "privacy", "appearance", "advanced"),
            SettingsPage.entries.map { it.route },
        )
    }

    @Test
    fun withoutAComputerOnlyWhatDoesNotNeedOneIsListed() {
        assertEquals(listOf(SettingsPage.CONNECTION, SettingsPage.APPEARANCE, SettingsPage.ADVANCED), SettingsPage.WITHOUT_COMPUTER)
    }

    @Test
    fun aSubPageBelongsToItsGroupAndAnUnknownRouteToNone() {
        assertEquals(SettingsPage.ADVANCED, SettingsPage.groupOf(SettingsPage.NETWORK))
        assertEquals(SettingsPage.ADVANCED, SettingsPage.groupOf(SettingsPage.PERMISSIONS))
        assertEquals(SettingsPage.CONTROL, SettingsPage.groupOf("control"))
        assertNull(SettingsPage.groupOf("features"))
        assertNull(SettingsPage.of("more"))
    }
}
