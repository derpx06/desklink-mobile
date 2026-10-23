package org.desklink.mobile.plugins.screen

import android.app.Service
import org.desklink.mobile.helpers.DeskNames
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Foreground MediaProjection owner for the WebRTC VP8 screen track. */
class PhoneScreenCaptureService : Service() {
    private var deviceId: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The notification's Stop: release the view on the desk, then the projection.
            DeskLinkApplication.getInstance().stopRemoteSession(deviceId)
            DeskLinkApplication.getInstance().getDevicePlugin(deviceId, ScreenControlPlugin::class.java)
                ?.onPhoneCaptureStopped(getString(R.string.screen_share_stopped))
            stopSelf()
            return START_NOT_STICKY
        }
        deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.let { getResultData(it) }
        if (deviceId.isNullOrBlank() || resultCode != android.app.Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startCapture(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        SessionLiveNotification.ensureChannel(this)
        val desk = DeskNames.of(deviceId)
        val stop = android.app.PendingIntent.getService(
            this,
            0,
            Intent(this, PhoneScreenCaptureService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, SessionLiveNotification.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.screen_share_live_title, desk))
            .setContentText(getString(R.string.screen_share_live_text))
            .setColor(SessionLiveNotification.CORAL)
            .setOngoing(true)
            .addAction(R.drawable.sym_stop_circle, getString(R.string.screen_share_stop), stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        sharingDeviceId = deviceId
        if (!DeskLinkApplication.getInstance().startWebRtcScreenCapture(deviceId, data)) {
            Log.w(TAG, "Could not start the authenticated DeskLink WebRTC screen track")
            DeskLinkApplication.getInstance().reportWebRtcScreenCaptureFailure(
                deviceId,
                "Could not start the DeskLink WebRTC screen track",
            )
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (sharingDeviceId == deviceId) sharingDeviceId = null
        DeskLinkApplication.getInstance().stopWebRtcScreenCapture(deviceId)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun getResultData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_RESULT_DATA)

    companion object {
        private const val TAG = "DeskLink/PhoneScreen"
        private const val ACTION_STOP = "org.desklink.mobile.plugins.screen.STOP_PHONE_SCREEN"
        private const val NOTIFICATION_ID = 1701
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        /** The computer this phone's screen is shared with right now, for Home's live row. */
        var sharingDeviceId by androidx.compose.runtime.mutableStateOf<String?>(null)
            private set
    }
}
