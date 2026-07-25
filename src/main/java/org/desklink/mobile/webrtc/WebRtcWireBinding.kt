/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

/**
 * Binding shared by both ends of one signed WebRTC negotiation attempt.
 *
 * Local DeviceSession IDs are intentionally not put on the wire: Android and
 * Linux allocate them independently. The attempt ID is already signed by the
 * paired identities, so this deterministic 60-bit value is stable on both
 * peers and changes whenever a new peer connection is created.
 */
data class WebRtcWireBinding(
    val senderDeviceId: String,
    val peerDeviceId: String,
    val sessionId: Long,
    val generation: Long = GENERATION,
) {
    companion object {
        const val GENERATION: Long = 1

        fun fromAttempt(senderDeviceId: String, peerDeviceId: String, attemptId: String): WebRtcWireBinding =
            WebRtcWireBinding(senderDeviceId, peerDeviceId, sessionIdFromAttempt(attemptId))

        fun sessionIdFromAttempt(attemptId: String): Long {
            val hex = attemptId.replace("-", "")
            require(hex.length == 32 && hex.all(::isHexDigit)) {
                "WebRTC session attempt ID must be a UUID"
            }
            return hex.substring(0, 15).toLong(16)
        }

        private fun isHexDigit(value: Char): Boolean =
            value in '0'..'9' || value in 'a'..'f' || value in 'A'..'F'
    }
}
