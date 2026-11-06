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

    @Test
    fun theDebugSwitchLetsAScreenshotThroughOnlyInADebugBuild() {
        assertTrue(SecureWindows.bypassed(debug = true, switchOn = true))
        assertFalse("release ignores the switch whatever a pref says", SecureWindows.bypassed(debug = false, switchOn = true))
        assertFalse(SecureWindows.bypassed(debug = true, switchOn = false))
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity)
        prefs.edit().putBoolean(SecureWindows.KEY_ALLOW_SCREENSHOTS, true).commit()
        SecureWindows.enter(activity)
        assertFalse("enter is a no-op with the switch on", secure(activity))
        SecureWindows.leave(activity)
        prefs.edit().putBoolean(SecureWindows.KEY_ALLOW_SCREENSHOTS, false).commit()
        SecureWindows.enter(activity)
        assertTrue("off by default: secure again", secure(activity))
        SecureWindows.leave(activity)
    }

    @Test
    fun theSwitchIsOnlyInTheDebugSection() {
        fun source(path: String) = listOf(java.io.File(path), java.io.File("desklink-mobile/$path")).first { it.exists() }.readText()
        val settings = source("src/main/java/org/desklink/mobile/ui/compose/screen/settings/SettingsTab.kt")
        val debugAt = settings.indexOf("if (BuildConfig.DEBUG) {")
        val uses = Regex("KEY_ALLOW_SCREENSHOTS").findAll(settings).map { it.range.first }.toList()
        assertTrue(uses.isNotEmpty())
        assertTrue("every use is inside the debug block", uses.all { it > debugAt })
    }

    @Test
    fun theDeskScreenAndTheBucketAreSecureScreens() {
        fun source(path: String) = listOf(java.io.File(path), java.io.File("desklink-mobile/$path")).first { it.exists() }.readText()
        assertTrue(source("src/main/java/org/desklink/mobile/plugins/mousepad/MousePadActivity.kt").contains("SecureWindows.enter(this)"))
        assertTrue(source("src/main/java/org/desklink/mobile/plugins/folders/BucketActivity.kt").contains("SecureWindow()"))
    }
}
