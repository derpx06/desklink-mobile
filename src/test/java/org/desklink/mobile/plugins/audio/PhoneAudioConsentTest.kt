/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.audio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhoneAudioConsentTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun aRememberedMicrophoneConsentCanBeTakenBack() {
        val desk = "eeeeeeee-0000-0000-0000-000000000006"
        PhoneAudio.rememberConsent(app, desk)
        assertTrue(PhoneAudio.consented(app, desk))
        PhoneAudio.forgetConsent(app, desk)
        assertFalse("the next request asks again", PhoneAudio.consented(app, desk))
    }

    @Test
    fun anotherComputersSessionEndingNeverResetsTheAudioModuleThatIsFeedingThisOne() {
        assertTrue(PhoneAudio.mayReset(null, "desk-A"))
        assertTrue(PhoneAudio.mayReset("desk-A", "desk-A"))
        assertFalse("desk B is hearing the phone; desk A reconnecting must not silence it", PhoneAudio.mayReset("desk-B", "desk-A"))
    }
}
