/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import android.app.Activity
import android.view.WindowManager
import org.desklink.mobile.ui.compose.SecureWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecureWindowTest {
    private fun secure(activity: Activity) = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun theWindowIsSecureWhileAnySecretScreenIsUpAndOnlyThen() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        assertFalse(secure(activity))
        SecureWindows.enter(activity) // a tab
        SecureWindows.enter(activity) // a dialog on top of it
        assertTrue(secure(activity))
        SecureWindows.leave(activity)
        assertTrue("one still needs it", secure(activity))
        SecureWindows.leave(activity)
        assertFalse(secure(activity))
        assertEquals(0, SecureWindows.count(activity))
    }

    private fun source(path: String) = listOf(java.io.File(path), java.io.File("desklink-mobile/$path")).first { it.exists() }.readText()
    private val main = "src/main/java/org/desklink/mobile/"

    /**
     * Owner decision (11-17): terminals, notes and files are not secret windows: screenshots, screen recording, the desk's Phone
     * Screen share and the recents tile work there. Only real secrets keep the window secure: the pairing code, the computer's
     * own screen on the mouse pad, and the session password's dialog.
     */
    @Test
    fun onlyTheRealSecretsKeepTheWindowSecure() {
        for (path in listOf(
            "ui/compose/screen/tabs/TerminalsTab.kt", "plugins/terminal/TerminalActivity.kt", "ui/compose/screen/tabs/TabScreens.kt",
            "plugins/notes/NotesActivity.kt", "plugins/folders/BucketActivity.kt",
        )) {
            assertFalse("$path blocks screenshots and screen sharing", Regex("SecureWindow").containsMatchIn(source(main + path)))
        }
        assertTrue(source(main + "ui/compose/screen/pairing/PairingScreens.kt").contains("SecureWindow()"))
        assertTrue(source(main + "plugins/mousepad/MousePadActivity.kt").contains("SecureWindows.enter(this)"))
        assertTrue(source(main + "ui/compose/SessionPasswordDialog.kt").contains("SecureFlagPolicy.SecureOn"))
    }

    @Test
    fun thereIsNoDebugSwitchForScreenshotsAnyMore() {
        assertFalse(source(main + "ui/compose/SecureWindow.kt").contains("allow_screenshots"))
        assertFalse(source(main + "ui/compose/screen/settings/SettingsTab.kt").contains("ALLOW_SCREENSHOTS"))
    }

    /** The terminal never asks for a password keyboard: ColorOS blanks every capture while one is up. */
    @Test
    fun theTerminalKeyboardIsNeverAPasswordVariation() {
        val view = source(main + "plugins/terminal/TerminalView.kt")
        assertTrue(view.contains("InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS"))
        assertFalse(Regex("outAttrs\\.inputType\\s*=.*PASSWORD").containsMatchIn(view))
    }
}
