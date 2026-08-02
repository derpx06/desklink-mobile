package org.desklink.mobile.webrtc

import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcFeatureProfileTest {
    @Test
    fun initialProfileContainsFileSharingWithPingAndFindPhone() {
        val capabilities = setOf(
            DeskLinkProtocol.PACKET_TYPE_PING,
            DeskLinkProtocol.PACKET_TYPE_FINDMYPHONE_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_CLIPBOARD,
            DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1,
        )

        assertEquals(
            listOf(
                DeskLinkProtocol.PACKET_TYPE_FINDMYPHONE_REQUEST,
                DeskLinkProtocol.PACKET_TYPE_PING,
                DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST,
            ),
            WebRtcFeatureProfile.capabilities(capabilities),
        )
        assertTrue(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_PING))
        assertTrue(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_FINDMYPHONE_REQUEST))
        assertTrue(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST))
        assertFalse(WebRtcFeatureProfile.allows(DeskLinkProtocol.PACKET_TYPE_CLIPBOARD))
    }
}
