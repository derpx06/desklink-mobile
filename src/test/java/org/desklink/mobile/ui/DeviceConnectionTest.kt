/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.ui

import org.desklink.mobile.ui.compose.extensions.device.deviceConnection
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.webrtc.WebRtcConnectionPath
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceConnectionTest {
    @Test
    fun connectionPillFollowsTheLiveTransportAndItsIcePath() {
        assertEquals(DeviceConnection.OFFLINE, deviceConnection(reachable = false, featuresReady = false, path = null))
        // A link alone is not "connected": features only travel once WebRTC is ready.
        assertEquals(DeviceConnection.CONNECTING, deviceConnection(reachable = true, featuresReady = false, path = WebRtcConnectionPath.LAN))
        // LOCAL only for a LAN pair, as the desk decides "on this network" for unlock.
        assertEquals(DeviceConnection.LOCAL, deviceConnection(reachable = true, featuresReady = true, path = WebRtcConnectionPath.LAN))
        assertEquals(DeviceConnection.AWAY, deviceConnection(reachable = true, featuresReady = true, path = WebRtcConnectionPath.DIRECT))
        assertEquals(DeviceConnection.AWAY, deviceConnection(reachable = true, featuresReady = true, path = WebRtcConnectionPath.TURN))
        assertEquals(DeviceConnection.CONNECTED, deviceConnection(reachable = true, featuresReady = true, path = null))
        // The bootstrap link is being re-established while WebRTC still carries every feature: no flicker.
        assertEquals(DeviceConnection.LOCAL, deviceConnection(reachable = false, featuresReady = true, path = WebRtcConnectionPath.LAN))
    }
}

class DeviceTypeGlyphTest {
    /** The desktop sends exactly these two values (DeskLink/src/device_links/device_info.rs). */
    @Test
    fun laptopsAndDesktopsGetTheirOwnGlyph() {
        assertEquals(org.desklink.mobile.R.drawable.ic_device_laptop_32dp, org.desklink.mobile.DeviceType.fromString("laptop").toDrawableId())
        assertEquals(org.desklink.mobile.R.drawable.ic_device_desktop_32dp, org.desklink.mobile.DeviceType.fromString("desktop").toDrawableId())
        // Anything unreported falls back to the generic computer, the monitor.
        assertEquals(org.desklink.mobile.R.drawable.ic_device_desktop_32dp, org.desklink.mobile.DeviceType.fromString("").toDrawableId())
    }

    @Test
    fun `reachable with every recovery attempt failed is unavailable, never offline or connecting`() {
        assertEquals(DeviceConnection.UNAVAILABLE, deviceConnection(reachable = true, featuresReady = false, path = null, unavailable = true))
        assertEquals(DeviceConnection.OFFLINE, deviceConnection(reachable = false, featuresReady = false, path = null, unavailable = true))
        assertEquals(DeviceConnection.LOCAL, deviceConnection(reachable = true, featuresReady = true, path = WebRtcConnectionPath.LAN, unavailable = true))
    }
}
