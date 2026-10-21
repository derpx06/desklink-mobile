package org.desklink.mobile.plugins.camera

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.desklink.mobile.webrtc.WebRtcRemoteSessionControlKind
import org.desklink.mobile.webrtc.WebRtcRemoteSessionControlMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneCameraTest {
    private fun request(device: String, session: String) = CameraRequest.of(device, session, "front", 1280, 720, 30, false)

    @Test
    fun theSizeIsCappedAt1080pAnd30Fps() {
        assertEquals(Triple(1920, 1080, 30), CameraRequest.cap(3840, 2160, 60))
        assertEquals(Triple(1080, 1920, 30), CameraRequest.cap(2160, 3840, 30))
        assertEquals(Triple(1280, 720, 30), CameraRequest.cap(null, null, null))
        assertEquals(Triple(640, 480, 15), CameraRequest.cap(640, 480, 15))
        assertEquals("back", CameraRequest.of("d", "s", "sideways", null, null, null, null).facing)
    }

    @Test
    fun oneCameraSessionAtATime() {
        val state = CameraState()
        assertEquals(CameraState.Offer.ACCEPTED, state.offer(request("deskA", "s1")))
        // The same desk asking again replaces its unanswered request; another desk is busy.
        assertEquals(CameraState.Offer.ACCEPTED, state.offer(request("deskA", "s2")))
        assertEquals(CameraState.Offer.BUSY, state.offer(request("deskB", "s3")))
        assertNull(state.start("deskB"))
        assertEquals("s2", state.start("deskA")?.remoteSessionId)
        assertEquals(CameraState.Offer.ALREADY_LIVE, state.offer(request("deskA", "s2")))
        assertEquals(CameraState.Offer.BUSY, state.offer(request("deskA", "s4")))
        assertEquals("s2", state.end("deskA")?.remoteSessionId)
        assertNull(state.end("deskA"))
        assertEquals(CameraState.Offer.ACCEPTED, state.offer(request("deskB", "s3")))
    }

    @Test
    fun consentIsRememberedPerDesk() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(PhoneCamera.consented(context, "deskA"))
        PhoneCamera.rememberConsent(context, "deskA", true)
        assertTrue(PhoneCamera.consented(context, "deskA"))
        assertFalse(PhoneCamera.consented(context, "deskB"))
    }

    @Test
    fun cameraMessagesParseAndRoundTrip() {
        val json = JSONObject()
            .put("remoteSessionVersion", 1).put("kind", "camera-request").put("sessionAttemptId", "a")
            .put("deviceId", "desk").put("sessionId", 1).put("connectionGeneration", 2)
            .put("remoteSessionId", "8b3d1d3e-2f7c-4d9e-9a51-5d1f1d0e7a10").put("sequence", 1).put("timestamp", 5)
            .put("facing", "back").put("width", 1920).put("height", 1080).put("fps", 30).put("audio", true)
        val message = WebRtcRemoteSessionControlMessage.fromJson(json)
        assertEquals(WebRtcRemoteSessionControlKind.CAMERA_REQUEST, message.kind)
        assertEquals("back", message.facing)
        assertEquals(1920, message.width)
        assertEquals(true, message.audio)
        assertEquals(message, WebRtcRemoteSessionControlMessage.fromJson(message.toJson()))
        val control = WebRtcRemoteSessionControlMessage.fromJson(JSONObject(json.toString()).put("kind", "camera-control").put("action", "flip"))
        assertEquals("flip", control.action)
    }
}
