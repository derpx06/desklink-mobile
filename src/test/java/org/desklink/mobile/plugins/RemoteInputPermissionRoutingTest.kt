package org.desklink.mobile.plugins

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.plugins.mousereceiver.MouseReceiverPlugin
import org.desklink.mobile.plugins.mousereceiver.MouseReceiverService
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputPermissionRoutingTest {
    @After
    fun cleanup() {
        MouseReceiverService.instance = null
    }

    @Test
    fun mouseReceiverStaysRoutableWhenAccessibilityIsMissing() {
        val plugin = MouseReceiverPlugin()
        MouseReceiverService.instance = null

        val packet = NetworkPacket("kdeconnect.mousepad.request").apply {
            this["dx"] = 4.0
            this["dy"] = 2.0
        }

        assertTrue(plugin.loadPluginWhenRequiredPermissionsMissing())
        assertFalse(plugin.onPacketReceived(packet))
    }
}
