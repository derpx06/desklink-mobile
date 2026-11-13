/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcSignalingTest {
    @Test
    fun canonicalRecordMatchesRustFixture() {
        val message = WebRtcSignalingMessage(
            signalingVersion = 1,
            requestId = "request-1",
            sessionAttemptId = "attempt-1",
            fromDeviceId = "desktop",
            toDeviceId = "phone",
            timestamp = 1000,
            messageType = SignalingMessageType.OFFER,
            payload = JSONObject().put("sdp", "offer"),
            signature = "",
        )
        assertEquals(
            "1:19:request-19:attempt-17:desktop5:phone4:10005:offer9:sdp=offer",
            String(message.canonicalBytes()),
        )
    }

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

    @Test
    fun restartRequestUsesTheCrossPlatformEmptyPayloadRecord() {
        val message = WebRtcSignalingMessage(
            signalingVersion = 1,
            requestId = "request-1",
            sessionAttemptId = "restart-request-attempt",
            fromDeviceId = "desktop",
            toDeviceId = "phone",
            timestamp = 1000,
            messageType = SignalingMessageType.RESTART_REQUEST,
            payload = JSONObject(),
            signature = "signature",
        )

        assertEquals(
            "1:19:request-123:restart-request-attempt7:desktop5:phone4:100015:restart_request0:",
            String(message.canonicalBytes()),
        )
    }

    @Test
    fun onlyTheDeterministicInitiatorHonorsRestartRequests() {
        assertEquals(true, WebRtcSessionCoordinator.shouldInitiateForRestartRequest("a", "b"))
        assertEquals(false, WebRtcSessionCoordinator.shouldInitiateForRestartRequest("b", "a"))
        assertEquals(false, WebRtcSessionCoordinator.shouldInitiateForRestartRequest("a", "a"))
    }

    @Test
    fun bootstrapReconnectRebuildsOnlyWhenThePeerIsNotActive() {
        assertEquals(
            true,
            WebRtcSessionCoordinator.shouldRestartAfterBootstrapReconnect("a", "b", false),
        )
        assertEquals(
            false,
            WebRtcSessionCoordinator.shouldRestartAfterBootstrapReconnect("b", "a", false),
        )
        assertEquals(
            false,
            WebRtcSessionCoordinator.shouldRestartAfterBootstrapReconnect("a", "b", true),
        )
    }

    @Test
    fun `a stalled attempt started by recovery is replaced by the LAN heartbeat like any other`() {
        val stall = WebRtcSessionCoordinator.ATTEMPT_STALL_MS
        // A recovery whose attempt has not started yet: left alone.
        assertTrue(WebRtcSessionCoordinator.negotiationInFlight(true, false, 0))
        assertFalse(WebRtcSessionCoordinator.negotiationInFlight(false, false, 0))
        // A young attempt is left alone, a stalled one replaced, recovery or not.
        assertTrue(WebRtcSessionCoordinator.negotiationInFlight(true, true, stall - 1))
        assertFalse(WebRtcSessionCoordinator.negotiationInFlight(true, true, stall))
        assertFalse(WebRtcSessionCoordinator.negotiationInFlight(false, true, stall))
    }

    @Test
    fun `only a new or adopted link resets the ladder, never the heartbeat`() {
        assertFalse(WebRtcSessionCoordinator.shouldResetLadderOnBootstrap(false))
        assertTrue(WebRtcSessionCoordinator.shouldResetLadderOnBootstrap(true))
    }

    @Test
    fun `feature-ready waits for liveness only when the desk has that channel`() {
        val withLiveness = WebRtcTransport.requiredLabelsFor(true)
        val without = WebRtcTransport.requiredLabelsFor(false)
        assertTrue(WebRtcChannel.LIVENESS.label in withLiveness)
        assertFalse(WebRtcChannel.LIVENESS.label in without)
        assertEquals(setOf(WebRtcChannel.CONTROL.label, WebRtcChannel.EVENTS.label), without)
    }
}
