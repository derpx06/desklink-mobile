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
}
