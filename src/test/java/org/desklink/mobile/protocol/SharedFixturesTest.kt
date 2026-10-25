/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.protocol

import org.desklink.mobile.QrPairing
import org.desklink.mobile.backends.relay.RelayLinkCrypto.Ephemeral
import org.desklink.mobile.backends.relay.RelayLinkCrypto
import org.desklink.mobile.backends.relay.RelayLinkCrypto.Role
import org.desklink.mobile.backends.relay.authSignInput
import org.desklink.mobile.plugins.folders.FolderSync
import org.desklink.mobile.webrtc.WebRtcFeatureProfile
import org.bouncycastle.jce.ECNamedCurveTable
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.ECPrivateKeySpec

/**
 * The vectors shared with the desktop: each file under test resources `shared/` is the workspace copy
 * (protocol/testdata/desklink_v9/, compared by protocol/check-fixtures.sh); this reads it and recomputes with the phone's code.
 */
class SharedFixturesTest {
    private fun fixture(name: String) = JSONObject(javaClass.getResourceAsStream("/shared/$name.json")!!.bufferedReader().readText())
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun strings(array: org.json.JSONArray) = (0 until array.length()).map { array.getString(it) }

    @Test
    fun qrProof() {
        val f = fixture("qr_proof")
        fun bytes(key: String) = ByteArray(32) { f.getInt(key).toByte() }
        assertEquals(f.getString("proofHex"), hex(QrPairing.proofBytes(bytes("secretByte"), f.getString("desk"), f.getString("phone"), bytes("nonceDeskByte"), bytes("noncePhoneByte"))))
    }

    @Test
    fun relayEndToEnd() {
        val f = fixture("relay_e2e")
        fun ephemeral(byte: Int, nonce: ByteArray): Ephemeral {
            val scalar = BigInteger(1, ByteArray(32) { byte.toByte() })
            val private = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(scalar, RelayLinkCrypto.p256))
            val public = ECNamedCurveTable.getParameterSpec("secp256r1").g.multiply(scalar).normalize().getEncoded(false)
            return Ephemeral(private, public, nonce)
        }
        val nonceI = ByteArray(32) { f.getInt("nonceInitiatorByte").toByte() }
        val nonceR = ByteArray(32) { f.getInt("nonceResponderByte").toByte() }
        val i = ephemeral(f.getInt("scalarInitiatorByte"), nonceI)
        val r = ephemeral(f.getInt("scalarResponderByte"), nonceR)
        val fpI = f.getString("fpInitiator")
        val fpR = f.getString("fpResponder")
        val keys = RelayLinkCrypto.deriveKeys(i, r.public, Role.INITIATOR, fpI, fpR, nonceI, nonceR)
        val transcript = RelayLinkCrypto.transcript(fpI, fpR, i.public, r.public, nonceI, nonceR, f.getString("identityInitiator"), f.getString("identityResponder"))
        assertEquals(f.getString("initiatorPublicPrefixHex"), RelayLinkCrypto.hex(i.public.copyOf(9)))
        assertEquals(f.getString("transcriptSha256Hex"), RelayLinkCrypto.hex(RelayLinkCrypto.sha256(transcript)))
        assertEquals(f.getString("initiatorSendKeyHex"), RelayLinkCrypto.hex(keys.send))
        assertEquals(f.getString("initiatorReceiveKeyHex"), RelayLinkCrypto.hex(keys.receive))
        assertEquals(f.getString("sealedSeq1"), RelayLinkCrypto.seal(keys.send, 1, f.getString("plaintext").toByteArray()))
    }

    @Test
    fun caseClashWinners() {
        val f = fixture("case_clash")
        val names = strings(f.getJSONArray("names"))
        val losers = FolderSync.caseClashes(names)
        assertEquals(strings(f.getJSONArray("winners")), names.filter { !FolderSync.isLeftOut(it, losers) })
    }

    @Test
    fun relayAuthSignInput() {
        val f = fixture("auth_sign_input")
        assertEquals(f.getString("hex"), hex(authSignInput(f.getString("nonce"), f.getString("host"))))
    }

    @Test
    fun removedTypesAreOutsideTheProfile() {
        strings(fixture("removed_types").getJSONArray("types")).forEach { assertFalse(it, WebRtcFeatureProfile.allows(it)) }
    }

    @Test
    fun profileListIsThePhonesProfile() {
        assertEquals(strings(fixture("profile").getJSONArray("phone")).sorted(), WebRtcFeatureProfile.all.sorted())
    }
}
