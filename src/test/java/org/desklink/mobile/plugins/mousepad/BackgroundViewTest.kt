/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mousepad

import org.desklink.mobile.plugins.mousepad.MousePadActivity.BackgroundAction
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundViewTest {
    private fun action(interactive: Boolean = true, finishing: Boolean = false, rotating: Boolean = false, live: Boolean = true, pending: Boolean = false) =
        MousePadActivity.backgroundAction(interactive, finishing, rotating, live, pending)

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

    @Test
    fun aRequestNobodyAnsweredYetEndsWhenTheScreenIsLeftSoAFlickerOfTheLinkCannotPutTheComputersDialogUp() {
        assertEquals(BackgroundAction.END_NOW, action(live = false, pending = true))
        assertEquals(BackgroundAction.END_NOW, action(live = false, pending = true, interactive = false))
        assertEquals(BackgroundAction.NONE, action(live = false, pending = true, rotating = true))
        assertEquals(BackgroundAction.NONE, action(live = false, pending = true, finishing = true))
    }

    private val source = java.io.File("src/main/java/org/desklink/mobile/plugins/mousepad/MousePadActivity.kt").readText()

    @Test
    fun backIsNotInterceptedAndAFinishingScreenEndsTheRemoteSession() {
        assertEquals("Back is the system's: it finishes the screen", false, Regex("""BackHandler|onBackPressed|OnBackPressedCallback""").containsMatchIn(source))
        val destroy = source.substringAfter("override fun onDestroy()").substringBefore("override fun onSharedPreferenceChanged")
        assertEquals(true, Regex("""isFinishing && !isChangingConfigurations\)\s*\{\s*sendStopScreen\(\)""").containsMatchIn(destroy))
    }
}
