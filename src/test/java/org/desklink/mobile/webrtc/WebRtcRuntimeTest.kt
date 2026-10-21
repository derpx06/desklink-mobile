/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.PeerConnectionFactory

class WebRtcRuntimeTest {
    @Test
    fun `candidates come from every interface so a hotspot laptop is reachable`() {
        val options = WebRtcRuntime.iceNetworkOptions()

        assertTrue(options.disableNetworkMonitor)
        assertEquals(PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK, options.networkIgnoreMask)
    }
}
