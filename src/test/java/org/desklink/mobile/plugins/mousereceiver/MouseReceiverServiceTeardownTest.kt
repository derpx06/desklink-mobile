/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mousereceiver

import android.app.Application
import android.os.Looper
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.plugins.inputdevicesreceiver.Glide
import org.desklink.mobile.plugins.inputdevicesreceiver.InputDevicesReceiverPlugin
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The accessibility service can be switched off at any moment of a glide: nothing may crash and nothing may stay held. */
@RunWith(RobolectricTestRunner::class)
class MouseReceiverServiceTeardownTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val sent = mutableListOf<NetworkPacket>()
    private lateinit var service: MouseReceiverService

    private fun plugin(): InputDevicesReceiverPlugin {
        val device = mockk<Device>(relaxed = true)
        every { device.deviceId } returns "desk-1"
        every { device.sendPacket(any()) } answers { sent += firstArg<NetworkPacket>() }
        return InputDevicesReceiverPlugin().also { it.setContext(context, device); it.onCreate() }
    }

    @Before
    fun connect() {
        service = Robolectric.buildService(MouseReceiverService::class.java).create().get()
        MouseReceiverService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        assertNotNull(MouseReceiverService.instance)
    }

    @After
    fun clean() {
        Glide.end()
        MouseReceiverService.instance = null
    }

    @Test
    fun theServiceGoingAwayWithMovesStillQueuedDoesNotCrashTheMainThread() {
        repeat(50) { MouseReceiverService.move(3, 3) }
        service.onDestroy() // switched off mid-glide, before the posted updates run
        assertNull("the static instance is cleared", MouseReceiverService.instance)
        shadowOf(Looper.getMainLooper()).idle() // would throw an NPE or IllegalArgumentException before
        assertFalse(MouseReceiverService.move(1, 1))
        assertFalse(MouseReceiverService.click())
        assertFalse(MouseReceiverService.setPos(5, 5))
    }

    @Test
    fun aHeldDragDoesNotOutliveTheGlide() {
        val p = plugin()
        Glide.begin("desk-1", Glide.LEFT_EDGE, null)
        assertTrue(MouseReceiverService.longClickSwipe()) // the desk pressed and held
        val held = MouseReceiverService::class.java.getDeclaredField("swipeStoke").apply { isAccessible = true }
        assertNotNull(held.get(service))
        p.stop(release = true)
        assertNull("the drag was released with the glide", held.get(service))
    }

    @Test
    fun aGlideForAnotherLinkIsNotCutShortByTheNewLinkBecomingReady() {
        val p = plugin()
        Glide.begin("desk-1", Glide.LEFT_EDGE, null)
        p.onFeatureReady()
        assertEquals("desk-1", Glide.activeDesk)
    }

    @Test
    fun aGlideIsRefusedWhileTheScreenIsOff() {
        val p = plugin()
        PreferenceManager.getDefaultSharedPreferences(context).edit { putBoolean("glide_allowed_desk-1", true) }
        shadowOf(context.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(false)
        p.onPacketReceived(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES_REQUEST).apply {
            this["exitEdge"] = Glide.LEFT_EDGE
            this["deltax"] = 0
            this["deltay"] = 100
        })
        assertNull(Glide.activeDesk)
        assertEquals("the desk gets its pointer back at once", 1, sent.count { it.type == DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES && it.has("releaseDeltax") })
        // The same request with the screen on is taken.
        shadowOf(context.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(true)
        p.onPacketReceived(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES_REQUEST).apply {
            this["exitEdge"] = Glide.LEFT_EDGE
            this["deltax"] = 0
            this["deltay"] = 100
        })
        assertEquals("desk-1", Glide.activeDesk)
    }
}
