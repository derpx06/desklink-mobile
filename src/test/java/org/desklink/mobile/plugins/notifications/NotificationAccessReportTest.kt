/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.desklink.mobile.emptyStatesFor
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The real reporting path with the permission missing: the plugin is not loaded, and the computer still hears `accessGranted: false`. */
@RunWith(RobolectricTestRunner::class)
class NotificationAccessReportTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun withoutNotificationAccessTheFeatureReadyStatesCarryAccessGrantedFalse() {
        val key = Plugin.getPluginKey(NotificationsPlugin::class.java)
        val device = mockk<Device>(relaxed = true) {
            every { context } returns app
            every { isPluginEnabled(key) } returns true
            every { loadedPlugins } returns java.util.concurrent.ConcurrentHashMap<String, Plugin>() // not loaded: no access
            every { pluginsWithoutPermissions } returns java.util.concurrent.ConcurrentHashMap<String, Plugin>().also { it[key] = mockk(relaxed = true) }
        }
        val packet = emptyStatesFor(device).firstOrNull { it.type == DeskLinkProtocol.PACKET_TYPE_NOTIFICATION }
        assertNotNull(packet)
        assertEquals(false, packet!!.getBoolean("accessGranted", true))
        assertEquals(true, packet.getBoolean("snapshot", false))
    }
}
