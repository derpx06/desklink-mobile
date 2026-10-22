/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.camera

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.plugins.screen.SessionLiveNotification

/**
 * "Phone as webcam" (C4): owns the camera while a desk uses it, as a camera-type foreground
 * service with the coral "<desk> is using this camera" notification, Flip and Stop.
 */
class CameraService : Service() {
    private var deviceId: String? = null
    /** The camera session this service carries: its late end never stops a newer one. */
    private var sessionId: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = DeskLinkApplication.getInstance()
        when (intent?.action) {
            ACTION_STOP -> {
                app.webRtcCoordinator(deviceId)?.stopCamera("stopped", notifyPeer = true, onlySessionId = sessionId)
                stopSelf()
            }
            ACTION_FLIP -> app.webRtcCoordinator(deviceId)?.flipCamera()
            else -> {
                val id = intent?.getStringExtra(EXTRA_DEVICE) ?: return stop()
                deviceId = id
                val desk = app.getDevice(id)?.name.orEmpty()
                // `audio:true` with the microphone granted: the service holds the microphone too.
                sessionId = PhoneCamera.state.pending?.takeIf { it.deviceId == id }?.remoteSessionId
                val withMic = PhoneCamera.state.pending?.audio == true && org.desklink.mobile.plugins.audio.PhoneAudio.hasMicPermission(this)
                startForegroundCompat(notification(desk), withMic)
                if (app.webRtcCoordinator(id)?.startCamera() != true) {
                    Log.w(TAG, "camera did not start for $id")
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun stop(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    private fun notification(desk: String): android.app.Notification {
        SessionLiveNotification.ensureChannel(this)
        fun action(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, CameraService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SessionLiveNotification.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.camera_live_title, desk))
            .setContentText(getString(R.string.camera_live_text))
            .setColor(SessionLiveNotification.CORAL)
            .setColorized(true)
            .setOngoing(true)
            .addAction(R.drawable.sym_refresh, getString(R.string.camera_flip), action(ACTION_FLIP, 1))
            .addAction(R.drawable.sym_stop_circle, getString(R.string.camera_stop), action(ACTION_STOP, 2))
            .build()
    }

    private fun startForegroundCompat(notification: android.app.Notification, withMic: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mic = if (withMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or mic)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        // However it ends (Stop, the desk, a lost link), the camera is released and the desk told.
        sessionId?.let { DeskLinkApplication.getInstance().webRtcCoordinator(deviceId)?.stopCamera("stopped", notifyPeer = true, onlySessionId = it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DeskLink/Camera"
        private const val NOTIFICATION_ID = 1734
        private const val ACTION_STOP = "org.desklink.mobile.plugins.camera.STOP"
        private const val ACTION_FLIP = "org.desklink.mobile.plugins.camera.FLIP"
        const val EXTRA_DEVICE = "deviceId"

        /** Started from a visible DeskLink window only: Android lets a camera service start no other way. */
        fun start(context: Context, deviceId: String) =
            ContextCompat.startForegroundService(context, Intent(context, CameraService::class.java).putExtra(EXTRA_DEVICE, deviceId))
    }
}
