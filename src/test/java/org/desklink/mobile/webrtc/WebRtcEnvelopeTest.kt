/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebRtcEnvelopeTest {
    @Test
    fun featureTrafficRequiresAuthenticatedCapabilityHandover() {
        var state = WebRtcHandoverState.NEGOTIATING
        state = state.receive(WebRtcHandoverMessage.AUTHENTICATED)
        assertEquals(false, state.featuresAllowed)
        state = state.receive(WebRtcHandoverMessage.CAPABILITIES)
        state = state.receive(WebRtcHandoverMessage.FEATURE_READY)
        assertEquals(true, state.featuresAllowed)
    }

    @Test
    fun handoverControlMessageRejectsWrongWireGeneration() {
        val wire = WebRtcWireBinding.fromAttempt(
            "phone-device", "desktop-device", "01234567-89ab-cdef-0123-456789abcdef",
        )
        val message = WebRtcHandoverControlMessage(
            handoverVersion = 1,
            kind = WebRtcHandoverControlKind.HELLO,
            sessionAttemptId = "01234567-89ab-cdef-0123-456789abcdef",
            deviceId = "desktop-device",
            sessionId = wire.sessionId,
            connectionGeneration = wire.generation,
            nonce = "nonce",
            timestamp = 1_000,
        )
        message.validate(wire, message.sessionAttemptId, 1_000)
        assertThrows(IllegalArgumentException::class.java) {
            message.copy(connectionGeneration = wire.generation + 1)
                .validate(wire, message.sessionAttemptId, 1_000)
        }
    }

    @Test
    fun authenticationTranscriptMatchesDesktopCanonicalFieldOrder() {
        val transcript = WebRtcAuthenticationTranscript(
            sessionAttemptId = "attempt",
            initiatorDeviceId = "desktop",
            responderDeviceId = "phone",
            sessionId = 4,
            connectionGeneration = 2,
            initiatorNonce = "a",
            responderNonce = "b",
            offerSha256 = "offer",
            answerSha256 = "answer",
            initiatorDtlsFingerprint = "one",
            responderDtlsFingerprint = "two",
            protocolVersion = 1,
            timestamp = 1,
        )
        assertEquals(
            """{"sessionAttemptId":"attempt","initiatorDeviceId":"desktop","responderDeviceId":"phone","sessionId":4,"connectionGeneration":2,"initiatorNonce":"a","responderNonce":"b","offerSha256":"offer","answerSha256":"answer","initiatorDtlsFingerprint":"one","responderDtlsFingerprint":"two","protocolVersion":1,"timestamp":1}""",
            String(transcript.canonicalBytes()),
        )
    }
    @Test
    fun pairedFeaturePacketRoundTripsAcrossTheWebRtcBridge() {
        val sender = WebRtcWireBinding.fromAttempt(
            "desktop-device", "phone-device", "01234567-89ab-cdef-0123-456789abcdef",
        )
        val packet = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_CLIPBOARD).apply {
            set("content", "hello from DeskLink")
        }
        val envelope = WebRtcPacketBridge.encode(sender, packet, 1)
        val receiver = WebRtcWireBinding.fromAttempt(
            "phone-device", "desktop-device", "01234567-89ab-cdef-0123-456789abcdef",
        )

        val decoded = WebRtcPacketBridge.decode(receiver, envelope)
        assertEquals(packet.type, decoded.type)
        assertEquals("hello from DeskLink", decoded.getString("content"))
    }

    @Test
    fun pointerMotionIsRealtimeButButtonTransitionsAreReliable() {
        val motion = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST).apply {
            set("dx", 2.0)
            set("dy", 3.0)
        }
        val click = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST).apply {
            set("singleclick", true)
        }
        assertEquals(WebRtcChannel.INPUT_REALTIME, WebRtcPacketBridge.channelFor(motion))
        assertEquals(WebRtcChannel.INPUT_RELIABLE, WebRtcPacketBridge.channelFor(click))
    }

    @Test
    fun positionedScreenTapStaysReliableSoItsClickCannotOvertakeItsPosition() {
        val tap = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST).apply {
            set("x", 640)
            set("y", 360)
            set("singleclick", true)
        }

        assertEquals(WebRtcChannel.INPUT_RELIABLE, WebRtcPacketBridge.channelFor(tap))
    }

    @Test
    fun wireBindingUsesTheSamePositiveSessionIdAsDesktop() {
        val binding = WebRtcWireBinding.fromAttempt(
            "phone-device",
            "desktop-device",
            "01234567-89ab-cdef-0123-456789abcdef",
        )

        assertEquals(0x0123_4567_89ab_cdeL, binding.sessionId)
        assertEquals(1L, binding.generation)
    }

    @Test
    fun fixtureParsesAndValidatesBinding() {
        val fixture = WebRtcEnvelope.fromJson(
            """
            {"protocolVersion":1,"messageId":"00000000-0000-4000-8000-000000000001","deviceId":"phone-test-device","sessionId":12,"connectionGeneration":4,"channel":"desklink-events-v1","messageType":"clipboard.update","timestamp":1780000000000,"flags":0,"payloadLength":5,"payloadBase64":"aGVsbG8="}
            """.trimIndent(),
        )
        assertEquals("desklink-events-v1", fixture.channel)
        assertArrayEquals("hello".toByteArray(), fixture.validate("phone-test-device", 12, 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun staleGenerationIsRejected() {
        val fixture = WebRtcEnvelope.create(
            "phone-test-device", 12, 4, WebRtcChannel.EVENTS, "ping", "x".toByteArray(), 1,
        )
        fixture.validate("phone-test-device", 12, 5)
    }

    @Test
    fun unknownChannelIsRejected() {
        val fixture = WebRtcEnvelope.create(
            "phone-test-device", 12, 4, WebRtcChannel.EVENTS, "ping", "x".toByteArray(), 1,
        ).copy(channel = "desklink-unknown-v1")
        assertThrows(IllegalStateException::class.java) {
            fixture.validate("phone-test-device", 12, 4)
        }
    }
}
