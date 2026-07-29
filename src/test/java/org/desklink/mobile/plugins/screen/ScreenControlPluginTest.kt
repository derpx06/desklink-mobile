package org.desklink.mobile.plugins.screen

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.desklink.mobile.Device
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenControlPluginTest {
    @Test
    fun advertisesDeskLinkScreenPacketsInBothDirections() {
        val plugin = ScreenControlPlugin()
        val supported = plugin.supportedPacketTypes.toSet()
        val outgoing = plugin.outgoingPacketTypes.toSet()

        for (packetType in ScreenControlPlugin.SCREEN_PACKET_TYPES) {
            assertTrue("missing supported packet $packetType", packetType in supported)
            assertTrue("missing outgoing packet $packetType", packetType in outgoing)
        }
        assertTrue(DeskLinkProtocol.PACKET_TYPE_SCREEN_FRAME !in supported)
        assertTrue(DeskLinkProtocol.PACKET_TYPE_SCREEN_FRAME !in outgoing)
    }

    @Test
    fun requestPacketContainsStreamRoleAndQuality() {
        val packet = ScreenControlPlugin.createScreenRequestPacket(
            role = ScreenControlPlugin.ROLE_DESKTOP_SCREEN,
            maxDimension = 1280,
            fps = 6,
            quality = 60
        )

        assertTrue(packet.type == ScreenControlPlugin.PACKET_TYPE_SCREEN_REQUEST)
        assertTrue(packet.getString("role") == ScreenControlPlugin.ROLE_DESKTOP_SCREEN)
        assertTrue(packet.getInt("maxDimension") == 1280)
        assertTrue(packet.getInt("fps") == 6)
        assertTrue(packet.getInt("quality") == 60)
    }

    @Test
    fun requestDesktopScreenSendsDefaultLanQualityRequest() {
        val plugin = ScreenControlPlugin()
        val context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>()
            every { getString(any()) } returns "Screen control"
        }
        val device = mockk<Device> {
            every { deviceId } returns "device-id"
            every { sendPacket(any()) } returns Unit
        }

        plugin.setContext(context, device)
        plugin.requestDesktopScreen()

        verify(exactly = 1) {
            device.sendPacket(match { packet ->
                packet.type == ScreenControlPlugin.PACKET_TYPE_SCREEN_REQUEST &&
                    packet.getString("role") == ScreenControlPlugin.ROLE_DESKTOP_SCREEN &&
                    packet.getInt("maxDimension") == 1280 &&
                    packet.getInt("fps") == 6 &&
                    packet.getInt("quality") == 60
            })
        }
    }

    @Test
    fun stoppingOneScreenSessionSendsOnlyOneStopPacket() {
        val plugin = ScreenControlPlugin()
        val context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>()
            every { getString(any()) } returns "Screen control"
            every { stopService(any()) } returns true
        }
        val device = mockk<Device> {
            every { deviceId } returns "device-id"
            every { sendPacket(any()) } returns Unit
        }

        plugin.setContext(context, device)
        plugin.requestDesktopScreen()
        plugin.stopScreen()
        plugin.stopScreen()

        verify(exactly = 1) {
            device.sendPacket(match { packet ->
                packet.type == ScreenControlPlugin.PACKET_TYPE_SCREEN_STOP
            })
        }
    }
}
