/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.surfaces

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Keeping the desk awake weakens the desk's own lock: from a locked phone's shade it asks for the phone's unlock first. */
@RunWith(RobolectricTestRunner::class)
class KeepAwakeTileLockedTest {
    @Test
    fun aTapOnALockedPhoneUnlocksBeforeItActs() {
        val tile = Robolectric.setupService(KeepAwakeTileService::class.java)
        shadowOf(tile).setLocked(true)
        tile.onClick()
        assertFalse("unlockAndRun was asked for", tile.isLocked)
    }
}
