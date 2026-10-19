/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTransportRoutingTest {
    /**
     * WebRTC is the only feature transport. Accepting LAN features before the
     * handover let the two sides disagree about which transport was live, and
     * a restarted phone's notifications were dropped by a desktop that still
     * believed the old handover was complete.
     */
    @Test
    fun `paired LAN feature is always rejected`() {
        assertTrue(
            shouldRejectPairedLanFeaturePacket(fromWebRtc = false, paired = true, bootstrapPacket = false),
        )
    }

    /** Unpaired traffic is handled by dispatchPacket's unpair path instead. */
    @Test
    fun `unpaired traffic is left to the unpaired handling path`() {
        assertFalse(
            shouldRejectPairedLanFeaturePacket(fromWebRtc = false, paired = false, bootstrapPacket = false),
        )
    }

    @Test
    fun `bootstrap and WebRTC packets are never rejected by the LAN gate`() {
        assertFalse(
            shouldRejectPairedLanFeaturePacket(fromWebRtc = false, paired = true, bootstrapPacket = true),
        )
        assertFalse(
            shouldRejectPairedLanFeaturePacket(fromWebRtc = true, paired = true, bootstrapPacket = false),
        )
    }
}
