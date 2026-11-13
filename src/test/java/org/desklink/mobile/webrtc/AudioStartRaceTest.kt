/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.desklink.mobile.plugins.audio.AudioSessions
import org.desklink.mobile.plugins.audio.PhoneAudio
import org.desklink.mobile.session.DeviceManager
import org.desklink.mobile.webrtc.terminal.TerminalClient
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The microphone service starts (no projection) after the desk has replaced its request with one for the phone's sound: a
 * refusal the service answers, never an exception out of onStartCommand that kills the app.
 */
@RunWith(RobolectricTestRunner::class)
class AudioStartRaceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun aMicrophoneStartForASessionNowAskingForPhoneSoundIsRefusedNotThrown() {
        val device = mockk<Device>(relaxed = true)
        every { device.deviceId } returns "desk-audio-race"
        val coordinator = WebRtcSessionCoordinator(app, device, DeviceManager(), terminal = mockk<TerminalClient>(relaxed = true), onStateChanged = { _, _ -> })
        PhoneAudio.sendingTo = null
        val sessions = WebRtcSessionCoordinator::class.java.getDeclaredField("audioSessions").apply { isAccessible = true }.get(coordinator) as AudioSessions
        sessions.open(AudioSessions.Session("r2", "phone-to-desk", "system"))
        assertFalse(coordinator.startAudioSending(null))
    }
}
