/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.telephony

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The call tracker is the main looper's: a desk's request is applied there, never on the packet thread. */
@RunWith(AndroidJUnit4::class)
class TelephonyThreadTest {
    @Test
    fun aDesksRequestIsAppliedOnTheMainLooper() {
        val device = mockk<Device>(relaxed = true) { every { deviceId } returns "desk" }
        val plugin = TelephonyPlugin()
        plugin.setContext(ApplicationProvider.getApplicationContext(), device)
        shadowOf(Looper.getMainLooper()).pause()
        var handled = false
        Thread { handled = plugin.onPacketReceived(NetworkPacket("desklink.telephony.request").apply { set("action", "decline") }) }
            .apply { start(); join() }
        assertTrue(handled)
        verify(exactly = 0) { device.sendPacket(any()) }
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 1) { device.sendPacket(any()) } // "no call": answered from the main looper
    }
}
