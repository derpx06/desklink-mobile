/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.backends.relay

import org.bouncycastle.util.encoders.Base64
import org.bouncycastle.util.encoders.Hex
import org.desklink.mobile.webrtc.identitySignature
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The end-to-end channel two paired devices run *through* the relay
 * (docs/RELAY.md §2). The relay is operated by someone else, so nothing it
 * carries may be readable or forgeable by it: each side proves itself with the
 * key behind its pairing certificate — checked against the certificate stored
 * at pairing, never one the relay supplies — over a transcript binding both
 * ephemeral keys, both nonces and both identity packets. The ECDH secret then
 * keys AES-256-GCM in each direction.
 *
 * Byte-for-byte the desktop's `relay_link/crypto.rs`; the vector in
 * `RelayLinkCryptoTest` is pinned on both sides.
 */
object RelayLinkCrypto {
    const val LINK_VERSION = 1
    private const val CONTEXT = "desklink-relay-link-v1"
    private val DATA_AAD = "$CONTEXT data".toByteArray(Charsets.UTF_8)
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    enum class Role(val label: String) {
        INITIATOR("initiator\n"),
        RESPONDER("responder\n"),
    }

    /** One side's ephemeral contribution: the key, its uncompressed point, a 32-byte nonce. */
    class Ephemeral(internal val key: PrivateKey, val public: ByteArray, val nonce: ByteArray) {
        companion object {
            fun generate(): Ephemeral {
                val pair = KeyPairGenerator.getInstance("EC").apply {
                    initialize(ECGenParameterSpec("secp256r1"), random)
                }.generateKeyPair()
                return Ephemeral(
                    pair.private,
                    encodePoint((pair.public as ECPublicKey).w),
                    ByteArray(32).also(random::nextBytes),
                )
            }
        }
    }

    class LinkKeys(val send: ByteArray, val receive: ByteArray)

    /** P-256 domain parameters, taken from a generated key: portable across JDK and Android providers. */
    internal val p256: ECParameterSpec by lazy {
        (KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().public as ECPublicKey).params
    }

    /** A device's relay address: base64url, unpadded, of SHA-256(certificate DER). */
    fun fingerprint(certificateDer: ByteArray): String =
        b64(sha256(certificateDer)).replace('+', '-').replace('/', '_').trimEnd('=')

    fun isInitiator(localFp: String, remoteFp: String): Boolean = localFp < remoteFp

    /** Everything both signatures cover. Field order and separators are part of the protocol. */
    fun transcript(
        fpInitiator: String,
        fpResponder: String,
        ephInitiator: ByteArray,
        ephResponder: ByteArray,
        nonceInitiator: ByteArray,
        nonceResponder: ByteArray,
        identityInitiator: String,
        identityResponder: String,
    ): ByteArray = listOf(
        CONTEXT,
        fpInitiator,
        fpResponder,
        b64(ephInitiator),
        b64(ephResponder),
        b64(nonceInitiator),
        b64(nonceResponder),
        hex(sha256(identityInitiator.toByteArray(Charsets.UTF_8))),
        hex(sha256(identityResponder.toByteArray(Charsets.UTF_8))),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    fun sign(key: PrivateKey, role: Role, transcript: ByteArray): String =
        b64(identitySignature(key.algorithm).run {
            initSign(key)
            update(role.label.toByteArray(Charsets.UTF_8))
            update(transcript)
            sign()
        })

    fun verify(key: PublicKey, role: Role, transcript: ByteArray, signature: String): Boolean = runCatching {
        identitySignature(key.algorithm).run {
            initVerify(key)
            update(role.label.toByteArray(Charsets.UTF_8))
            update(transcript)
            verify(unb64(signature))
        }
    }.getOrDefault(false)

    fun deriveKeys(
        local: Ephemeral,
        peerPublic: ByteArray,
        role: Role,
        fpInitiator: String,
        fpResponder: String,
        nonceInitiator: ByteArray,
        nonceResponder: ByteArray,
    ): LinkKeys {
        val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(decodePoint(peerPublic), p256))
        val secret = KeyAgreement.getInstance("ECDH").run {
            init(local.key)
            doPhase(peer, true)
            generateSecret()
        }
        val okm = hkdfSha256(
            salt = nonceInitiator + nonceResponder,
            ikm = secret,
            info = "$CONTEXT keys\n$fpInitiator\n$fpResponder".toByteArray(Charsets.UTF_8),
            length = 64,
        )
        val toResponder = okm.copyOfRange(0, 32)
        val toInitiator = okm.copyOfRange(32, 64)
        return if (role == Role.INITIATOR) LinkKeys(toResponder, toInitiator) else LinkKeys(toInitiator, toResponder)
    }

    /** RFC 5869. */
    fun hkdfSha256(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            // An empty salt is a zero-length HMAC key; SecretKeySpec refuses that.
            init(SecretKeySpec(key.takeIf { it.isNotEmpty() } ?: ByteArray(32), "HmacSHA256"))
            parts.forEach(::update)
            doFinal()
        }
        require(length <= 255 * 32) { "HKDF output too long" }
        val prk = hmac(salt, ikm)
        val okm = java.io.ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (okm.size() < length) {
            block = hmac(prk, block, info, byteArrayOf(counter.toByte()))
            okm.write(block)
            counter++
        }
        return okm.toByteArray().copyOf(length)
    }

    /** `ct` for one frame: AES-256-GCM, IV = 4 zero bytes ‖ seq (big-endian u64). */
    fun seal(key: ByteArray, seq: Long, plaintext: ByteArray): String = b64(aead(Cipher.ENCRYPT_MODE, key, seq, plaintext))

    fun open(key: ByteArray, seq: Long, sealed: String): ByteArray {
        val bytes = runCatching { unb64(sealed) }.getOrNull()
        require(bytes != null && bytes.size >= TAG_BITS / 8) { "Malformed relay frame" }
        return runCatching { aead(Cipher.DECRYPT_MODE, key, seq, bytes) }
            .getOrElse { throw IllegalArgumentException("Relay frame failed authentication") }
    }

    private fun aead(mode: Int, key: ByteArray, seq: Long, input: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            val iv = ByteBuffer.allocate(12).putInt(0).putLong(seq).array()
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(DATA_AAD)
            doFinal(input)
        }

    /** Uncompressed SEC1: 0x04 ‖ X ‖ Y, 32 bytes each. */
    fun encodePoint(point: ECPoint): ByteArray = byteArrayOf(4) + fixed32(point.affineX) + fixed32(point.affineY)

    /** The inverse of [encodePoint], refusing anything not on P-256 (an invalid-curve point leaks key bits). */
    fun decodePoint(bytes: ByteArray): ECPoint {
        require(bytes.size == 65 && bytes[0] == 4.toByte()) { "Invalid relay link key" }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        val curve = p256.curve
        val p = (curve.field as ECFieldFp).p
        require(x < p && y < p) { "Invalid relay link key" }
        val onCurve = y.pow(2).mod(p) == x.pow(3).add(curve.a.multiply(x)).add(curve.b).mod(p)
        require(onCurve) { "Invalid relay link key" }
        return ECPoint(x, y)
    }

    private fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Standard alphabet, padded. */
    fun b64(bytes: ByteArray): String = Base64.toBase64String(bytes)

    fun unb64(text: String): ByteArray = Base64.decode(text)

    fun hex(bytes: ByteArray): String = Hex.toHexString(bytes)
}
