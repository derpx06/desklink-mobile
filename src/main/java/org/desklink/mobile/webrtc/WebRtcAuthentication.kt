/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import org.json.JSONStringer

data class WebRtcAuthenticationTranscript(
    val sessionAttemptId: String,
    val initiatorDeviceId: String,
    val responderDeviceId: String,
    val sessionId: Long,
    val connectionGeneration: Long,
    val initiatorNonce: String,
    val responderNonce: String,
    val offerSha256: String,
    val answerSha256: String,
    val initiatorDtlsFingerprint: String,
    val responderDtlsFingerprint: String,
    val protocolVersion: Int,
    val timestamp: Long,
) {
    fun canonicalBytes(): ByteArray = JSONStringer()
        .`object`()
        .key("sessionAttemptId").value(sessionAttemptId)
        .key("initiatorDeviceId").value(initiatorDeviceId)
        .key("responderDeviceId").value(responderDeviceId)
        .key("sessionId").value(sessionId)
        .key("connectionGeneration").value(connectionGeneration)
        .key("initiatorNonce").value(initiatorNonce)
        .key("responderNonce").value(responderNonce)
        .key("offerSha256").value(offerSha256)
        .key("answerSha256").value(answerSha256)
        .key("initiatorDtlsFingerprint").value(initiatorDtlsFingerprint)
        .key("responderDtlsFingerprint").value(responderDtlsFingerprint)
        .key("protocolVersion").value(protocolVersion)
        .key("timestamp").value(timestamp)
        .endObject()
        .toString()
        .toByteArray(StandardCharsets.UTF_8)

    fun sign(privateKey: PrivateKey): ByteArray = signatureFor(privateKey.algorithm).run {
        initSign(privateKey)
        update(canonicalBytes())
        sign()
    }

    fun verify(publicKey: PublicKey, signature: ByteArray): Boolean = runCatching {
        signatureFor(publicKey.algorithm).run {
            initVerify(publicKey)
            update(canonicalBytes())
            verify(signature)
        }
    }.getOrDefault(false)

    private fun signatureFor(keyAlgorithm: String): Signature = Signature.getInstance(
        if (keyAlgorithm.equals("RSA", ignoreCase = true)) "SHA256withRSA" else "SHA256withECDSA",
    )
}
