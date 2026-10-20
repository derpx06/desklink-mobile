/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.ui

import org.desklink.mobile.ui.compose.extensions.device.deviceConnection
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceConnectionTest {
    @Test
    fun connectionPillFollowsTheLiveTransport() {
        assertEquals(DeviceConnection.OFFLINE, deviceConnection(reachable = false, featuresReady = false, lan = true, relay = true))
        // A link alone is not "connected": features only travel once WebRTC is ready.
        assertEquals(DeviceConnection.CONNECTING, deviceConnection(reachable = true, featuresReady = false, lan = true, relay = false))
        assertEquals(DeviceConnection.LOCAL, deviceConnection(reachable = true, featuresReady = true, lan = true, relay = true))
        assertEquals(DeviceConnection.AWAY, deviceConnection(reachable = true, featuresReady = true, lan = false, relay = true))
        assertEquals(DeviceConnection.CONNECTED, deviceConnection(reachable = true, featuresReady = true, lan = false, relay = false))
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
}
