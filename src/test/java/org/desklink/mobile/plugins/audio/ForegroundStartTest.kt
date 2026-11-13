/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.audio

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.plugins.screen.ScreenProjectionActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A foreground start the service would have to stop before startForeground crashes the app ("did not then call
 * startForeground"): an invalid request never starts the service.
 */
@RunWith(RobolectricTestRunner::class)
class ForegroundStartTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun theMicrophoneServiceIsNotStartedWithoutRecordAudio() {
        shadowOf(app).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        shadowOf(app).clearStartedServices()
        assertFalse(PhoneAudioService.startMic(app, "desk-fg"))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun aCaptureGrantWithNoComputerStartsNoService() {
        val controller = Robolectric.buildActivity(ScreenProjectionActivity::class.java).setup()
        shadowOf(app).clearStartedServices()
        val prompt = shadowOf(controller.get()).nextStartedActivityForResult
        shadowOf(controller.get()).receiveResult(prompt.intent, Activity.RESULT_OK, Intent())
        assertNull(shadowOf(app).nextStartedService)
    }
}
