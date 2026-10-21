package org.desklink.mobile.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.webrtc.RTCStats
import org.webrtc.RtpParameters

class WebRtcConnectionStatsTest {
    @Test
    fun candidateTypesMapToThePath() {
        assertEquals(WebRtcConnectionPath.LAN, connectionPath("host", "host", bootstrapViaRelay = false))
        assertEquals(WebRtcConnectionPath.LAN, connectionPath("host", "prflx", bootstrapViaRelay = false))
        assertEquals(WebRtcConnectionPath.DIRECT, connectionPath("host", "host", bootstrapViaRelay = true))
        assertEquals(WebRtcConnectionPath.DIRECT, connectionPath("srflx", "host", bootstrapViaRelay = false))
        assertEquals(WebRtcConnectionPath.DIRECT, connectionPath("prflx", "srflx", bootstrapViaRelay = true))
        assertEquals(WebRtcConnectionPath.TURN, connectionPath("relay", "host", bootstrapViaRelay = false))
        assertEquals(WebRtcConnectionPath.TURN, connectionPath("host", "relay", bootstrapViaRelay = true))
        assertEquals(WebRtcConnectionPath.TURN, connectionPath(null, "relay", bootstrapViaRelay = false))
        assertEquals(WebRtcConnectionPath.LAN, connectionPath("prflx", "prflx", bootstrapViaRelay = false))
        assertEquals(WebRtcConnectionPath.DIRECT, connectionPath("srflx", "srflx", bootstrapViaRelay = false))
    }

    @Test
    fun webrtcbinsLongCandidateTypeNamesMapLikeTheShortOnes() {
        assertEquals(WebRtcConnectionPath.LAN, connectionPath("host", "peer-reflexive", bootstrapViaRelay = false))
        assertEquals(WebRtcConnectionPath.DIRECT, connectionPath("server-reflexive", "host", bootstrapViaRelay = false))
        assertEquals(WebRtcConnectionPath.TURN, connectionPath("host", "relayed", bootstrapViaRelay = false))
        assertEquals("srflx", canonicalCandidateType("server-reflexive"))
        assertEquals("prflx", canonicalCandidateType("peer-reflexive"))
        assertEquals("relay", canonicalCandidateType("relayed"))
        assertEquals("host", canonicalCandidateType("host"))
    }

    @Test
    fun unknownCandidateTypesAreDirect() {
        assertEquals(WebRtcConnectionPath.DIRECT, connectionPath(null, null, bootstrapViaRelay = false))
        assertEquals(WebRtcConnectionPath.DIRECT, connectionPath("host", null, bootstrapViaRelay = false))
        assertEquals(WebRtcConnectionPath.DIRECT, connectionPath("host", "mystery", bootstrapViaRelay = false))
    }

    private fun stats(id: String, type: String, vararg members: Pair<String, Any>) =
        id to RTCStats(0, type, id, mapOf(*members))

    @Test
    fun theSelectedPairIsFollowedFromTheTransport() {
        val report = mapOf(
            stats("T1", "transport", "selectedCandidatePairId" to "CP2"),
            stats(
                "CP1", "candidate-pair",
                "localCandidateId" to "L1", "remoteCandidateId" to "R1", "currentRoundTripTime" to 0.5,
            ),
            stats(
                "CP2", "candidate-pair",
                "localCandidateId" to "L2", "remoteCandidateId" to "R2",
                "currentRoundTripTime" to 0.0123, "availableOutgoingBitrate" to 4_200_000.0,
            ),
            stats("L1", "local-candidate", "candidateType" to "host"),
            stats("R1", "remote-candidate", "candidateType" to "host"),
            stats("L2", "local-candidate", "candidateType" to "relay"),
            stats("R2", "remote-candidate", "candidateType" to "srflx"),
        )
        assertEquals(
            WebRtcConnectionStats(WebRtcConnectionPath.TURN, 12L, 4_200_000L),
            connectionStatsFrom(report, bootstrapViaRelay = false),
        )
    }

    @Test
    fun noSelectedPairMeansNoStats() {
        assertNull(connectionStatsFrom(mapOf(stats("T1", "transport")), bootstrapViaRelay = false))
    }

    @Test
    fun anUnresolvedCandidateTypeMeansNoStatsNotTheInternet() {
        fun report(localType: String?, remoteType: String?) = mapOf(
            stats("T1", "transport", "selectedCandidatePairId" to "CP"),
            stats("CP", "candidate-pair", "localCandidateId" to "L", "remoteCandidateId" to "R", "currentRoundTripTime" to 0.004),
            if (localType == null) stats("L", "local-candidate") else stats("L", "local-candidate", "candidateType" to localType),
            if (remoteType == null) stats("R", "remote-candidate") else stats("R", "remote-candidate", "candidateType" to remoteType),
        )
        assertNull(connectionStatsFrom(report(null, "host"), bootstrapViaRelay = false))
        assertNull(connectionStatsFrom(report("host", null), bootstrapViaRelay = false))
        assertNull(connectionStatsFrom(report("", "host"), bootstrapViaRelay = false))
        assertEquals(WebRtcConnectionPath.LAN, connectionStatsFrom(report("host", "host"), bootstrapViaRelay = false)?.path)
    }

    @Test
    fun controlKeepsMotionAndViewingKeepsResolution() {
        assertEquals(RtpParameters.DegradationPreference.BALANCED, screenDegradationPreference(true))
        assertEquals(RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION, screenDegradationPreference(false))
    }

    @Test
    fun encoderFailureStepsTheCaptureDownThenGivesUp() {
        assertEquals(960, nextCaptureLongSide(1280))
        assertEquals(720, nextCaptureLongSide(960))
        assertEquals(720, nextCaptureLongSide(800))
        assertNull(nextCaptureLongSide(720))
    }
}
