package org.desklink.mobile.plugins.screen

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.helpers.DeskNames

/** One user-visible permission request for a phone-screen stream. */
class ScreenProjectionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_DEVICE_ID)?.let { ScreenControlPlugin.cancelAsk(this, it) }
        // Recreated (a rotation under the system prompt): the first prompt is still up and its answer comes here.
        if (savedInstanceState != null) return
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        // Allow on an ask the desk already gave up on: said now, not after Android's sheet.
        if (deviceId != null && !DeskLinkApplication.getInstance().deskAwaitsPhoneScreen(deviceId)) {
            stoppedWaiting(deviceId)
            finish()
            return
        }
        // The person said Allow; Android's sheet can take a while: the desk is told to keep waiting.
        DeskLinkApplication.getInstance().keepPhoneScreenAskAlive(deviceId)
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
        // Only a complete request starts the foreground service: one it would have to stop before startForeground crashes the app.
        val app = DeskLinkApplication.getInstance()
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null && !deviceId.isNullOrBlank() &&
            !app.deskAwaitsPhoneScreen(deviceId)
        ) {
            // Shared after the desk stopped waiting (F25): nothing would show it, and a silent refusal leaves the
            // person thinking it works. Said here, and the desk already says it gave up (with Ask again).
            stoppedWaiting(deviceId)
        } else if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null && !deviceId.isNullOrBlank()) {
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

    private fun stoppedWaiting(deviceId: String?) {
        val words = getString(R.string.screen_share_desk_stopped_waiting, DeskNames.of(deviceId))
        DeskLinkApplication.getInstance().getDevicePlugin(deviceId, ScreenControlPlugin::class.java)?.let {
            it.onPhoneCapturePermissionFinished(false)
            it.onPhoneCaptureStopped(words)
        }
        android.widget.Toast.makeText(this, words, android.widget.Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
        private const val REQUEST_CAPTURE = 7001
    }
}
