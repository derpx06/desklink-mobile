package org.desklink.mobile.plugins.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.desklink.mobile.webrtc.WebRtcFileAction
import org.desklink.mobile.webrtc.WebRtcFileControl
import org.desklink.mobile.webrtc.WebRtcRemoteSessionControlKind
import org.desklink.mobile.webrtc.WebRtcRemoteSessionControlMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class InsertFromPhoneTest {
    @Test
    fun requestsAreCheckedAndTargetsDefaultToTheClipboard() {
        assertEquals("file", CaptureRequest.of("d", "c", "scan", "file")!!.target)
        assertEquals("clipboard", CaptureRequest.of("d", "c", "photo", null)!!.target)
        assertEquals("clipboard", CaptureRequest.of("d", "c", "qr", "elsewhere")!!.target)
        assertNull(CaptureRequest.of("d", "c", "video", "file"))
        assertNull(CaptureRequest.of("d", "c", null, "file"))
    }

    @Test
    fun theCaptureKindTravelsAsCaptureKind() {
        val json = JSONObject()
            .put("remoteSessionVersion", 1).put("kind", "capture-request").put("sessionAttemptId", "a").put("deviceId", "desk")
            .put("sessionId", 1).put("connectionGeneration", 2).put("remoteSessionId", "8b3d1d3e-2f7c-4d9e-9a51-5d1f1d0e7a10")
            .put("sequence", 1).put("timestamp", 5)
            .put("captureId", "8b3d1d3e-2f7c-4d9e-9a51-5d1f1d0e7a10").put("captureKind", "scan").put("target", "file")
        val request = WebRtcRemoteSessionControlMessage.fromJson(json)
        assertEquals(WebRtcRemoteSessionControlKind.CAPTURE_REQUEST, request.kind)
        assertEquals("scan", request.captureKind)
        assertEquals("file", request.target)
        val result = request.copy(kind = WebRtcRemoteSessionControlKind.CAPTURE_RESULT, text = "WIFI:S:home;T:WPA;P:secret;;", pages = 3)
        assertEquals(result, WebRtcRemoteSessionControlMessage.fromJson(result.toJson()))
        assertEquals("capture-result", result.toJson().getString("kind"))
    }

    @Test
    fun aCapturedFilesOfferNamesItsCapture() {
        val offer = WebRtcFileControl(
            protocolVersion = 1, action = WebRtcFileAction.OFFER, transferId = "t1", deviceId = "desk", sessionId = 1,
            connectionGeneration = 1, transferToken = "token", filename = "scan.pdf", totalSize = 3, sha256 = "a".repeat(64),
            offset = 0, chunkSize = WebRtcFileControl.LEGACY_CHUNK_BYTES, mimeType = "application/pdf", captureId = "c1",
        )
        assertEquals(offer, WebRtcFileControl.fromJson(JSONObject(offer.toJson().toString())))
    }

    @Test
    fun thePageIsFoundOnAPlainBackground() {
        // A bright page (x 20..79, y 30..89) on a dark desk, 100 x 120.
        val w = 100; val h = 120
        val luma = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 20..79 && y in 30..89) 230 else 40 }
        val quad = ScanImage.detect(luma, w, h)
        val expected = listOf(20f to 30f, 79f to 30f, 79f to 89f, 20f to 89f)
        quad.points.zip(expected).forEach { (got, want) ->
            assertTrue("$got vs $want", abs(got.first - want.first) <= 2 && abs(got.second - want.second) <= 2)
        }
    }

    @Test
    fun aFeaturelessPhotoFallsBackToAnInset() {
        val quad = ScanImage.detect(IntArray(100 * 100) { 128 }, 100, 100)
        assertEquals(ScanImage.inset(100, 100, 0.08f), quad)
        assertEquals(84f * 84f, ScanImage.area(quad), 0.5f)
    }
}
