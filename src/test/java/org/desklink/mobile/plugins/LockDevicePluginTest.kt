package org.desklink.mobile.plugins

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.desklink.mobile.Device
import org.desklink.mobile.plugins.lockdevice.LockDevicePlugin
import org.junit.Test

class LockDevicePluginTest {
    @Test
    fun menuActionSendsLockRequest() {
        val plugin = LockDevicePlugin()
        val context = mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>()
            every { getString(any()) } returns "STRING"
        }
        val device = mockk<Device> {
            every { deviceId } returns "device-id"
            every { sendPacket(any()) } returns Unit
        }

        plugin.setContext(context, device)

        plugin.getUiMenuEntries().single().onClick(mockk())

        verify(exactly = 1) {
            device.sendPacket(match { packet ->
                packet.type == "kdeconnect.lock.request" && packet.getBoolean("setLocked")
            })
        }
    }

    @Test
    fun advertisesLockCapabilities() {
        val plugin = LockDevicePlugin()

        assert("kdeconnect.lock" in plugin.supportedPacketTypes)
        assert("kdeconnect.lock.request" in plugin.supportedPacketTypes)
        assert("kdeconnect.lock" in plugin.outgoingPacketTypes)
        assert("kdeconnect.lock.request" in plugin.outgoingPacketTypes)
        assert(plugin.loadPluginWhenRequiredPermissionsMissing())
    }
}
