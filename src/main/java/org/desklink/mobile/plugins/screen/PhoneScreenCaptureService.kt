package org.desklink.mobile.plugins.screen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R

/** Foreground MediaProjection owner for the WebRTC VP8 screen track. */
class PhoneScreenCaptureService : Service() {
    private var deviceId: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.product_name))
            .setContentText(getString(R.string.remote_screen_connected))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (!DeskLinkApplication.getInstance().startWebRtcScreenCapture(deviceId, data)) {
            Log.w(TAG, "Could not start the authenticated DeskLink WebRTC screen track")
            stopSelf()
        }
    }

    override fun onDestroy() {
        DeskLinkApplication.getInstance().stopWebRtcScreenCapture(deviceId)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun getResultData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_RESULT_DATA)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notification_channel_screen_sharing), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val TAG = "DeskLink/PhoneScreen"
        private const val CHANNEL = "desklink-screen-capture"
        private const val NOTIFICATION_ID = 1701
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
    }
}
