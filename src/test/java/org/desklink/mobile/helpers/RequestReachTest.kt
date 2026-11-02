/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import androidx.core.app.NotificationManagerCompat
import org.desklink.mobile.plugins.audio.PhoneAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** A question from the computer that nobody on the phone can see is answered at once, in words, not after a silent timeout. */
@RunWith(RobolectricTestRunner::class)
class RequestReachTest {
    private val high = NotificationManagerCompat.IMPORTANCE_HIGH

    @Test
    fun theAppInFrontIsAlwaysReachableOtherwiseTheHeadsUpMustBeAllowedAndLoud() {
        assertEquals(RequestReach.Verdict.OK, RequestReach.verdict(inFront = true, notificationsEnabled = false, channelImportance = 0))
        assertEquals(RequestReach.Verdict.OK, RequestReach.verdict(false, true, high))
        assertEquals(RequestReach.Verdict.OK, RequestReach.verdict(false, true, null))
        assertEquals(RequestReach.Verdict.NOTIFICATIONS_OFF, RequestReach.verdict(false, false, high))
        assertEquals("the requests channel switched off", RequestReach.Verdict.NOTIFICATIONS_OFF, RequestReach.verdict(false, true, NotificationManagerCompat.IMPORTANCE_NONE))
        assertEquals("turned down to a quiet channel", RequestReach.Verdict.QUIET, RequestReach.verdict(false, true, NotificationManagerCompat.IMPORTANCE_LOW))
    }

    @Test
    fun everyRefusalThatIsNotThePersonsOwnNoHasWordsOnThePhone() {
        for (reason in listOf("permission", "unavailable", "unsupported", "busy")) assertTrue(reason, PhoneAudio.reasonTextRes(reason) != null)
        assertNull("the person's own no needs none", PhoneAudio.reasonTextRes("declined"))
    }

    @Test
    fun aQuestionWhoseHeadsUpWasMissedOpensWhenDeskLinkComesToTheFrontAndAnUnansweredOneEnds() {
        val root = "src/main/java/org/desklink/mobile/"
        val start = File(root + "ui/MainActivity.kt").readText().substringAfter("override fun onStart()").substringBefore("override fun onDestroy()")
        assertTrue(start.contains("PhoneCapture.openIfWaiting(this)") && start.contains("PhoneAudio.openIfWaiting(this)"))
        val audio = File(root + "plugins/audio/PhoneAudio.kt").readText()
        assertTrue("the microphone question ends with 'timeout' after a minute", audio.contains("end(app, request, \"timeout\")") && PhoneAudio.ASK_TIMEOUT_MS == 60_000L)
        assertTrue("and with 'notifications' at once when nobody can see it", audio.contains("end(app, request, \"notifications\")"))
        assertTrue(File(root + "plugins/capture/PhoneCapture.kt").readText().contains("finish(context, \"notifications\")"))
        assertTrue("the person's screen says why too", File(root + "plugins/audio/PhoneAudioActivity.kt").readText().contains("PhoneAudio.reasonText(this, reason)"))
    }

    @Test
    fun aWithdrawnOrDroppedQuestionIsForgottenAndOnlyAFreshOneIsOpenedByOpeningTheApp() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        PhoneAudio.pending = PhoneAudio.Pending("desk-1", "session-1", "mic")
        PhoneAudio.withdrawn(app, "desk-2", "session-1")
        assertTrue("another computer's withdrawal changes nothing", PhoneAudio.pending != null)
        PhoneAudio.withdrawn(app, "desk-1", "session-OTHER")
        assertTrue("another session's neither", PhoneAudio.pending != null)
        PhoneAudio.withdrawn(app, "desk-1", "session-1")
        assertNull(PhoneAudio.pending)
        PhoneAudio.pending = PhoneAudio.Pending("desk-1", "s2", "mic")
        PhoneAudio.withdrawn(app, "desk-1") // the link went
        assertNull(PhoneAudio.pending)
        assertTrue(PhoneAudio.openable(5_000))
        assertTrue("older than 30 s is not opened by opening DeskLink", !PhoneAudio.openable(31_000))
        assertTrue(!org.desklink.mobile.plugins.capture.PhoneCapture.openable(31_000) && org.desklink.mobile.plugins.capture.PhoneCapture.openable(10_000))
    }

    @Test
    fun theCoordinatorDropsTheQuestionWhenTheComputerStopsItOrTheLinkGoes() {
        val source = File("src/main/java/org/desklink/mobile/webrtc/WebRtcSessionCoordinator.kt").readText()
        assertTrue(source.contains("audio.withdrawn(context, device.deviceId, message.remoteSessionId)"))
        assertTrue(source.contains("PhoneAudio.withdrawn(context, device.deviceId, session.remoteSessionId)"))
        assertTrue(source.contains("PhoneCapture.withdrawn(context, device.deviceId)"))
        val audio = File("src/main/java/org/desklink/mobile/plugins/audio/PhoneAudio.kt").readText()
        assertTrue("an unanswered question ends whether or not its screen is open", audio.contains("pending === request && !answered"))
        assertTrue("and only for a request the computer still has", audio.contains("sendingSessionId() == waiting.remoteSessionId"))
        assertTrue("the flag is set before the screen is started", audio.indexOf("opened = false\n        answered = false") in 0 until audio.indexOf("context.startActivity(open)"))
    }
}
