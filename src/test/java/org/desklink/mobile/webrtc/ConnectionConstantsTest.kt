/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.desklink.mobile.PENDING_FEATURE_LIMIT
import org.desklink.mobile.PENDING_FEATURE_MAX_AGE_MS
import org.desklink.mobile.backends.lan.LanLink
import org.desklink.mobile.backends.lan.LinkRace
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every number of the connection contract the desk must share, pinned to the fixture both apps read
 * (`shared/connection_constants.json`, an identical copy of the desk's
 * `testdata/connection_constants.json`, pinned there by `connection_constants_match_the_shared_fixture`).
 */
class ConnectionConstantsTest {
    private val f = JSONObject(javaClass.getResourceAsStream("/shared/connection_constants.json")!!.bufferedReader().readText())

    private fun longs(array: JSONArray) = List(array.length()) { array.getLong(it) }

    @Test
    fun `the connection numbers are the desk's`() {
        assertEquals(longs(f.getJSONArray("recoveryLadderMillis")), WebRtcRecoveryPolicy.DELAYS_MILLIS.toList())
        assertEquals(f.getLong("healthyAfterMillis"), WebRtcRecoveryPolicy.HEALTHY_RESET_MS)
        assertEquals(f.getLong("peerProbeAfterMillis"), WebRtcSessionCoordinator.PEER_PROBE_AFTER_MS)
        assertEquals(f.getLong("peerDeadAfterMillis"), WebRtcSessionCoordinator.PEER_DEAD_AFTER_MS)
        assertEquals(f.getLong("transientDisconnectGraceMillis"), WebRtcSessionCoordinator.TRANSIENT_DISCONNECT_GRACE_MILLIS)
        assertEquals(f.getLong("linkRaceWindowMillis"), LinkRace.WINDOW_MS)
        assertEquals(f.getLong("maxPayloadBytes"), WebRtcEnvelope.MAX_PAYLOAD_BYTES.toLong())
        assertEquals(f.getLong("maxEnvelopeBytes"), WebRtcEnvelope.MAX_ENVELOPE_BYTES.toLong())
        assertEquals(f.getLong("maxLivenessEnvelopeBytes"), WebRtcChannel.MAX_LIVENESS_ENVELOPE_BYTES.toLong())
        assertEquals(f.getLong("livenessMaxBufferedBytes"), WebRtcChannel.LIVENESS_MAX_BUFFERED_BYTES)
        assertEquals(f.getLong("eventsHighWaterBytes"), FlowControlledSender.EVENTS_HIGH_WATER)
        assertEquals(f.getLong("eventsLowWaterBytes"), FlowControlledSender.EVENTS_LOW_WATER)
        assertEquals(f.getLong("pendingFeatureMaxAgeMillis"), PENDING_FEATURE_MAX_AGE_MS)
        assertEquals(f.getJSONObject("pendingFeatureLimit").getInt("android"), PENDING_FEATURE_LIMIT)
        assertEquals(f.getLong("laneRetryFirstMillis"), FileLaneProtocol.RETRY_MS)
        assertEquals(f.getLong("laneRetryMaxMillis"), FileLaneProtocol.COOL_MAX_MS)
    }

    @Test
    fun `the lan reader gives up a little after the desk, never before`() {
        val idle = LanLink::class.java.getDeclaredField("IDLE_TIMEOUT_MILLIS").apply { isAccessible = true }.getLong(null)
        assertEquals(f.getLong("androidLanReaderIdleMillis"), idle)
        assertTrue(idle > f.getLong("desktopHeartbeatDeadMillis"))
    }

    @Test
    fun `channels, the ones feature-ready waits for, and the bundle policy are the desk's`() {
        val channels = f.getJSONArray("channels")
        assertEquals(channels.length(), WebRtcChannel.entries.size)
        for (i in 0 until channels.length()) {
            val expected = channels.getJSONObject(i)
            val channel = WebRtcChannel.fromLabel(expected.getString("label"))!!
            assertEquals(expected.getBoolean("ordered"), channel.ordered)
            assertEquals(if (expected.isNull("maxRetransmits")) null else expected.getInt("maxRetransmits"), channel.maxRetransmits)
        }
        val required = f.getJSONArray("requiredChannels")
        assertEquals(List(required.length()) { required.getString(it) }, WebRtcChannel.REQUIRED.map { it.label })
        assertEquals("max-bundle", f.getString("bundlePolicy"))
        val transport = File("src/main/java/org/desklink/mobile/webrtc/WebRtcTransport.kt").readText()
        assertTrue(transport.contains("bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE"))
    }
}
