/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.audio

import android.Manifest
import org.desklink.mobile.helpers.DeskNames
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R

/**
 * "<desk> wants to hear this phone's microphone / sound": asked on screen the first time per desk
 * (remembered), then Android's own microphone permission and, for phone sound, its capture prompt.
 * Visible, so it may start [PhoneAudioService].
 */
class PhoneAudioActivity : AppCompatActivity() {
    private lateinit var deviceId: String
    private lateinit var request: PhoneAudio.Pending

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) proceed() else deny("permission")
    }

    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            if (!PhoneAudioService.startSound(this, deviceId, result.resultCode, data)) return@registerForActivityResult deny("unavailable")
            finish()
        } else {
            deny("declined")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra(EXTRA_DEVICE) ?: return finish()
        PhoneAudio.clearAsk(this, deviceId)
        request = PhoneAudio.pending?.takeIf { it.deviceId == deviceId } ?: return finish()
        // Rotated or recreated after the person answered: that answer is already being acted on.
        if (savedInstanceState?.getBoolean(STATE_ANSWERED) == true) return
        if (PhoneAudio.consented(this, deviceId)) return allowed()
        val desk = DeskNames.of(deviceId)
        val mic = request.source == "mic"
        AlertDialog.Builder(this)
            .setTitle(getString(if (mic) R.string.audio_consent_mic else R.string.audio_consent_sound, desk))
            .setMessage(getString(if (mic) R.string.audio_consent_mic_text else R.string.audio_consent_sound_text, desk))
            .setPositiveButton(R.string.audio_allow) { _, _ ->
                answered = true
                PhoneAudio.rememberConsent(this, deviceId)
                allowed()
            }
            .setNegativeButton(R.string.audio_deny) { _, _ -> deny("declined") }
            .setOnCancelListener { deny("declined") }
            .show()
    }

    private var answered = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ANSWERED, answered || PhoneAudio.consented(this, deviceId))
    }

    /** Playback capture is an AudioRecord too: both sources need RECORD_AUDIO. */
    private fun allowed() {
        if (!PhoneAudio.hasMicPermission(this)) micPermission.launch(Manifest.permission.RECORD_AUDIO) else proceed()
    }

    private fun proceed() {
        PhoneAudio.pending = null
        if (request.source == "mic") {
            // Android 14+ refuses a microphone service the moment this window is no longer in front.
            if (!PhoneAudioService.startMic(this, deviceId)) return deny("unavailable")
            finish()
            return
        }
        // Phone sound while the screen is shared reuses that projection: no second prompt.
        val coordinator = DeskLinkApplication.getInstance().webRtcCoordinator(deviceId)
        if (coordinator?.startAudioSendingWithScreen() == true) return finish()
        val manager = ContextCompat.getSystemService(this, MediaProjectionManager::class.java) ?: return deny("unsupported")
        projection.launch(manager.createScreenCaptureIntent())
    }

    private fun deny(reason: String) {
        PhoneAudio.pending = null
        DeskLinkApplication.getInstance().webRtcCoordinator(deviceId)?.denyAudio(reason)
        finish()
    }

    companion object {
        const val EXTRA_DEVICE = "deviceId"
        private const val STATE_ANSWERED = "answered"
    }
}
