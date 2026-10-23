/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.inputdevicesreceiver

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The desk's pointer must never stay on this phone: whichever way the glide ends, [Glide] is clear and the desk is told. */
@RunWith(RobolectricTestRunner::class)
class GlideLetGoTest {
    private val sent = mutableListOf<org.desklink.mobile.NetworkPacket>()

    private fun plugin(id: String = "desk-1"): InputDevicesReceiverPlugin {
        val device = mockk<Device>(relaxed = true)
        every { device.deviceId } returns id
        every { device.sendPacket(any()) } answers { sent += firstArg<org.desklink.mobile.NetworkPacket>() }
        return InputDevicesReceiverPlugin().also {
            it.setContext(ApplicationProvider.getApplicationContext<Application>(), device)
            it.onCreate()
        }
    }

    @After
    fun clear() = Glide.end()

    @Test
    fun theLinkGoingAwayEndsTheGlideWithoutAPacketThatCannotBeSent() {
        val p = plugin()
        Glide.begin("desk-1", Glide.LEFT_EDGE, null)
        p.onFeatureLost()
        assertNull(Glide.activeDesk)
        assertFalse(Glide.isGliding("desk-1"))
        assertEquals("a dead link carries nothing", 0, sent.size)
    }

    @Test
    fun aLinkLostWhileAnotherDeskGlidesLeavesThatGlideAlone() {
        val p = plugin("desk-2")
        Glide.begin("desk-1", Glide.LEFT_EDGE, null)
        p.onFeatureLost()
        assertEquals("desk-1", Glide.activeDesk)
    }

    @Test
    fun theScreenGoingOffHandsThePointerBackToTheDesk() {
        val p = plugin()
        Glide.begin("desk-1", Glide.LEFT_EDGE, 1080.0)
        p.stop(release = true)
        assertNull(Glide.activeDesk)
        assertEquals(1, sent.count { it.type == org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES })
    }
}
