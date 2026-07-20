package org.desklink.mobile.protocol.legacykdeconnectv8

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyKdeConnectV8Test {
    @Test
    fun keepsProtocolV8WireIdentifiersUnchanged() {
        assertEquals(8, LegacyKdeConnectV8.PROTOCOL_VERSION)
        assertEquals("kdeconnect.identity", LegacyKdeConnectV8.PACKET_TYPE_IDENTITY)
        assertEquals("kdeconnect.pair", LegacyKdeConnectV8.PACKET_TYPE_PAIR)
        assertEquals("kdeconnect.ping", LegacyKdeConnectV8.PACKET_TYPE_PING)
        assertEquals("kdeconnect.clipboard", LegacyKdeConnectV8.PACKET_TYPE_CLIPBOARD)
        assertEquals("kdeconnect.share.request", LegacyKdeConnectV8.PACKET_TYPE_SHARE_REQUEST)
        assertEquals("kdeconnect.mousepad.request", LegacyKdeConnectV8.PACKET_TYPE_MOUSEPAD_REQUEST)
        assertEquals("kdeconnect.notification", LegacyKdeConnectV8.PACKET_TYPE_NOTIFICATION)
        assertEquals("kdeconnect.mpris", LegacyKdeConnectV8.PACKET_TYPE_MPRIS)
        assertEquals("_kdeconnect._udp", LegacyKdeConnectV8.MDNS_SERVICE_TYPE)
    }
}
