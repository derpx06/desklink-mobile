/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.inputdevicesreceiver

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The glide switch off (or no accessibility service): an enter is handed back at once, and the desk is told `allowed: false`. */
@RunWith(RobolectricTestRunner::class)
class GlideAllowedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val sent = mutableListOf<NetworkPacket>()

    private fun plugin(): InputDevicesReceiverPlugin {
        val device = mockk<Device>(relaxed = true)
        every { device.deviceId } returns "desk-1"
        every { device.sendPacket(any()) } answers { sent += firstArg<NetworkPacket>() }
        return InputDevicesReceiverPlugin().also { it.setContext(app, device); it.onCreate() }
    }

    private fun enter(edge: Int = Glide.LEFT_EDGE) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES_REQUEST).apply {
        this["exitEdge"] = edge
        this["deltax"] = 0.0
        this["deltay"] = 120.0
        this["edgeLength"] = 1080.0
    }

    @After
    fun clear() {
        Glide.end()
        Glide.setAllowed(app, "desk-1", false)
    }

    @Test
    fun withTheSwitchOffAnEnterIsHandedBackAtOnceAndNothingStartsAGlide() {
        Glide.setAllowed(app, "desk-1", false)
        val p = plugin()
        p.onPacketReceived(enter())
        val release = sent.single { it.has("releaseDeltax") }
        assertEquals(0, release.getInt("releaseDeltax", -1))
        assertEquals(120, release.getInt("releaseDeltay", -1))
        assertFalse("never a glide", Glide.isGliding("desk-1"))
        assertNull(Glide.activeDesk)
        val state = sent.last { it.has("allowed") }
        assertEquals(false, state.getBoolean("allowed", true))
        assertFalse("the report is not a release", state.has("releaseDeltax"))
        assertEquals(DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES, state.type)
    }

    @Test
    fun theStateIsReportedOnFeatureReadyAndIsFalseWithoutTheServiceEvenWithTheSwitchOn() {
        Glide.setAllowed(app, "desk-1", true)
        val p = plugin()
        p.onFeatureReady()
        assertEquals("the accessibility service is not running here", false, sent.single().getBoolean("allowed", true))
        assertFalse(p.glideAllowed())
        assertTrue(Glide.allowed(app, "desk-1"))
    }

    @Test
    fun anEnterRefusedAgainAndAgainIsRateLimited() {
        Glide.setAllowed(app, "desk-1", false)
        val p = plugin()
        repeat(200) { p.onPacketReceived(enter()) }
        assertTrue("one hand-back per allowed enter, not 200: ${sent.count { it.has("releaseDeltax") }}", sent.count { it.has("releaseDeltax") } <= Glide.MAX_ENTERS_PER_SECOND)
    }
}
