/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.helpers.PermissionRecovery
import org.desklink.mobile.ui.compose.screen.settings.PermissionNeeds
import org.desklink.mobile.ui.compose.screen.settings.permissionRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PermissionNeedsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun permissionsNothingUsesAreNotCountedAsWaiting() {
        val nothing = permissionRows(app, "Desk", {}, PermissionNeeds(accessibility = false, notificationAccess = false, calls = false, contacts = false))
        val waiting = nothing.filter { !it.granted && !it.unknown }.map { it.title }
        assertFalse("accessibility is not waiting when no feature needs it: $waiting", waiting.any { it.contains("ccessibility") })
        assertFalse(waiting.any { it.contains("otification access", ignoreCase = true) })
        assertTrue("no calls row when calls are off", nothing.none { it.title == app.getString(org.desklink.mobile.R.string.settings_perm_calls) })
    }

    @Test
    fun aFeatureThatIsOnMakesItsPermissionWaiting() {
        val all = permissionRows(app, "Desk", {}, PermissionNeeds())
        val waiting = all.filter { !it.granted && !it.unknown }.map { it.title }
        assertTrue(waiting.any { it == app.getString(org.desklink.mobile.R.string.settings_perm_calls) })
        assertTrue(waiting.any { it == app.getString(org.desklink.mobile.R.string.settings_perm_contacts) })
    }

    @Test
    fun anAllowThatAndroidWillNotAnswerOpensTheAppsPermissionPage() {
        assertTrue(PermissionRecovery.mustOpenSettings(askedBefore = true, showsRationale = false))
        assertFalse("never asked: ask", PermissionRecovery.mustOpenSettings(askedBefore = false, showsRationale = false))
        assertFalse("denied once: Android still asks", PermissionRecovery.mustOpenSettings(askedBefore = true, showsRationale = true))
        assertEquals(emptyList<String>(), PermissionRecovery.blocked(app, listOf("android.permission.CAMERA")))
    }
}
