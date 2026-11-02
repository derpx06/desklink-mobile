/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import androidx.core.app.NotificationManagerCompat
import org.desklink.mobile.plugins.audio.PhoneAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A question from the computer that nobody on the phone can see is answered at once, in words, not after a silent timeout. */
class RequestReachTest {
    private val high = NotificationManagerCompat.IMPORTANCE_HIGH

    @Test
    fun theAppInFrontIsAlwaysReachableOtherwiseTheHeadsUpMustBeAllowedAndLoud() {
        assertEquals(RequestReach.Verdict.OK, RequestReach.verdict(inFront = true, notificationsEnabled = false, channelImportance = 0))
        assertEquals(RequestReach.Verdict.OK, RequestReach.verdict(false, true, high))
        assertEquals(RequestReach.Verdict.OK, RequestReach.verdict(false, true, null))
        assertEquals(RequestReach.Verdict.NOTIFICATIONS_OFF, RequestReach.verdict(false, false, high))
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
}
