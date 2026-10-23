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
        getSystemService(android.app.NotificationManager::class.java)
            ?.cancel(ScreenControlPlugin.PROMPT_NOTIFICATION_ID)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // The whole display, never "share one app": a remote view of a single
        // app cannot show the launcher, the shade or the app switcher, and
        // control of the rest of the phone would be blind.
        val intent = if (android.os.Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay(),
            )
        } else {
            manager.createScreenCaptureIntent()
        }
        startActivityForResult(intent, REQUEST_CAPTURE)
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
            // Android refused the service (a background start): the desk is told, as for a declined prompt.
            if (!started) DeskLinkApplication.getInstance().reportWebRtcScreenCaptureFailure(deviceId, "Android did not allow sharing the screen")
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
