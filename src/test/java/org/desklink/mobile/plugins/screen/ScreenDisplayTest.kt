package org.desklink.mobile.plugins.screen

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.desklink.mobile.webrtc.WebRtcRemoteSessionControlMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenDisplayTest {
    @Test
    fun tabletsStartOnExtendPhonesOnMirror() {
        assertEquals(ScreenDisplay.EXTEND, ScreenDisplay.defaultFor(600))
        assertEquals(ScreenDisplay.MIRROR, ScreenDisplay.defaultFor(411))
    }

    @Test
    fun extendCarriesTheViewsPixelsAndScale() {
        assertEquals(ScreenDisplay.Ask("extend", 2400, 1080, 2.75), ScreenDisplay.ask("extend", 2400 to 1080, 2.75f))
        assertEquals(ScreenDisplay.Ask("mirror", null, null, null), ScreenDisplay.ask("mirror", 2400 to 1080, 2.75f))
        assertEquals(ScreenDisplay.Ask("extend", null, null, null), ScreenDisplay.ask("extend", null, 2f))
    }

    @Test
    fun aDeskThatCannotExtendMirrorsUntilAskedAgain() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        ScreenDisplay.choose(context, "deskA", ScreenDisplay.EXTEND)
        assertEquals(ScreenDisplay.EXTEND, ScreenDisplay.requested(context, "deskA"))
        ScreenDisplay.refusedBy("deskA")
        assertEquals(ScreenDisplay.MIRROR, ScreenDisplay.requested(context, "deskA"))
        assertEquals(ScreenDisplay.EXTEND, ScreenDisplay.chosen(context, "deskA"))
        ScreenDisplay.choose(context, "deskA", ScreenDisplay.EXTEND)
        assertEquals(ScreenDisplay.EXTEND, ScreenDisplay.requested(context, "deskA"))
    }

    @Test
    fun requestViewAndScreenReadyCarryD1() {
        val json = JSONObject()
            .put("remoteSessionVersion", 1).put("kind", "screen-ready").put("sessionAttemptId", "a").put("deviceId", "desk")
            .put("sessionId", 1).put("connectionGeneration", 2).put("remoteSessionId", "8b3d1d3e-2f7c-4d9e-9a51-5d1f1d0e7a10")
            .put("sequence", 1).put("timestamp", 5).put("direction", "desktop-to-phone")
            .put("display", "extend").put("screenWidth", 873).put("screenHeight", 392).put("scale", 2.75).put("screenCount", 2)
        val message = WebRtcRemoteSessionControlMessage.fromJson(json)
        assertEquals("extend", message.display)
        assertEquals(2.75, message.scale!!, 0.0)
        assertEquals(message, WebRtcRemoteSessionControlMessage.fromJson(message.toJson()))
        assertNull(WebRtcRemoteSessionControlMessage.fromJson(json.put("display", "mirror").apply { remove("scale") }).scale)
    }
}
