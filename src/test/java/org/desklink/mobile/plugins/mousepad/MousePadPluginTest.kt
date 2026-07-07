package org.desklink.mobile.plugins.mousepad

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.desklink.mobile.Device
import org.junit.Test

class MousePadPluginTest {
    @Test
    fun sendMousePositionSendsAbsoluteCoordinates() {
        val plugin = MousePadPlugin()
        val context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>()
        }
        val device = mockk<Device> {
            every { deviceId } returns "device-id"
            every { sendPacket(any()) } returns Unit
        }

        plugin.setContext(context, device)
        plugin.sendMousePosition(320, 180)

        verify(exactly = 1) {
            device.sendPacket(match { packet ->
                packet.type == MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST &&
                    packet.getInt("x") == 320 &&
                    packet.getInt("y") == 180
            })
        }
    }
}
