/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.backends.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The host the relay auth signs is the bare host, as the desktop's `host_of`. */
class RelayHostTest {
    @Test
    fun theSignedHostIsBare() {
        assertEquals("relay.example.org", relayHost("wss://relay.example.org/v1"))
        assertEquals("relay.example.org", relayHost("wss://relay.example.org:8443/v1"))
        assertEquals("relay.example.org", relayHost("wss://user:pw@relay.example.org:8443/v1"))
        assertEquals("::1", relayHost("wss://[::1]/v1"))
        assertEquals("2001:db8::1", relayHost("wss://[2001:db8::1]:8443/v1"))
    }

    @Test
    fun underscoreHostnamesAreAccepted() {
        assertEquals("my_relay.example.org", relayHost("wss://my_relay.example.org/v1"))
        assertEquals("my_relay.example.org", relayHost("wss://my_relay.example.org:8443/v1"))
        assertEquals("my_relay.example.org", relayHost("wss://user@my_relay.example.org:8443/v1?x=1"))
        assertEquals("my_relay", relayHost("ws://my_relay"))
    }

    @Test
    fun noHostIsNull() {
        assertNull(relayHost(""))
        assertNull(relayHost("wss:///v1"))
        assertNull(relayHost("not a url"))
    }
}

class RelayAuthSignInputTest {
    @org.junit.Test
    fun theBytesTheRelayVerifiesArePinned() {
        // The relay builds format!("{AUTH_CONTEXT}\n{nonce}\n{public_host}") (relay/src/protocol.rs); the desktop signs the same.
        org.junit.Assert.assertEquals(
            "6465736b6c696e6b2d72656c61792d617574682d76310a6e6f6e63652d41420a72656c61792e6578616d706c652e6f7267",
            authSignInput("nonce-AB", "relay.example.org").joinToString("") { "%02x".format(it) },
        )
    }
}
