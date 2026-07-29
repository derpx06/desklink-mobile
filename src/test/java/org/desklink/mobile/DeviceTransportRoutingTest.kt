/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTransportRoutingTest {
    @Test
    fun `paired LAN feature is rejected before WebRTC handover`() {
        assertTrue(
            shouldRejectPairedLanFeaturePacket(
                fromWebRtc = false,
                paired = true,
                bootstrapPacket = false,
                webRtcFeatureTransportReady = false,
            ),
        )
    }

    @Test
    fun `paired LAN feature is rejected after WebRTC handover`() {
        assertTrue(
            shouldRejectPairedLanFeaturePacket(
                fromWebRtc = false,
                paired = true,
                bootstrapPacket = false,
                webRtcFeatureTransportReady = true,
            ),
        )
    }

    @Test
    fun `ordinary feature has no transport before WebRTC handover`() {
        assertFalse(webRtcFeatureTransportShouldHandlePacket(false))
        assertTrue(webRtcFeatureTransportShouldHandlePacket(true))
    }

    @Test
    fun `bootstrap and WebRTC packets are never rejected by LAN transition gate`() {
        assertFalse(
            shouldRejectPairedLanFeaturePacket(
                fromWebRtc = false,
                paired = true,
                bootstrapPacket = true,
                webRtcFeatureTransportReady = true,
            ),
        )
        assertFalse(
            shouldRejectPairedLanFeaturePacket(
                fromWebRtc = true,
                paired = true,
                bootstrapPacket = false,
                webRtcFeatureTransportReady = true,
            ),
        )
    }
}
