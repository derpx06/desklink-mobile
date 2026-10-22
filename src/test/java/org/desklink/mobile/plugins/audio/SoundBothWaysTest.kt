package org.desklink.mobile.plugins.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.desklink.mobile.webrtc.WebRtcAudioSdp
import org.desklink.mobile.webrtc.WebRtcRemoteSessionControlKind
import org.desklink.mobile.webrtc.WebRtcRemoteSessionControlMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SoundBothWaysTest {
    private val sdp = listOf(
        "v=0", "m=video 9 UDP/TLS/RTP/SAVPF 96", "a=rtpmap:96 VP8/90000",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111 63", "a=rtpmap:111 opus/48000/2", "a=fmtp:111 minptime=10;useinbandfec=1",
        "a=rtpmap:63 red/48000/2", "a=fmtp:63 111/111", "m=application 9 UDP/DTLS/SCTP webrtc-datachannel", "",
    ).joinToString("\r\n")

    @Test
    fun opusGetsTheA1Parameters() {
        val out = WebRtcAudioSdp.withOpusParameters(sdp)
        assertTrue(out.contains("\r\na=fmtp:111 minptime=10;useinbandfec=1;stereo=1;maxaveragebitrate=128000\r\n"))
        assertTrue(out.contains("\r\na=fmtp:63 111/111\r\n"))
        assertEquals(out, WebRtcAudioSdp.withOpusParameters(out))
        // No audio section (an old peer's SDP): unchanged.
        val noAudio = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 VP8/90000\r\n"
        assertEquals(noAudio, WebRtcAudioSdp.withOpusParameters(noAudio))
        // An Opus line without fmtp gets one.
        val bare = WebRtcAudioSdp.withOpusParameters("m=audio 9 RTP 111\na=rtpmap:111 opus/48000/2")
        assertTrue(bare.endsWith("a=rtpmap:111 opus/48000/2\na=fmtp:111 minptime=10;useinbandfec=1;stereo=1;maxaveragebitrate=128000"))
    }

    private fun message(kind: String) = JSONObject()
        .put("remoteSessionVersion", 1).put("kind", kind).put("sessionAttemptId", "a").put("deviceId", "desk")
        .put("sessionId", 1).put("connectionGeneration", 2).put("remoteSessionId", "8b3d1d3e-2f7c-4d9e-9a51-5d1f1d0e7a10")
        .put("sequence", 1).put("timestamp", 5)

    @Test
    fun audioKindsRoundTripAndTheirDirectionIsNotAScreenDirection() {
        val request = WebRtcRemoteSessionControlMessage.fromJson(message("audio-request").put("direction", "phone-to-desk").put("source", "mic"))
        assertEquals(WebRtcRemoteSessionControlKind.AUDIO_REQUEST, request.kind)
        assertEquals("phone-to-desk", request.audioDirection)
        assertNull(request.direction)
        assertEquals("mic", request.source)
        assertEquals(request, WebRtcRemoteSessionControlMessage.fromJson(request.toJson()))
        val ready = WebRtcRemoteSessionControlMessage.fromJson(
            message("audio-ready").put("direction", "desk-to-phone").put("source", "system").put("sampleRate", 48000).put("channels", 2),
        )
        assertEquals(48000, ready.sampleRate)
        assertEquals(ready, WebRtcRemoteSessionControlMessage.fromJson(ready.toJson()))
        val volume = WebRtcRemoteSessionControlMessage.fromJson(message("audio-control").put("action", "volume").put("value", 40))
        assertEquals(40, volume.value)
        listOf("audio-stop", "audio-denied").forEach { assertTrue(WebRtcRemoteSessionControlKind.fromWireNameOrNull(it)!!.isAudio) }
        // A kind from a newer build is recognised as unknown (ignored), not an exception.
        assertNull(WebRtcRemoteSessionControlKind.fromWireNameOrNull("hologram-request"))
    }

    @Test
    fun oneSessionPerDirectionAndANewOneReplacesTheOld() {
        val sessions = AudioSessions()
        val hear1 = AudioSessions.Session("h1", "desk-to-phone", "system")
        assertNull(sessions.open(hear1))
        val send1 = AudioSessions.Session("s1", "phone-to-desk", "mic")
        assertNull(sessions.open(send1))
        assertEquals(hear1, sessions.open(AudioSessions.Session("h2", "desk-to-phone", "system")))
        assertTrue(sessions.ready("s1")!!.live)
        assertNull(sessions.ready("h1"))
        assertEquals("s1", sessions.end("s1")?.remoteSessionId)
        assertNull(sessions.end("s1"))
        assertEquals(listOf("h2"), sessions.clear().map { it.remoteSessionId })
    }
}
