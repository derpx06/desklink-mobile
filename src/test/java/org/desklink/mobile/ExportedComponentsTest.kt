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

    private fun info(flags: Int) = app.packageManager.getPackageInfo(app.packageName, flags)

    @Test
    fun theExportedActivitiesServicesAndProvidersAreAnExplicitList() {
        val activities = info(PackageManager.GET_ACTIVITIES).activities.orEmpty().filter { it.exported }.map { it.name.substringAfterLast('.') }.toSortedSet()
        val services = info(PackageManager.GET_SERVICES).services.orEmpty().filter { it.exported }.map { it.name.substringAfterLast('.') }.toSortedSet()
        val providers = info(PackageManager.GET_PROVIDERS).providers.orEmpty().filter { it.exported }.map { it.name.substringAfterLast('.') }.toSortedSet()
        assertEquals(EXPECTED_ACTIVITIES + (if (BuildConfig.DEBUG) DEBUG_ONLY_ACTIVITIES else emptySet()), activities)
        assertEquals(EXPECTED_SERVICES, services)
        assertEquals(EXPECTED_PROVIDERS, providers)
        // Every exported service is bound by the system only: it demands a system permission.
        info(PackageManager.GET_SERVICES).services.orEmpty().filter { it.exported }.forEach {
            assertTrue("${it.name} must demand a system permission", !it.permission.isNullOrEmpty())
        }
    }

    @Test
    fun mainActivityStacksAboveTheOthersInsteadOfClosingThem() {
        val main = app.packageManager.getActivityInfo(android.content.ComponentName(app, "org.desklink.mobile.ui.MainActivity"), 0)
        assertEquals(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP, main.launchMode)
    }

    @Test
    fun anIntentWithoutAnActionIsIgnoredNotACrash() {
        ShareBroadcastReceiver().onReceive(app as Context, Intent())
        assertTrue(true)
    }

    private companion object {
        /** What another app must reach: the launcher, and the share sheet targets (each asks the person before it acts). */
        val EXPECTED_ACTIVITIES = sortedSetOf("MainActivity", "SaveToNotesActivity", "ShareActivity", "ShareToBucketActivity")

        /** Debug builds only (src/debug and the Compose test manifest); a release build has none of them. */
        val DEBUG_ONLY_ACTIVITIES = sortedSetOf("AudioSelfTestActivity", "CaptureDemoActivity", "ComponentActivity", "MediaCardPreviewActivity", "PreviewActivity", "QuitActivity")

        /** The system binds these: tiles, the accessibility pointer service, the notification listener and the chooser targets. */
        val EXPECTED_SERVICES = sortedSetOf(
            "ClipboardTileService", "KeepAwakeTileService", "LockDeskTileService", "MouseReceiverService",
            "NotificationReceiver", "RingDeskTileService", "ShareChooserTargetService",
        )
        val EXPECTED_PROVIDERS = sortedSetOf<String>()
    }
}
