/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.desklink.mobile.plugins.mprisreceiver.MprisReceiverCallback
import org.desklink.mobile.ui.ThemeUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The real phone's StrictMode run: what the main thread did that was the disk's or Android's business. */
@RunWith(RobolectricTestRunner::class)
class MainThreadWorkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun source(path: String) = File("src/main/java/org/desklink/mobile/$path").readText()

    @Test
    fun anAppsLabelIsAskedOfThePackageManagerOnce() {
        val manager = mockk<PackageManager>()
        every { manager.getApplicationInfo("org.example", 0) } returns ApplicationInfo()
        every { manager.getApplicationLabel(any()) } returns "Example"
        val context = mockk<Context>()
        every { context.packageManager } returns manager
        repeat(5) { assertEquals("Example", AppsHelper.appNameLookup(context, "org.example")) }
        verify(exactly = 1) { manager.getApplicationInfo("org.example", 0) }
    }

    @Test
    fun theLogLinesTheMainThreadHandsOverAreKeptInOrder() {
        AppLog.useDir(tmp.root)
        try {
            AppLog.clear()
            repeat(20) { AppLog.event("t", "line $it") } // the Robolectric test thread is the main thread
            assertEquals((0 until 20).map { "line $it" }, AppLog.lines().map { it.substringAfter("t ") }.toList())
        } finally {
            AppLog.useDir(null)
        }
    }

    @Test
    fun wallpaperColoursAreAnsweredFromMemoryAndFollowTheSwitch() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().putBoolean(ThemeUtil.KEY_DYNAMIC_COLORS, true).commit()
        ThemeUtil.setUserPreferredTheme(app)
        assertTrue(ThemeUtil.dynamicColorsOn())
        prefs.edit().putBoolean(ThemeUtil.KEY_DYNAMIC_COLORS, false).commit()
        assertFalse("turning it off applies to the next screen", ThemeUtil.dynamicColorsOn())
        assertFalse("the precondition no longer reads the preferences", source("ui/ThemeUtil.kt").substringAfter("setPrecondition").substringBefore(".build()").contains("PreferenceManager"))
    }

    @Test
    fun theCoverCheckTellsOnePixelOrOneWidthApart() {
        val a = IntArray(12) { it * 7919 }
        assertEquals(MprisReceiverCallback.check(a, 4), MprisReceiverCallback.check(a.copyOf(), 4))
        assertNotEquals(MprisReceiverCallback.check(a, 4), MprisReceiverCallback.check(a.copyOf().also { it[5] += 1 }, 4))
        assertNotEquals("the same pixels as 3 wide", MprisReceiverCallback.check(a, 4), MprisReceiverCallback.check(a, 3))
        assertFalse("no slow Bitmap.sameAs on the main thread", source("plugins/mprisreceiver/MprisReceiverCallback.kt").contains(".sameAs("))
    }

    @Test
    fun theClipboardListenerAndTheNotificationPluginDoTheirBinderAndDiskWorkOffTheMainThread() {
        val clipboard = source("plugins/clipboard/ClipboardListener.kt")
        assertFalse("registered on the main thread", clipboard.contains("main.post { cm.addPrimaryClipChangedListener"))
        val behind = clipboard.substringAfter("private fun onPrimaryClipChanged()").substringBefore("/** Names of the computers")
        assertTrue("the clip's description and the offer are worked out in the background", behind.substringAfter("// Behind:").startsWith(" the clip") && behind.substringAfter("// Behind:").contains("execute {"))
        val notifications = source("plugins/notifications/NotificationsPlugin.kt")
        val current = notifications.substringAfter("private fun sendCurrentNotifications(service: NotificationReceiver").substringBefore("private fun sendCurrentNotifications(notifications")
        assertTrue("the active notifications are asked for in the background", current.indexOf("ThreadHelper.execute") in 0 until current.indexOf("getActiveNotifications"))
        assertTrue("and a label is looked up at the post, not at the send", notifications.contains("ThreadHelper.execute { appNameLookup(context, pkg) }"))
    }

    @Test
    fun theRingtoneNameIsWorkedOutInTheBackgroundWhenSettingsOpen() {
        val tab = source("ui/compose/screen/settings/SettingsTab.kt")
        val group = tab.substringAfter("private fun NotificationsGroup(").substringBefore("SectionLabel(stringResource(R.string.sg_notifications_phone")
        val at = group.indexOf("getRingtone(")
        assertTrue(at > 0)
        assertTrue("inside a produceState on Dispatchers.IO", group.lastIndexOf("withContext(kotlinx.coroutines.Dispatchers.IO)", at) > group.lastIndexOf("produceState", at))
    }
}
