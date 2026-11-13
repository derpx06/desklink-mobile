/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.findmyphone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric's MediaPlayer refuses to prepare a data source it has no media info for, which is
 * exactly an emulator started with `-no-audio` or a ringtone that cannot be opened.
 */
@RunWith(RobolectricTestRunner::class)
class FindMyPhonePluginTest {
    @Test
    fun theRingtoneFailingToPrepareNeitherUnloadsThePluginNorStopsTheRing() {
        val plugin = FindMyPhonePlugin()
        plugin.setContext(ApplicationProvider.getApplicationContext<Context>(), mockk<Device> { every { deviceId } returns "d" })
        assertTrue(plugin.onCreate())

        plugin.startPlaying() // falls back to vibration
        plugin.startFlashing()
        plugin.stopPlaying()
        plugin.stopFlashing()
        plugin.startPlaying() // a second ring tries again
        plugin.onDestroy()
    }

    @Test
    fun aRingEndedByTheLinkGoingTakesItsNotificationWithIt() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val plugin = FindMyPhonePlugin()
        plugin.setContext(app, mockk<Device>(relaxed = true) { every { deviceId } returns "d" })
        assertTrue(plugin.onCreate())
        plugin.onPacketReceived(org.desklink.mobile.NetworkPacket("desklink.findmyphone.request"))
        val notifications = org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
        assertTrue("the ring posted its Found it notification", notifications.allNotifications.isNotEmpty())
        plugin.onDestroy()
        assertTrue("no ongoing Found it left with nothing ringing", notifications.allNotifications.isEmpty())
    }
}
