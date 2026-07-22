/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import org.json.JSONObject

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
    fun canonicalBytes(): ByteArray = JSONObject()
        .put("sessionAttemptId", sessionAttemptId)
        .put("initiatorDeviceId", initiatorDeviceId)
        .put("responderDeviceId", responderDeviceId)
        .put("sessionId", sessionId)
        .put("connectionGeneration", connectionGeneration)
        .put("initiatorNonce", initiatorNonce)
        .put("responderNonce", responderNonce)
        .put("offerSha256", offerSha256)
        .put("answerSha256", answerSha256)
        .put("initiatorDtlsFingerprint", initiatorDtlsFingerprint)
        .put("responderDtlsFingerprint", responderDtlsFingerprint)
        .put("protocolVersion", protocolVersion)
        .put("timestamp", timestamp)
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
