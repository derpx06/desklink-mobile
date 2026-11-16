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
            every { sendUserAction(any()) } returns Unit
        }

        plugin.setContext(context, device)

        plugin.getUiMenuEntries().single().onClick(mockk())

        verify(exactly = 1) {
            device.sendUserAction(match { packet ->
                packet.type == "desklink.lock.request" && packet.getBoolean("setLocked")
            })
        }
    }

    @Test
    fun advertisesLockCapabilities() {
        val plugin = LockDevicePlugin()

        assert("desklink.lock" in plugin.supportedPacketTypes)
        assert("desklink.lock.request" in plugin.supportedPacketTypes)
        assert("desklink.lock" in plugin.outgoingPacketTypes)
        assert("desklink.lock.request" in plugin.outgoingPacketTypes)
        assert(plugin.loadPluginWhenRequiredPermissionsMissing())
    }

    @Test
    fun aRequestCarryingOnlyIsLockedNeverLocks() {
        // `isLocked` is the lock-state report's key; only `setLocked` on a request is an instruction.
        io.mockk.mockkStatic(org.desklink.mobile.plugins.mousereceiver.MouseReceiverService::class)
        try {
            every { org.desklink.mobile.plugins.mousereceiver.MouseReceiverService.powerButton() } returns true
            val plugin = LockDevicePlugin()
            val context = mockk<Context>(relaxed = true)
            val device = mockk<Device>(relaxed = true) { every { deviceId } returns "device-id" }
            plugin.setContext(context, device)
            plugin.onPacketReceived(org.desklink.mobile.NetworkPacket("desklink.lock.request").apply { set("isLocked", true) })
            verify(exactly = 0) { org.desklink.mobile.plugins.mousereceiver.MouseReceiverService.powerButton() }
        } finally {
            io.mockk.unmockkAll()
        }
    }
}
