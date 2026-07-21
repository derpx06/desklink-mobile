package org.desklink.mobile.protocol

import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskLinkProtocolTest {
    @Test
    fun nativeContractUsesDeskLinkV9AndDeskLinkDiscovery() {
        assertEquals(9, DeskLinkProtocol.PROTOCOL_VERSION)
        assertEquals("_desklink._udp", DeskLinkProtocol.MDNS_SERVICE_TYPE)

        val packetTypes = listOf(
            DeskLinkProtocol.PACKET_TYPE_IDENTITY,
            DeskLinkProtocol.PACKET_TYPE_PAIR,
            DeskLinkProtocol.PACKET_TYPE_PING,
            DeskLinkProtocol.PACKET_TYPE_CLIPBOARD,
            DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_NOTIFICATION,
            DeskLinkProtocol.PACKET_TYPE_BATTERY,
            DeskLinkProtocol.PACKET_TYPE_MPRIS,
            DeskLinkProtocol.PACKET_TYPE_SYSTEMVOLUME,
            DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_PRESENTER,
            DeskLinkProtocol.PACKET_TYPE_SCREEN_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_SCREEN_FRAME,
            DeskLinkProtocol.PACKET_TYPE_CONTACTS_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_SMS_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_TELEPHONY_REQUEST,
            DeskLinkProtocol.PACKET_TYPE_CONNECTIVITY_REPORT,
        )

        assertTrue(packetTypes.all { it.startsWith("desklink.") })
        assertEquals("desklink.pair", DeskLinkProtocol.PACKET_TYPE_PAIR)
    }
}
