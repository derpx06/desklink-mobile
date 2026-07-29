package org.desklink.mobile.plugins.screen

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import org.desklink.mobile.DeskLinkApplication

/** One user-visible permission request for a phone-screen stream. */
class ScreenProjectionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, PhoneScreenCaptureService::class.java)
                .putExtra(PhoneScreenCaptureService.EXTRA_DEVICE_ID, deviceId)
                .putExtra(PhoneScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(PhoneScreenCaptureService.EXTRA_RESULT_DATA, data)
            var started = false
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                started = true
            } catch (_: RuntimeException) {
                // Finish the permission activity even when Android rejects a
                // background foreground-service start.
            }
            DeskLinkApplication.getInstance()
                .getDevicePlugin(deviceId, ScreenControlPlugin::class.java)
                ?.onPhoneCapturePermissionFinished(started)
        } else if (requestCode == REQUEST_CAPTURE) {
            DeskLinkApplication.getInstance()
                .getDevicePlugin(deviceId, ScreenControlPlugin::class.java)
                ?.onPhoneCapturePermissionFinished(false)
            DeskLinkApplication.getInstance().reportWebRtcScreenCaptureFailure(
                deviceId,
                "Phone screen-sharing permission was not granted",
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
        private const val REQUEST_CAPTURE = 7001
    }
}
