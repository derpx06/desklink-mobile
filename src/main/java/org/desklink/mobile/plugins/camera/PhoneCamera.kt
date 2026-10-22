/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.camera

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.desklink.mobile.R
import org.desklink.mobile.helpers.LifecycleHelper
import org.desklink.mobile.helpers.NotificationHelper

/** A desk's `camera-request` (contract C4), its size capped at 1920x1080 and 30 fps. */
data class CameraRequest(
    val deviceId: String,
    val remoteSessionId: String,
    val facing: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val audio: Boolean,
) {
    companion object {
        /** Long side at most 1920, short side at most 1080, keeping the shape; 1280x720@30 when unsaid. */
        fun cap(width: Int?, height: Int?, fps: Int?): Triple<Int, Int, Int> {
            val w = (width ?: 1280).coerceAtLeast(1)
            val h = (height ?: 720).coerceAtLeast(1)
            val long = maxOf(w, h)
            val short = minOf(w, h)
            val scale = minOf(1.0, 1920.0 / long, 1080.0 / short)
            fun even(v: Double) = (v.toInt() and 1.inv()).coerceAtLeast(2)
            return Triple(even(w * scale), even(h * scale), (fps ?: 30).coerceIn(1, 30))
        }

        fun of(deviceId: String, remoteSessionId: String, facing: String?, width: Int?, height: Int?, fps: Int?, audio: Boolean?): CameraRequest {
            val (w, h, f) = cap(width, height, fps)
            return CameraRequest(deviceId, remoteSessionId, if (facing == "front") "front" else "back", w, h, f, audio == true)
        }
    }
}

/**
 * One camera session at a time, phone-wide: a request is pending (waiting for the person) or live.
 * Pure state, so the rules are testable.
 */
class CameraState {
    enum class Offer { ACCEPTED, ALREADY_LIVE, BUSY }

    var pending: CameraRequest? = null
        private set
    var live: CameraRequest? = null
        private set

    @Synchronized
    fun offer(request: CameraRequest): Offer {
        val current = live ?: pending
        return when {
            current == null -> { pending = request; Offer.ACCEPTED }
            current.deviceId == request.deviceId && current.remoteSessionId == request.remoteSessionId ->
                if (live != null) Offer.ALREADY_LIVE else Offer.ACCEPTED
            // The same desk asking again replaces its own unanswered request.
            live == null && current.deviceId == request.deviceId -> { pending = request; Offer.ACCEPTED }
            else -> Offer.BUSY
        }
    }

    /** The person allowed it: the pending request for [deviceId] goes live. */
    @Synchronized
    fun start(deviceId: String): CameraRequest? {
        val request = pending?.takeIf { it.deviceId == deviceId } ?: return null
        pending = null
        live = request
        return request
    }

    /** Ends whatever [deviceId] has, pending or live. Returns what ended. */
    @Synchronized
    fun end(deviceId: String): CameraRequest? {
        val ended = live?.takeIf { it.deviceId == deviceId } ?: pending?.takeIf { it.deviceId == deviceId }
        if (live?.deviceId == deviceId) live = null
        if (pending?.deviceId == deviceId) pending = null
        return ended
    }
}

/** The phone side of "Phone as webcam": consent, the request notification and the service. */
object PhoneCamera {
    private const val TAG = "DeskLink/Camera"
    private const val NOTIFICATION_ASK = 1733
    val state = CameraState()

    private fun consentKey(deviceId: String) = "camera_consent_$deviceId"

    /** First time per desk, the person says yes on screen; remembered after that. */
    fun consented(context: Context, deviceId: String) =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(consentKey(deviceId), false)

    fun rememberConsent(context: Context, deviceId: String, allowed: Boolean) =
        PreferenceManager.getDefaultSharedPreferences(context).edit { putBoolean(consentKey(deviceId), allowed) }

    /**
     * A new request. A camera foreground service may start only while DeskLink is visible, so from
     * the background it is a heads-up the person taps (which opens [CameraConsentActivity]).
     */
    fun ask(context: Context, deskName: String, request: CameraRequest) {
        val open = Intent(context, CameraConsentActivity::class.java)
            .putExtra(CameraConsentActivity.EXTRA_DEVICE, request.deviceId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val remembered = consented(context, request.deviceId)
        val tap = PendingIntent.getActivity(context, NOTIFICATION_ASK, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.camera_request_title, deskName))
            .setContentText(context.getString(if (remembered) R.string.camera_request_tap_start else R.string.camera_request_tap_answer))
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(tag(request.deviceId), NOTIFICATION_ASK, notification)
        } else {
            Log.w(TAG, "camera request for ${request.deviceId} not shown: notifications are off")
        }
        // The heads-up always goes up (a background start may be silently refused); in front the
        // screen opens at once too, and takes the heads-up down.
        if (LifecycleHelper.isInForeground) runCatching { context.startActivity(open) }
    }

    fun clearAsk(context: Context, deviceId: String) =
        NotificationManagerCompat.from(context).cancel(tag(deviceId), NOTIFICATION_ASK)

    private fun tag(deviceId: String) = "camera:$deviceId"

    /** The peer went away: the camera stops with it (the desk asks again on the next link). */
    fun onPeerGone(context: Context, deviceId: String) {
        val ended = state.end(deviceId) ?: return
        clearAsk(context, deviceId)
        Log.i(TAG, "camera for ${ended.deviceId} ended with the link")
        context.stopService(Intent(context, CameraService::class.java))
    }
}
