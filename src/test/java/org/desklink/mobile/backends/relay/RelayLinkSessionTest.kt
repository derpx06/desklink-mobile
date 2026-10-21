/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.backends.relay

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.backends.relay.RelayLinkSession.Event
import org.desklink.mobile.backends.relay.RelayLinkSession.Identities
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/** Mirrors the desktop's `relay_link/handshake.rs` tests. */
class RelayLinkSessionTest {
    private val phoneId = "phone0000000000000000000000000000"
    private val desktopId = "desktop00000000000000000000000000"
    private val otherId = "other000000000000000000000000000000"

    private fun key(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun identity(id: String): String = NetworkPacket(NetworkPacket.PACKET_TYPE_IDENTITY).also {
        it["deviceId"] = id
        it["deviceName"] = id
        it["deviceType"] = "phone"
        it["protocolVersion"] = 9
    }.serialize().trimEnd('\n')

    private val phoneKey = key()
    private val desktopKey = key()

    /** The phone ("AAA", initiator) and the desktop ("ZZZ", responder). */
    private fun phone(localIdentity: String = identity(phoneId), localKey: KeyPair = phoneKey) =
        RelayLinkSession(Identities("AAA", "ZZZ", localKey.private, localIdentity, desktopKey.public, desktopId))

    private fun desktop(localKey: KeyPair = desktopKey) =
        RelayLinkSession(Identities("ZZZ", "AAA", localKey.private, identity(desktopId), phoneKey.public, phoneId))

    private fun sent(events: List<Event>): JSONObject = events.filterIsInstance<Event.Send>().first().message

    /** Round-trips through JSON text, as the relay does. */
    private fun RelayLinkSession.take(message: JSONObject) = receive(JSONObject(message.toString()))

    private fun handshake(phone: RelayLinkSession, desktop: RelayLinkSession) {
        val h2 = sent(desktop.take(phone.start()))
        val events = phone.take(h2)
        assertTrue((events[1] as Event.Established).identity.contains(desktopId))
        val established = desktop.take(sent(events))
        assertTrue((established[0] as Event.Established).identity.contains(phoneId))
    }

    @Test
    fun bothSidesEstablishAndExchangePacketsInBothDirections() {
        val phone = phone()
        val desktop = desktop()
        handshake(phone, desktop)
        assertArrayEquals("to desktop".toByteArray(), (desktop.take(phone.seal("to desktop".toByteArray()))[0] as Event.Packet).bytes)
        val frame = desktop.seal("to phone".toByteArray())
        assertArrayEquals("to phone".toByteArray(), (phone.take(frame)[0] as Event.Packet).bytes)
        // A replayed frame is refused.
        assertThrows(IllegalArgumentException::class.java) { phone.take(frame) }
    }

    @Test
    fun theResponderAsksTheInitiatorToStartAndASecondKickIsIgnored() {
        val phone = phone()
        val desktop = desktop()
        val kick = desktop.start()
        assertEquals("kick", kick.getString("k"))
        val h1 = sent(phone.take(kick))
        assertEquals("h1", h1.getString("k"))
        // A second kick while that h1 is in flight changes nothing, so the h2 still matches.
        assertTrue(phone.take(kick).isEmpty())
        val h2 = sent(desktop.take(h1))
        assertEquals("h2", h2.getString("k"))
        phone.take(h2)
        assertTrue(phone.isEstablished)
    }

    @Test
    fun aRelayCannotImpersonateEitherSide() {
        val phone = phone()
        val desktop = desktop()
        // The relay answers the phone's h1 itself, with its own key.
        val h1 = phone.start()
        val impostor = desktop(localKey = key())
        assertThrows(IllegalArgumentException::class.java) { phone.take(sent(impostor.take(h1))) }

        // An h1 claiming to be a different device is refused by the desktop.
        assertThrows(IllegalArgumentException::class.java) { desktop.take(phone(identity(otherId)).start()) }
    }

    @Test
    fun aTamperedH2OrFinFails() {
        var phone = phone()
        var desktop = desktop()
        val h2 = sent(desktop.take(phone.start()))
        h2.put("nonce", RelayLinkCrypto.b64(ByteArray(32) { 9 }))
        assertThrows(IllegalArgumentException::class.java) { phone.take(h2) }

        phone = phone()
        desktop = desktop()
        val fin = sent(phone.take(sent(desktop.take(phone.start()))))
        fin.put("sig", RelayLinkCrypto.b64(ByteArray(64) { 1 }))
        assertThrows(IllegalArgumentException::class.java) { desktop.take(fin) }
        assertFalse(desktop.isEstablished)
    }

    @Test
    fun dataBeforeTheHandshakeIsRefused() {
        val phone = phone()
        val desktop = desktop()
        handshake(phone, desktop)
        assertThrows(IllegalArgumentException::class.java) { desktop().take(phone.seal(byteArrayOf(1))) }
    }

    @Test
    fun onlySignalingHeartbeatsAndUnpairMayCrossARelayLink() {
        assertTrue(RelayLink.allowedOnRelay(NetworkPacket("desklink.webrtc.signal.v1")))
        assertTrue(RelayLink.allowedOnRelay(NetworkPacket("desklink.heartbeat")))
        assertFalse(RelayLink.allowedOnRelay(NetworkPacket(NetworkPacket.PACKET_TYPE_IDENTITY)))
        assertFalse(RelayLink.allowedOnRelay(NetworkPacket("desklink.clipboard")))
        val pair = NetworkPacket(NetworkPacket.PACKET_TYPE_PAIR)
        assertFalse(RelayLink.allowedOnRelay(pair))
        pair["pair"] = true
        assertFalse(RelayLink.allowedOnRelay(pair))
        pair["pair"] = false
        assertTrue(RelayLink.allowedOnRelay(pair))
    }

    @Test
    fun aNewH1RestartsAnEstablishedResponder() {
        val phone = phone()
        val desktop = desktop()
        handshake(phone, desktop)
        handshake(phone, desktop)
        val frame = phone.seal("after restart".toByteArray())
        assertEquals(1L, frame.getLong("seq"))
        desktop.take(frame)
    }
}
