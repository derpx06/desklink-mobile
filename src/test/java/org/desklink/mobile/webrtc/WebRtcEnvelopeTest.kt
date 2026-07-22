/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebRtcEnvelopeTest {
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
