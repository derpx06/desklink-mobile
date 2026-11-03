/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.backends

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.backends.PairingTransport.Kind
import org.desklink.mobile.backends.relay.RelayLink
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pairing seam (docs/PAIRING-OFF-LAN.md). Mirrors `pairing_transport.rs`. */
class PairingTransportTest {
    private fun pair(value: Boolean?, timestamp: Boolean = false) = NetworkPacket(NetworkPacket.PACKET_TYPE_PAIR).apply {
        if (value != null) this["pair"] = value
        if (timestamp) this["timestamp"] = 1_700_000_000L
    }

    /** The relay invariant: with the flag off a relay link carries nothing of a pairing beyond `pair:false`. */
    @Test
    fun withTheFlagOffARelayCarriesNoPairingBeyondPairFalse() {
        for (packet in listOf(
            pair(true), pair(true, timestamp = true), pair(null, timestamp = true), pair(null),
            NetworkPacket("desklink.pair.qr"), NetworkPacket("desklink.clipboard"),
        )) {
            assertFalse("${packet.type} ${packet.serialize()}", PairingTransport.packetMayCross(Kind.RELAY, packet, offLan = false))
        }
        assertTrue(PairingTransport.packetMayCross(Kind.RELAY, pair(false), offLan = false))
        assertTrue(PairingTransport.packetMayCross(Kind.RELAY, pair(false, timestamp = true), offLan = false))
        assertTrue(PairingTransport.packetMayCross(Kind.RELAY, NetworkPacket("desklink.heartbeat"), offLan = false))
        assertTrue(PairingTransport.packetMayCross(Kind.RELAY, NetworkPacket("desklink.webrtc.signal.v1"), offLan = false))
        val text = NetworkPacket(NetworkPacket.PACKET_TYPE_PAIR).apply { this["pair"] = "false" }
        assertFalse("a string is not a boolean", PairingTransport.packetMayCross(Kind.RELAY, text, offLan = false))
    }

    @Test
    fun theFlagOpensOnlyPairingAndOnlyWhenBothSwitchesAreOn() {
        assertFalse(PairingTransport.flagOpen(compiled = false, requested = true))
        assertFalse(PairingTransport.flagOpen(compiled = true, requested = false))
        assertTrue(PairingTransport.flagOpen(compiled = true, requested = true))
        assertTrue(PairingTransport.packetMayCross(Kind.RELAY, pair(true, timestamp = true), offLan = true))
        assertTrue(PairingTransport.packetMayCross(Kind.RELAY, NetworkPacket("desklink.pair.qr"), offLan = true))
        assertFalse("features never cross a relay", PairingTransport.packetMayCross(Kind.RELAY, NetworkPacket("desklink.clipboard"), offLan = true))
        assertTrue(PairingTransport.packetMayCross(Kind.LAN, pair(true, timestamp = true), offLan = false))
    }

    /** A shipped build cannot open the path, whatever the hidden preference says. */
    @Test
    fun aShippedBuildHasTheFlagCompiledOutAndThePreferenceChangesNothing() {
        assertFalse(PairingTransport.OFF_LAN_PAIRING_COMPILED)
        val before = PairingTransport.requested
        try {
            PairingTransport.requested = true
            assertFalse(PairingTransport.offLanPairingEnabled())
            assertFalse(RelayLink.allowedOnRelay(pair(true, timestamp = true)))
            assertFalse(RelayLink.allowedOnRelay(NetworkPacket("desklink.pair.qr")))
            assertTrue(RelayLink.allowedOnRelay(pair(false)))
        } finally {
            PairingTransport.requested = before
        }
    }
}
