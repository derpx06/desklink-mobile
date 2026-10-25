/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mousepad

import org.desklink.mobile.plugins.mousepad.MousePadActivity.BackgroundAction
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundViewTest {
    private fun action(interactive: Boolean = true, finishing: Boolean = false, rotating: Boolean = false, live: Boolean = true) =
        MousePadActivity.backgroundAction(interactive, finishing, rotating, live)

    @Test
    fun aDarkScreenEndsTheViewAtOnceAndLeavingForAnotherAppAfterAGrace() {
        assertEquals(BackgroundAction.END_NOW, action(interactive = false))
        assertEquals(BackgroundAction.END_AFTER_GRACE, action())
        assertEquals(60_000L, MousePadActivity.BACKGROUND_GRACE_MS)
    }

    @Test
    fun nothingEndsWithoutAViewOrOnARotationOrWhileTheScreenIsLeavingForGood() {
        assertEquals(BackgroundAction.NONE, action(live = false))
        assertEquals(BackgroundAction.NONE, action(rotating = true))
        assertEquals(BackgroundAction.NONE, action(finishing = true))
    }
}
