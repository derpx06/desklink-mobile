/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun `unique input reordered across WebRTC channels is accepted once`() {
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
            set(WebRtcRemoteSessionController.INPUT_SEQUENCE_FIELD, 4)
        }

        controller.verifyInboundInput(packet, wire)
        packet.set(WebRtcRemoteSessionController.INPUT_SEQUENCE_FIELD, 3)
        controller.verifyInboundInput(packet, wire)

        packet.set(WebRtcRemoteSessionController.INPUT_SEQUENCE_FIELD, 4)
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

    /** Contract §9: the phone offers its own screen; the desk's request-view for that id needs no second consent. */
    @Test
    fun `offer-view carries a new phone-chosen id phone to desktop`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val offered = controller.beginOffer()
        val message = controller.makeMessage(WebRtcRemoteSessionControlKind.OFFER_VIEW, attemptId, wire, remoteSessionId = offered)
        val json = message.toJson()
        assertEquals("offer-view", json.getString("kind"))
        assertEquals("phone-to-desktop", json.getString("direction"))
        assertEquals(offered, json.getString("remoteSessionId"))
        assertTrue(json.getLong("sequence") > 0)

        // The desk's request-view for another id, or the other direction, is not the answer.
        assertFalse(controller.answersOffer("another-id", WebRtcScreenDirection.PHONE_TO_DESKTOP))
        assertFalse(controller.answersOffer(offered, WebRtcScreenDirection.DESKTOP_TO_PHONE))
        assertTrue(controller.answersOffer(offered, WebRtcScreenDirection.PHONE_TO_DESKTOP))
        // Used once.
        assertFalse(controller.answersOffer(offered, WebRtcScreenDirection.PHONE_TO_DESKTOP))
    }

    @Test
    fun `a view request carries this phone's size and rate and old messages parse without`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val id = controller.beginView(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        val message = controller.makeMessage(WebRtcRemoteSessionControlKind.REQUEST_VIEW, attemptId, wire, remoteSessionId = id, maxDimension = 2400, fps = 60)
        val json = message.toJson()
        assertEquals(2400, json.getInt("maxDimension"))
        assertEquals(60, json.getInt("fps"))
        val decoded = WebRtcRemoteSessionControlMessage.fromJson(json)
        assertEquals(2400, decoded.maxDimension)
        assertEquals(60, decoded.fps)
        val plain = controller.makeMessage(WebRtcRemoteSessionControlKind.REQUEST_VIEW, attemptId, wire, remoteSessionId = id).toJson()
        assertFalse(plain.has("maxDimension"))
        assertNull(WebRtcRemoteSessionControlMessage.fromJson(plain).fps)
    }

    /** An expired lease must leave the session viewing, so the phone can ask
     * for control again instead of refusing every gesture forever. */
    @Test
    fun `an expired lease drops to viewing so control can be requested again`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val remoteSessionId = controller.beginView(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        controller.markScreenReady(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        controller.accept(grant(remoteSessionId, owner = "phone", leaseId = "phone-lease"), wire, attemptId)

        assertFalse(controller.dropExpiredLease(now = System.currentTimeMillis()))
        assertTrue(controller.dropExpiredLease(now = System.currentTimeMillis() + 31_000))

        val snapshot = controller.snapshot()
        assertEquals(WebRtcRemoteSessionState.VIEWING, snapshot.state)
        assertFalse(snapshot.hasControlLease)
        assertEquals(remoteSessionId, controller.requestControl())
    }

    /** Switch screen: the desktop re-announces screen-ready for the other
     * screen while the phone holds control. Control must survive it — losing
     * the lease there made every tap after a switch go nowhere. */
    @Test
    fun `a screen switch keeps control and takes the new screen's size`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val remoteSessionId = controller.beginView(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        controller.markScreenReady(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        controller.accept(grant(remoteSessionId, owner = "phone", leaseId = "phone-lease"), wire, attemptId)

        val switched = control(WebRtcRemoteSessionControlKind.SCREEN_READY, remoteSessionId)
            .copy(sequence = 2, screenWidth = 1920, screenHeight = 1080, screenCount = 2)
        controller.accept(switched, wire, attemptId)

        val snapshot = controller.snapshot()
        assertEquals(WebRtcRemoteSessionState.CONTROLLING, snapshot.state)
        assertTrue(snapshot.hasControlLease)
        controller.prepareOutboundInput(mousePacket(), wire.generation)
    }

    @Test
    fun `control denial preserves the view so the user can retry`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val remoteSessionId = controller.beginView(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        controller.markScreenReady(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        val denied = grant(remoteSessionId, owner = "phone", leaseId = "unused").copy(
            kind = WebRtcRemoteSessionControlKind.CONTROL_DENIED,
            leaseId = null,
            ownerDeviceId = null,
            leaseExpiresAt = null,
            reason = "Desktop did not grant input control",
        )

        controller.accept(denied, wire, attemptId)

        val snapshot = controller.snapshot()
        assertEquals(WebRtcRemoteSessionState.VIEWING, snapshot.state)
        assertEquals(remoteSessionId, snapshot.remoteSessionId)
        assertEquals(WebRtcScreenDirection.DESKTOP_TO_PHONE, snapshot.direction)
        assertFalse(snapshot.hasControlLease)
        assertEquals(remoteSessionId, controller.requestControl())
    }

    /** The desktop mirrors the lease from the heartbeats it *receives*; the
     * phone owns the lease and never receives its own, so without renewing on
     * send the phone's copy expired at the desktop's initial 30 s and every
     * later gesture was rejected while the UI still said control was on. */
    @Test
    fun `a lease the phone owns is renewed by the heartbeat it sends`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val remoteSessionId = controller.beginView(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        controller.markScreenReady(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        controller.accept(
            grant(remoteSessionId, owner = "phone", leaseId = "phone-lease")
                .copy(leaseExpiresAt = System.currentTimeMillis() + 250),
            wire,
            attemptId,
        )

        assertNotNull(controller.makeLocalHeartbeat(attemptId, wire))
        Thread.sleep(400) // Past the desktop-granted expiry.

        controller.prepareOutboundInput(mousePacket(), wire.generation)
        assertTrue(controller.hasLocalControlLease(wire.generation))
    }

    @Test
    fun `an expired lease is refused and produces no heartbeat`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val remoteSessionId = controller.beginView(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        controller.markScreenReady(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        controller.accept(
            grant(remoteSessionId, owner = "phone", leaseId = "phone-lease")
                .copy(leaseExpiresAt = System.currentTimeMillis() + 150),
            wire,
            attemptId,
        )
        Thread.sleep(300)

        assertNull(controller.makeLocalHeartbeat(attemptId, wire))
        assertThrows(IllegalArgumentException::class.java) {
            controller.prepareOutboundInput(mousePacket(), wire.generation)
        }
    }

    /** A delayed release, denial or error for a *previous* view must not tear
     * down the one the user has just started. */
    @Test
    fun `a late message for an old session leaves the new session alone`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val old = controller.beginView(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        val current = controller.beginView(WebRtcScreenDirection.DESKTOP_TO_PHONE)

        for (kind in listOf(
            WebRtcRemoteSessionControlKind.RELEASE,
            WebRtcRemoteSessionControlKind.SCREEN_STOPPED,
            WebRtcRemoteSessionControlKind.VIEW_DENIED,
            WebRtcRemoteSessionControlKind.SCREEN_ERROR,
            WebRtcRemoteSessionControlKind.CONTROL_DENIED,
        )) {
            assertFalse("$kind for a stale session must be ignored", controller.accept(control(kind, old), wire, attemptId))
            assertEquals(current, controller.snapshot().remoteSessionId)
            assertEquals(WebRtcRemoteSessionState.REQUESTING_VIEW, controller.snapshot().state)
        }
    }

    @Test
    fun `a denial of the current request ends it`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val current = controller.beginView(WebRtcScreenDirection.DESKTOP_TO_PHONE)

        assertTrue(controller.accept(control(WebRtcRemoteSessionControlKind.VIEW_DENIED, current), wire, attemptId))

        assertEquals(WebRtcRemoteSessionState.DENIED, controller.snapshot().state)
        assertNull(controller.snapshot().remoteSessionId)
    }

    @Test
    fun `the desktop's screen geometry is kept for coordinate mapping until the view ends`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val id = controller.beginView(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        assertNull(controller.snapshot().screenWidth)

        controller.accept(
            control(WebRtcRemoteSessionControlKind.SCREEN_READY, id).copy(screenWidth = 1920, screenHeight = 1080),
            wire,
            attemptId,
        )
        assertEquals(1920, controller.snapshot().screenWidth)
        assertEquals(1080, controller.snapshot().screenHeight)

        controller.accept(control(WebRtcRemoteSessionControlKind.RELEASE, id), wire, attemptId)
        assertNull(controller.snapshot().screenWidth)

        // A new view must not inherit the previous desktop's geometry either.
        val next = controller.beginView(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        assertNull(controller.snapshot().screenHeight)
        assertEquals(next, controller.snapshot().remoteSessionId)
    }


    /** Nothing may leave the phone at "requesting" forever: the desktop can be
     * silent (an old build, a lost message, a portal dialog nobody answers). */
    @Test
    fun `an unanswered view request times out and only that request`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val id = controller.beginView(WebRtcScreenDirection.DESKTOP_TO_PHONE)

        assertFalse("a different session id is not this request", controller.viewRequestTimedOut("other"))
        assertEquals(WebRtcRemoteSessionState.REQUESTING_VIEW, controller.snapshot().state)

        assertTrue(controller.viewRequestTimedOut(id))
        assertEquals(WebRtcRemoteSessionState.FAILED, controller.snapshot().state)
        assertNull(controller.snapshot().remoteSessionId)
        assertFalse("already resolved", controller.viewRequestTimedOut(id))
    }

    @Test
    fun `a view that was granted in time is not timed out`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val id = controller.beginView(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        controller.markScreenReady(WebRtcScreenDirection.DESKTOP_TO_PHONE)

        assertFalse(controller.viewRequestTimedOut(id))
        assertEquals(WebRtcRemoteSessionState.VIEWING, controller.snapshot().state)
    }

    @Test
    fun `an unanswered control request falls back to the view so it can be retried`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val id = controller.beginView(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        controller.markScreenReady(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        controller.requestControl()

        assertTrue(controller.controlRequestTimedOut(id))

        assertEquals(WebRtcRemoteSessionState.VIEWING, controller.snapshot().state)
        assertEquals(id, controller.snapshot().remoteSessionId)
        assertEquals("and control can be requested again", id, controller.requestControl())
    }

    /** The desktop's text-focus report: same wire name and field as the
     * desktop (`remote_session.rs`), and it changes no session state. */
    @Test
    fun `input-focus reports parse with their editable flag and leave the session alone`() {
        assertEquals(
            WebRtcRemoteSessionControlKind.INPUT_FOCUS,
            WebRtcRemoteSessionControlKind.fromWireName("input-focus"),
        )
        val report = control(WebRtcRemoteSessionControlKind.INPUT_FOCUS, "11111111-1111-4111-8111-111111111111")
            .copy(editable = true)
        val decoded = WebRtcRemoteSessionControlMessage.fromJson(report.toJson())
        assertEquals(true, decoded.editable)
        assertNull(control(WebRtcRemoteSessionControlKind.HEARTBEAT, "11111111-1111-4111-8111-111111111111").editable)
    }

    @Test
    fun `the session password is sent only when one is known`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val id = controller.beginView(WebRtcScreenDirection.DESKTOP_TO_PHONE)
        val without = controller.makeMessage(WebRtcRemoteSessionControlKind.REQUEST_VIEW, attemptId, wire, id).toJson()
        assertFalse(without.has("password"))
        val empty = controller.makeMessage(WebRtcRemoteSessionControlKind.REQUEST_VIEW, attemptId, wire, id, password = "").toJson()
        assertFalse(empty.has("password"))
        val with = controller.makeMessage(WebRtcRemoteSessionControlKind.REQUEST_VIEW, attemptId, wire, id, password = "s3cret").toJson()
        assertEquals("s3cret", with.getString("password"))
        assertEquals("s3cret", WebRtcRemoteSessionControlMessage.fromJson(with).password)
    }

    @Test
    fun `password denials are recognised and nothing else is`() {
        assertEquals(SessionPasswordDenial.Required, SessionPassword.denial("password-required"))
        assertEquals(SessionPasswordDenial.Wrong, SessionPassword.denial("password-wrong"))
        assertEquals(SessionPasswordDenial.Locked(120), SessionPassword.denial("password-locked:120"))
        assertNull(SessionPassword.denial("password-locked"))
        assertNull(SessionPassword.denial("password-locked:soon"))
        assertNull(SessionPassword.denial("password-locked:-1"))
        assertNull(SessionPassword.denial("The approved desktop-control session is not active"))
        assertNull(SessionPassword.denial(null))
    }

    @Test
    fun `a password is bounded in UTF-8 bytes like the desktop`() {
        assertTrue(SessionPassword.fits("é".repeat(128)))
        assertFalse(SessionPassword.fits("é".repeat(128) + "x"))
    }

    private fun control(kind: WebRtcRemoteSessionControlKind, remoteSessionId: String) =
        grant(remoteSessionId, owner = "phone", leaseId = "unused").copy(
            kind = kind,
            direction = WebRtcScreenDirection.DESKTOP_TO_PHONE,
            leaseId = null,
            ownerDeviceId = null,
            leaseExpiresAt = null,
        )

    @Test
    fun `a second screen-ready for the shared screen replaces the geometry (a rotation)`() {
        val controller = WebRtcRemoteSessionController { "phone" }
        val remoteSessionId = controller.beginView(WebRtcScreenDirection.PHONE_TO_DESKTOP)
        fun ready(width: Int, height: Int, rotation: Int) = controller.makeMessage(
            WebRtcRemoteSessionControlKind.SCREEN_READY, attemptId, wire, remoteSessionId = remoteSessionId,
            screenWidth = width, screenHeight = height, screenRotation = rotation,
        )
        controller.markScreenReady(WebRtcScreenDirection.PHONE_TO_DESKTOP)
        assertTrue(controller.accept(ready(1080, 2400, 0).copy(deviceId = "desktop"), wire, attemptId))
        assertEquals(1080, controller.snapshot().screenWidth)
        // The owner rotates the phone: the same session announces the new shape.
        assertTrue(controller.accept(ready(2400, 1080, 90).copy(deviceId = "desktop"), wire, attemptId))
        assertEquals(2400, controller.snapshot().screenWidth)
        assertEquals(1080, controller.snapshot().screenHeight)
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
