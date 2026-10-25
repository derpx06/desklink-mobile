/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.plugins.share.ShareBroadcastReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Only what another app or the system must reach is exported; the rest is reached by this app's own PendingIntents. */
@RunWith(RobolectricTestRunner::class)
class ExportedComponentsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun exportedReceivers(): Set<String> = app.packageManager
        .getPackageInfo(app.packageName, PackageManager.GET_RECEIVERS).receivers.orEmpty()
        .filter { it.exported }.map { it.name.substringAfterLast('.') }.toSet()

    @Test
    fun theOnlyExportedReceiversAreTheMediaButtonAndTheHomeWidget() {
        assertEquals(setOf("MprisMediaNotificationReceiver", "DeskWidgetProvider"), exportedReceivers() - "ProfileInstallReceiver" /* androidx, guarded by its own permission */)
    }

    @Test
    fun anIntentWithoutAnActionIsIgnoredNotACrash() {
        ShareBroadcastReceiver().onReceive(app as Context, Intent())
        assertTrue(true)
    }
}
