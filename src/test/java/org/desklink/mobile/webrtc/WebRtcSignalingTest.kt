/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class WebRtcSignalingTest {
    @Test
    fun wrongDestinationIsRejected() {
        val message = WebRtcSignalingMessage(
            signalingVersion = 1,
            requestId = "request-1",
            sessionAttemptId = "attempt-1",
            fromDeviceId = "desktop",
            toDeviceId = "phone",
            timestamp = System.currentTimeMillis(),
            messageType = SignalingMessageType.OFFER,
            payload = JSONObject().put("sdp", "offer"),
            signature = "signature",
        )
        assertThrows(IllegalArgumentException::class.java) {
            message.validateFor("other-phone", System.currentTimeMillis())
        }
    }

    @Test
    fun selfSenderIsRejected() {
        val message = WebRtcSignalingMessage(
            signalingVersion = 1,
            requestId = "request-1",
            sessionAttemptId = "attempt-1",
            fromDeviceId = "phone",
            toDeviceId = "phone",
            timestamp = System.currentTimeMillis(),
            messageType = SignalingMessageType.ICE_CANDIDATE,
            payload = JSONObject().put("candidate", "candidate"),
            signature = "signature",
        )
        assertThrows(IllegalArgumentException::class.java) {
            message.validateFor("phone", System.currentTimeMillis())
        }
    }
}
