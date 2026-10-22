/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.audio

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.plugins.screen.SessionLiveNotification

/**
 * F1: holds this phone's microphone (microphone-type foreground service) or its own projection for
 * phone sound (mediaProjection type) while a desk hears it, with the coral in-use notification and
 * Stop. Started only from a visible DeskLink window, as Android 14+ requires.
 */
class PhoneAudioService : Service() {
    private var deviceId: String? = null
    private var projection: MediaProjection? = null
    /** The session this service carries: its late end never stops a newer one. */
    private var sessionId: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = DeskLinkApplication.getInstance()
        if (intent?.action == ACTION_STOP) {
            app.webRtcCoordinator(deviceId)?.stopAudioSending("stopped", notifyPeer = true, onlySessionId = sessionId)
            stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(EXTRA_DEVICE) ?: return stop()
        deviceId = id
        val sound = intent.hasExtra(EXTRA_RESULT_DATA)
        startForegroundCompat(notification(app.getDevice(id)?.name.orEmpty(), sound), sound)
        if (sound) {
            val data = resultData(intent) ?: return stop()
            val manager = ContextCompat.getSystemService(this, MediaProjectionManager::class.java) ?: return stop()
            projection = runCatching { manager.getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), data) }.getOrNull()
                ?: return stop()
            // Android 14+ wants a callback before the projection is used.
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    app.webRtcCoordinator(deviceId)?.stopAudioSending("stopped", notifyPeer = true, onlySessionId = sessionId)
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
        }
        val coordinator = app.webRtcCoordinator(id)
        if (coordinator?.startAudioSending(projection) != true) {
            Log.w(TAG, "audio did not start for $id")
            stopSelf()
        } else {
            sessionId = coordinator.sendingSessionId()
        }
        return START_NOT_STICKY
    }

    private fun stop(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    private fun notification(desk: String, sound: Boolean): android.app.Notification {
        SessionLiveNotification.ensureChannel(this)
        val stop = PendingIntent.getService(
            this, 0, Intent(this, PhoneAudioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SessionLiveNotification.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(if (sound) R.string.audio_live_sound else R.string.audio_live_mic, desk))
            .setContentText(getString(if (sound) R.string.audio_live_sound_text else R.string.audio_live_mic_text))
            .setColor(SessionLiveNotification.CORAL)
            .setColorized(true)
            .setOngoing(true)
            .addAction(R.drawable.sym_stop_circle, getString(R.string.camera_stop), stop)
            .build()
    }

    private fun startForegroundCompat(notification: android.app.Notification, sound: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val type = if (sound) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        sessionId?.let { DeskLinkApplication.getInstance().webRtcCoordinator(deviceId)?.stopAudioSending("stopped", notifyPeer = true, onlySessionId = it) }
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun resultData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_RESULT_DATA)

    companion object {
        private const val TAG = "DeskLink/Audio"
        private const val NOTIFICATION_ID = 1736
        private const val ACTION_STOP = "org.desklink.mobile.plugins.audio.STOP"
        private const val EXTRA_DEVICE = "deviceId"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"

        /** The microphone, from a visible activity. */
        fun startMic(context: Context, deviceId: String) =
            ContextCompat.startForegroundService(context, Intent(context, PhoneAudioService::class.java).putExtra(EXTRA_DEVICE, deviceId))

        /** The phone's sound, with the projection the person just allowed. */
        fun startSound(context: Context, deviceId: String, resultCode: Int, data: Intent) {
            if (resultCode != Activity.RESULT_OK) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhoneAudioService::class.java)
                    .putExtra(EXTRA_DEVICE, deviceId)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data),
            )
        }
    }
}
