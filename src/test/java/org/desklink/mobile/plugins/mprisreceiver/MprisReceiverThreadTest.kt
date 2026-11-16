/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mprisreceiver

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.desklink.mobile.Device
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The players are plain maps owned by the main looper: work from another thread is moved onto it (a ConcurrentModificationException crashed the app). */
@RunWith(AndroidJUnit4::class)
class MprisReceiverThreadTest {
    @Test
    fun featureReadyFromAWorkerRunsOnTheMainLooper() {
        val device = mockk<Device>(relaxed = true) { every { deviceId } returns "desk" }
        val plugin = MprisReceiverPlugin()
        plugin.setContext(ApplicationProvider.getApplicationContext(), device)
        shadowOf(Looper.getMainLooper()).pause()
        Thread { plugin.onFeatureReady() }.apply { start(); join() }
        verify(exactly = 0) { device.sendPacket(any()) }
        shadowOf(Looper.getMainLooper()).idle()
        verify(atLeast = 1) { device.sendPacket(any()) }
    }
}
