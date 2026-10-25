package org.desklink.mobile.plugins.screen

import android.app.Service
import org.desklink.mobile.helpers.DeskNames
import android.content.Context
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
            .setContentText(noticeText(this, desk, controlling = false, hearing = false))
            .setColor(SessionLiveNotification.CORAL)
            .setOngoing(true)
            .addAction(R.drawable.sym_stop_circle, getString(R.string.screen_share_stop), stop)
            .build()
        // Android 14/15 refuse this when the start came from the background or the projection grant has lapsed.
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "foreground start refused", it) }.isSuccess
        if (!started) {
            DeskLinkApplication.getInstance().reportWebRtcScreenCaptureFailure(deviceId, "Android did not allow sharing the screen")
            stopSelf()
            return
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
        /**
         * What the sharing notification says: that the computer sees the screen, and, when it is so, that it can also control the
         * phone and hears its sound. (It used to say only "stop at any time", whatever was going on.)
         */
        fun noticeText(context: Context, desk: String, controlling: Boolean, hearing: Boolean): String = buildList {
            if (controlling) add(context.getString(R.string.screen_share_live_controlling, desk))
            if (hearing) add(context.getString(R.string.screen_share_live_hearing, desk))
            add(context.getString(R.string.screen_share_live_text))
        }.joinToString(" · ")

        /** The share's notification follows what the computer can do now (control, sound). Safe to call at any time. */
        fun refreshNotice(context: Context) {
            val id = sharingDeviceId ?: return
            val app = DeskLinkApplication.getInstance()
            val coordinator = app.webRtcCoordinator(id) ?: return
            val snapshot = coordinator.remoteSessionSnapshot()
            val controlling = snapshot.hasControlLease && snapshot.direction == org.desklink.mobile.webrtc.WebRtcScreenDirection.PHONE_TO_DESKTOP
            val hearing = org.desklink.mobile.plugins.audio.PhoneAudio.sendingTo == id
            val manager = androidx.core.app.NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            val stop = android.app.PendingIntent.getService(
                context, 0, Intent(context, PhoneScreenCaptureService::class.java).setAction(ACTION_STOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val desk = DeskNames.of(id)
            val notification = NotificationCompat.Builder(context, SessionLiveNotification.CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.screen_share_live_title, desk))
                .setContentText(noticeText(context, desk, controlling, hearing))
                .setColor(SessionLiveNotification.CORAL)
                .setOngoing(true)
                .addAction(R.drawable.sym_stop_circle, context.getString(R.string.screen_share_stop), stop)
                .build()
            runCatching { @Suppress("MissingPermission") manager.notify(NOTIFICATION_ID, notification) }
        }

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
