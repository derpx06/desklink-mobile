/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.backends.relay

import org.bouncycastle.jce.ECNamedCurveTable
import org.desklink.mobile.backends.relay.RelayLinkCrypto.Ephemeral
import org.desklink.mobile.backends.relay.RelayLinkCrypto.Role
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPrivateKeySpec

/**
 * The cross-language vector. The desktop's `relay_link/crypto.rs` pins the
 * same inputs and outputs (docs/RELAY.md §2); change both or neither.
 */
class RelayLinkCryptoTest {
    private val fpI = "AAAAinitiator"
    private val fpR = "ZZZZresponder"
    private val nonceI = ByteArray(32) { 0x33 }
    private val nonceR = ByteArray(32) { 0x44 }
    private val identityI = """{"deviceId":"phone"}"""
    private val identityR = """{"deviceId":"desktop"}"""
    private val plaintext = """{"type":"desklink.heartbeat"}""".toByteArray()

    private fun fromScalar(byte: Int, nonce: ByteArray): Ephemeral {
        val scalar = BigInteger(1, ByteArray(32) { byte.toByte() })
        val private = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(scalar, RelayLinkCrypto.p256))
        val public = ECNamedCurveTable.getParameterSpec("secp256r1").g.multiply(scalar).normalize().getEncoded(false)
        return Ephemeral(private, public, nonce)
    }

    @Test
    fun pinnedVectorMatchesTheDesktop() {
        val i = fromScalar(0x11, nonceI)
        val r = fromScalar(0x22, nonceR)
        val onI = RelayLinkCrypto.deriveKeys(i, r.public, Role.INITIATOR, fpI, fpR, nonceI, nonceR)
        val onR = RelayLinkCrypto.deriveKeys(r, i.public, Role.RESPONDER, fpI, fpR, nonceI, nonceR)
        assertArrayEquals(onI.send, onR.receive)
        assertArrayEquals(onI.receive, onR.send)

        val transcript = RelayLinkCrypto.transcript(fpI, fpR, i.public, r.public, nonceI, nonceR, identityI, identityR)
        assertEquals("040217e617f0b64439", RelayLinkCrypto.hex(i.public.copyOf(9)))
        assertEquals(
            "b9ddecf9c6f46eaac3e6da802ad29be685b6ab572f38b3bdb633404b0a3aa10e",
            RelayLinkCrypto.hex(RelayLinkCrypto.sha256(transcript)),
        )
        assertEquals("780a247c92688ebe8dfd0fd01d527cd6bdcd9839f2dd6bb4ff689aaa901a4fcf", RelayLinkCrypto.hex(onI.send))
        assertEquals("058d8286624438b81cd9f2ce428b95ce9e4bce9bc97680c93bd4c1cf2d2404ec", RelayLinkCrypto.hex(onI.receive))
        assertEquals(
            "XBeya5M7xNZuQIiUpgn7aeVXgEVd4msLt1o9q/OUk3x0LpOVLZUm8KsVA8cm",
            RelayLinkCrypto.seal(onI.send, 1, plaintext),
        )
        // Our own point codec round-trips the vector's key.
        assertArrayEquals(i.public, RelayLinkCrypto.encodePoint(RelayLinkCrypto.decodePoint(i.public)))
    }

    @Test
    fun framesOpenOnlyWithTheRightKeyAndSequence() {
        val keys = RelayLinkCrypto.deriveKeys(
            fromScalar(0x11, nonceI), fromScalar(0x22, nonceR).public, Role.INITIATOR, fpI, fpR, nonceI, nonceR,
        )
        val frame = RelayLinkCrypto.seal(keys.send, 7, plaintext)
        assertArrayEquals(plaintext, RelayLinkCrypto.open(keys.send, 7, frame))
        assertThrows(IllegalArgumentException::class.java) { RelayLinkCrypto.open(keys.send, 8, frame) }
        assertThrows(IllegalArgumentException::class.java) { RelayLinkCrypto.open(keys.receive, 7, frame) }
        val tampered = RelayLinkCrypto.unb64(frame).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            RelayLinkCrypto.open(keys.send, 7, RelayLinkCrypto.b64(tampered))
        }
    }

    @Test
    fun aPointOffTheCurveIsRefused() {
        val point = fromScalar(0x11, nonceI).public.copyOf()
        point[64] = (point[64].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { RelayLinkCrypto.decodePoint(point) }
    }

    @Test
    fun signaturesBindTheRoleAndTheTranscript() {
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val signature = RelayLinkCrypto.sign(ec.private, Role.RESPONDER, "t".toByteArray())
        assertTrue(RelayLinkCrypto.verify(ec.public, Role.RESPONDER, "t".toByteArray(), signature))
        assertFalse(RelayLinkCrypto.verify(ec.public, Role.INITIATOR, "t".toByteArray(), signature))
        assertFalse(RelayLinkCrypto.verify(ec.public, Role.RESPONDER, "u".toByteArray(), signature))

        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val rsaSignature = RelayLinkCrypto.sign(rsa.private, Role.INITIATOR, "t".toByteArray())
        assertTrue(RelayLinkCrypto.verify(rsa.public, Role.INITIATOR, "t".toByteArray(), rsaSignature))
    }

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            RelayLinkCrypto.hex(RelayLinkCrypto.hkdfSha256(salt, ikm, info, 42)),
        )
    }

    @Test
    fun fingerprintIsUnpaddedBase64UrlOfTheCertificateHash() {
        val fp = RelayLinkCrypto.fingerprint(byteArrayOf(1, 2, 3))
        assertEquals(43, fp.length)
        assertFalse(fp.contains('+') || fp.contains('/') || fp.contains('='))
    }
}
