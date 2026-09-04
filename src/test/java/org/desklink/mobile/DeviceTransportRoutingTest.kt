/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTransportRoutingTest {
    /**
     * The bootstrap link is mutually authenticated with pinned certificates, so
     * it is a valid carrier until the WebRTC transport takes over. Rejecting it
     * here left the application with no transport at all on any network where
     * ICE could not complete.
     */
    @Test
    fun `paired LAN feature is accepted before WebRTC handover`() {
        assertFalse(
            shouldRejectPairedLanFeaturePacket(
                fromWebRtc = false,
                paired = true,
                bootstrapPacket = false,
                webRtcFeatureTransportReady = false,
            ),
        )
    }

    /**
     * This gate is about the paired transport transition only. An unpaired
     * device is handled separately by `dispatchPacket`'s `unpair()` path, so
     * this gate deliberately passes it through rather than rejecting it.
     */
    @Test
    fun `unpaired traffic is left to the unpaired handling path`() {
        assertFalse(
            shouldRejectPairedLanFeaturePacket(
                fromWebRtc = false,
                paired = false,
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
