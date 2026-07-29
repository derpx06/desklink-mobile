/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WebRtcRemoteSessionTest {
    private val attemptId = "01234567-89ab-cdef-0123-456789abcdef"
    private val wire = WebRtcWireBinding.fromAttempt("phone", "desktop", attemptId)

    @Test
    fun `lock pause releases the phone control lease`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val remoteSessionId = controller.beginView(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        controller.markScreenReady(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        controller.accept(
            grant(remoteSessionId, owner = "phone", leaseId = "phone-lease"),
            wire,
            attemptId,
        )

        controller.pauseLocked()

        val snapshot = controller.snapshot()
        assertEquals(WebRtcRemoteSessionState.PAUSED_LOCKED, snapshot.state)
        assertFalse(snapshot.hasControlLease)
        assertThrows(IllegalArgumentException::class.java) {
            controller.prepareOutboundInput(mousePacket(), wire.generation)
        }
    }

    @Test
    fun `replayed peer input sequence is rejected`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val remoteSessionId = controller.beginView(WebRtcScreenDirection.PHONE_TO_DESKTOP)
        controller.markScreenReady(WebRtcScreenDirection.PHONE_TO_DESKTOP)
        controller.accept(
            grant(remoteSessionId, owner = "desktop", leaseId = "desktop-lease"),
            wire,
            attemptId,
        )
        val packet = mousePacket().apply {
            set(WebRtcRemoteSessionController.REMOTE_SESSION_ID_FIELD, remoteSessionId)
            set(WebRtcRemoteSessionController.LEASE_ID_FIELD, "desktop-lease")
            set(WebRtcRemoteSessionController.INPUT_SEQUENCE_FIELD, 7)
        }

        controller.verifyInboundInput(packet, wire)
        assertThrows(IllegalArgumentException::class.java) {
            controller.verifyInboundInput(packet, wire)
        }
    }

    @Test
    fun `screen-ready geometry survives the authenticated control envelope`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val remoteSessionId = controller.beginView(WebRtcScreenDirection.PHONE_TO_DESKTOP)
        val message = controller.makeMessage(
            WebRtcRemoteSessionControlKind.SCREEN_READY,
            attemptId,
            wire,
            remoteSessionId = remoteSessionId,
            screenWidth = 1080,
            screenHeight = 2400,
            screenRotation = 0,
        )

        val decoded = WebRtcRemoteSessionControlMessage.fromJson(message.toJson())
        decoded.validate(wire.copy(senderDeviceId = "desktop", peerDeviceId = "phone"), attemptId, System.currentTimeMillis())
        assertEquals(1080, decoded.screenWidth)
        assertEquals(2400, decoded.screenHeight)
        assertEquals(0, decoded.screenRotation)
        assertNull(decoded.reason)
    }

    private fun grant(
        remoteSessionId: String,
        owner: String,
        leaseId: String,
    ) = WebRtcRemoteSessionControlMessage(
        remoteSessionVersion = WebRtcRemoteSessionControlMessage.VERSION,
        kind = WebRtcRemoteSessionControlKind.CONTROL_GRANTED,
        sessionAttemptId = attemptId,
        deviceId = "desktop",
        sessionId = wire.sessionId,
        connectionGeneration = wire.generation,
        remoteSessionId = remoteSessionId,
        leaseId = leaseId,
        direction = WebRtcScreenDirection.PHONE_TO_DESKTOP,
        ownerDeviceId = owner,
        sequence = 1,
        leaseExpiresAt = System.currentTimeMillis() + 30_000L,
        timestamp = System.currentTimeMillis(),
    )

    private fun mousePacket() = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST).apply {
        set("singleclick", true)
    }
}
