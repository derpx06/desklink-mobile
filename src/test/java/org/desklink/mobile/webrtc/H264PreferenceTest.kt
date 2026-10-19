package org.desklink.mobile.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test
import org.webrtc.RtpCapabilities

class H264PreferenceTest {
    private fun codec(name: String, vararg params: Pair<String, String>) =
        RtpCapabilities.CodecCapability().apply {
            this.name = name
            parameters = mapOf(*params)
        }

    @Test
    fun hardwareFriendlyH264ComesFirstAndEverythingElseKeepsItsOrder() {
        val codecs = listOf(
            codec("VP8"),
            codec("H264", "packetization-mode" to "0", "profile-level-id" to "42e01f"),
            codec("VP9"),
            codec("H264", "packetization-mode" to "1", "profile-level-id" to "640c1f"),
            codec("H264", "packetization-mode" to "1", "profile-level-id" to "42e01f"),
            codec("AV1"),
        )
        val ordered = codecs.sortedBy(::h264Rank).map { "${it.name}:${it.parameters["profile-level-id"]}:${it.parameters["packetization-mode"]}" }
        assertEquals(
            listOf("H264:42e01f:1", "H264:640c1f:1", "H264:42e01f:0", "VP8:null:null", "VP9:null:null", "AV1:null:null"),
            ordered,
        )
    }
}
