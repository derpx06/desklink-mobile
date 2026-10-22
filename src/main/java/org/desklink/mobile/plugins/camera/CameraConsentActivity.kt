/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R

/**
 * "<desk> wants to use this phone's camera": asked on screen the first time per desk, then
 * remembered. Visible, so it may start the camera service (Android allows that from a visible app only).
 */
class CameraConsentActivity : AppCompatActivity() {
    private lateinit var deviceId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra(EXTRA_DEVICE) ?: return finish()
        PhoneCamera.clearAsk(this, deviceId)
        if (PhoneCamera.state.pending?.deviceId != deviceId) return finish()
        val desk = DeskLinkApplication.getInstance().getDevice(deviceId)?.name.orEmpty()
        if (PhoneCamera.consented(this, deviceId)) return allowed()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.camera_consent_title, desk))
            .setMessage(getString(R.string.camera_consent_text, desk))
            .setPositiveButton(R.string.camera_consent_allow) { _, _ ->
                PhoneCamera.rememberConsent(this, deviceId, true)
                allowed()
            }
            .setNegativeButton(R.string.camera_consent_deny) { _, _ -> denied() }
            .setOnCancelListener { denied() }
            .show()
    }

    private fun allowed() {
        // The camera, and the microphone when the desk asked for sound (C4 `audio:true`).
        val wanted = listOfNotNull(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO.takeIf { PhoneCamera.state.pending?.audio == true },
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, wanted.toTypedArray(), REQUEST_CAMERA)
            return
        }
        CameraService.start(this, deviceId)
        finish()
    }

    private fun denied() {
        DeskLinkApplication.getInstance().webRtcCoordinator(deviceId)?.stopCamera("denied", notifyPeer = true)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        // The camera must be granted; a refused microphone only means video without sound.
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (camera) {
            CameraService.start(this, deviceId)
            finish()
        } else {
            DeskLinkApplication.getInstance().webRtcCoordinator(deviceId)?.stopCamera("permission", notifyPeer = true)
            finish()
        }
    }

    companion object {
        const val EXTRA_DEVICE = "deviceId"
        private const val REQUEST_CAMERA = 41
    }
}
