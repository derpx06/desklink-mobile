/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import android.view.HapticFeedbackConstants
import org.desklink.mobile.ui.compose.components.DeskHaptics
import org.desklink.mobile.ui.compose.components.Haptic
import org.desklink.mobile.ui.compose.screen.transfers.extensionBadge
import org.desklink.mobile.ui.compose.shell.TabMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules behind the polish pass: haptic constants, tab motion, file badges. */
class PolishTest {
    @Test fun everyHapticHasAConstantOnEveryVersion() {
        for (sdk in listOf(26, 29, 30, 33, 34, 35)) Haptic.entries.forEach { assertTrue(DeskHaptics.constantFor(it, sdk) >= 0) }
    }

    @Test fun newerVersionsGetTheTogglePatternsOlderOnesTheNearestTick() {
        assertEquals(HapticFeedbackConstants.TOGGLE_ON, DeskHaptics.constantFor(Haptic.TOGGLE_ON, 34))
        assertEquals(HapticFeedbackConstants.TOGGLE_OFF, DeskHaptics.constantFor(Haptic.TOGGLE_OFF, 34))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, DeskHaptics.constantFor(Haptic.TOGGLE_ON, 33))
        assertEquals(HapticFeedbackConstants.CONFIRM, DeskHaptics.constantFor(Haptic.SUCCESS, 30))
        assertEquals(HapticFeedbackConstants.REJECT, DeskHaptics.constantFor(Haptic.FAILURE, 30))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, DeskHaptics.constantFor(Haptic.TICK, 26))
    }

    @Test fun neighbouringTabsSlideAndFarJumpsCrossfade() {
        assertTrue(TabMotion.slides(0, 1))
        assertTrue(TabMotion.slides(3, 2))
        assertFalse(TabMotion.slides(0, 4))
        assertFalse(TabMotion.slides(4, 1))
    }

    @Test fun aFileWithoutAPictureShowsItsExtensionAsABadge() {
        assertEquals("BIN", extensionBadge("dl-300m.bin"))
        assertEquals("PDF", extensionBadge("a.b.pdf"))
        assertEquals("", extensionBadge("README"))
        assertEquals("", extensionBadge("archive.verylongextension"))
        assertEquals("", extensionBadge("weird.a b"))
    }
}
