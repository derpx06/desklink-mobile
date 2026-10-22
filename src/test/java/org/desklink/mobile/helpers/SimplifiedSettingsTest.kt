/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 2.0 turned most plugin options into fixed defaults: what was stored is dropped, quietly, and never more than that. */
@RunWith(RobolectricTestRunner::class)
class SimplifiedSettingsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun theOptionsThatBecameDefaultsAreDroppedAndTheOnesStillInSettingsStay() {
        val all = listOf(
            "remote_default_mode", "remote_haptics", "mpris_notification_enabled", "share_notification_preference", "findmyphone_flashlight",
            // Still in Settings: pointer speed, reverse scroll, ring sound, where files go.
            "mousepad_sensitivity_key", "mousepad_scroll_direction", "findmyphone_ringtone", "share_destination_custom", "theme_pref",
        )
        assertEquals(
            listOf("remote_default_mode", "remote_haptics", "mpris_notification_enabled", "share_notification_preference", "findmyphone_flashlight"),
            RemovedFeatures.simplifiedKeys(all),
        )
    }

    @Test
    fun aPluginWithoutASwitchRunsAtItsDefaultAgain() {
        val prefs = context.getSharedPreferences("computer-1", Context.MODE_PRIVATE)
        prefs.edit(commit = true) {
            putString("certificate", "MIIB-not-touched")
            putString("deviceName", "arch-desk")
            putBoolean("SharePlugin", false)
            putBoolean("MousePadPlugin", false)
            putBoolean("ClipboardPlugin", false) // has its own switch: kept
            putBoolean("TelephonyPlugin", true)
        }
        val dropped = RemovedFeatures.dropPluginSwitches(prefs, listOf("SharePlugin", "MousePadPlugin", "ClipboardPlugin", "TelephonyPlugin", "PingPlugin"))

        assertEquals(setOf("SharePlugin", "MousePadPlugin"), dropped.toSet())
        assertFalse(prefs.contains("SharePlugin"))
        assertFalse(prefs.contains("MousePadPlugin"))
        assertTrue(prefs.contains("ClipboardPlugin"))
        assertTrue(prefs.contains("TelephonyPlugin"))
        // The pairing record sits in the same file and is never touched.
        assertEquals("MIIB-not-touched", prefs.getString("certificate", null))
        assertEquals("arch-desk", prefs.getString("deviceName", null))
    }

    @Test
    fun onlyABooleanUnderAPluginsOwnKeyIsEverRemoved() {
        val prefs = context.getSharedPreferences("computer-2", Context.MODE_PRIVATE)
        prefs.edit(commit = true) { putString("SharePlugin", "not a switch"); putInt("PingPlugin", 3) }
        assertEquals(emptyList<String>(), RemovedFeatures.dropPluginSwitches(prefs, listOf("SharePlugin", "PingPlugin")))
        assertEquals("not a switch", prefs.getString("SharePlugin", null))
    }

    @Test
    fun theSwitchesThatRemainAreExactlyTheOnesSettingsStillShows() {
        assertEquals(
            setOf("ClipboardPlugin", "NotificationsPlugin", "TelephonyPlugin", "MprisReceiverPlugin", "ReceiveNotificationsPlugin", "ContactsPlugin"),
            RemovedFeatures.PLUGINS_WITH_A_SWITCH,
        )
    }

    @Test
    fun runningItTwiceChangesNothingTheSecondTime() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit(commit = true) { putBoolean("remote_haptics", false); putBoolean("mousepad_scroll_direction", true) }
        RemovedFeatures.runOnce(context)
        assertFalse(prefs.contains("remote_haptics"))
        assertTrue(prefs.getBoolean("mousepad_scroll_direction", false))
        // Set again after the cleanup: the marker keeps a later run from deleting what the person chose.
        prefs.edit(commit = true) { putBoolean("remote_haptics", false) }
        RemovedFeatures.runOnce(context)
        assertTrue(prefs.contains("remote_haptics"))
    }
}
