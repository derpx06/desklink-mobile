/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.capture

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.helpers.LifecycleHelper
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import java.io.File
import java.io.FileInputStream

/** A desk's `capture-request` (F3, I1). */
data class CaptureRequest(
    val deviceId: String,
    val captureId: String,
    val kind: String,
    val target: String,
    val at: Long = System.currentTimeMillis(),
) {
    companion object {
        val KINDS = setOf("photo", "scan", "text", "qr")
        val TARGETS = setOf("clipboard", "file")

        /** Null for a kind this phone cannot capture (answered `capture-cancelled {unsupported}`). */
        fun of(deviceId: String, captureId: String, kind: String?, target: String?): CaptureRequest? {
            if (kind !in KINDS) return null
            return CaptureRequest(deviceId, captureId, kind!!, if (target in TARGETS) target!! else "clipboard")
        }
    }
}

/**
 * One capture at a time, phone-wide: waiting for the person (a heads-up while DeskLink is behind),
 * then [CaptureActivity]. Unanswered for 120 s it is cancelled with `timeout`.
 */
object PhoneCapture {
    private const val TAG = "DeskLink/Capture"
    private const val NOTIFICATION_ID = 1737
    const val TIMEOUT_MS = 120_000L
    private val main = Handler(Looper.getMainLooper())

    /** The capture on screen or waiting for the person; null when none. */
    var current by mutableStateOf<CaptureRequest?>(null)
        private set

    /** The asking desk's name, for the screen's title. */
    @Volatile var deskName: String = ""
        private set

    /** True once [CaptureActivity] has it: the timeout no longer applies. */
    @Volatile private var opened = false

    /** A new request; returns the one it replaced (cancelled `user` to its desk). */
    fun request(context: Context, deskName: String, request: CaptureRequest): CaptureRequest? {
        val previous = current?.takeIf { it.captureId != request.captureId }
        this.deskName = deskName
        current = request
        opened = false
        val open = Intent(context, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        main.postDelayed({
            if (current?.captureId == request.captureId && !opened) {
                Log.i(TAG, "capture ${request.kind} timed out")
                finish(context, "timeout")
            }
        }, TIMEOUT_MS)
        val tap = PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(titleFor(request.kind), deskName))
            .setContentText(context.getString(R.string.capture_tap))
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
        // The heads-up always goes up (a background start may be silently refused); in front the
        // screen opens at once too, and takes the heads-up down.
        if (LifecycleHelper.isInForeground) runCatching { context.startActivity(open) }
        return previous
    }

    fun titleFor(kind: String) = when (kind) {
        "scan" -> R.string.capture_ask_scan
        "text" -> R.string.capture_ask_text
        "qr" -> R.string.capture_ask_qr
        else -> R.string.capture_ask_photo
    }

    /** [CaptureActivity] took the request on screen. */
    fun opened(context: Context): CaptureRequest? {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        opened = true
        return current
    }

    /** The desk withdrew it. */
    fun cancelledByDesk(context: Context, deviceId: String, captureId: String) {
        if (current?.deviceId != deviceId || current?.captureId != captureId) return
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        current = null
    }

    /** Ends the current capture with `capture-cancelled {reason}` to its desk. */
    fun finish(context: Context, reason: String) {
        val request = current ?: return
        current = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        DeskLinkApplication.getInstance().webRtcCoordinator(request.deviceId)?.sendCaptureCancelled(request.captureId, reason)
    }

    /** text/qr: the words themselves, in `capture-result`. */
    fun sendText(request: CaptureRequest, text: String): Boolean {
        val coordinator = DeskLinkApplication.getInstance().webRtcCoordinator(request.deviceId) ?: return false
        val sent = coordinator.sendCaptureResult(request.captureId, request.kind, text = CaptureText.fit(text))
        if (sent) current = null
        return sent
    }

    /**
     * photo/scan: `capture-result {mimeType, pages}`, then the file as a transfer whose offer carries
     * `captureId` (and `clipboard: true` for target clipboard). The file is deleted once sent or failed.
     */
    fun sendFile(request: CaptureRequest, file: File, mimeType: String, pages: Int): Boolean {
        val app = DeskLinkApplication.getInstance()
        val device = app.getDevice(request.deviceId)
        val coordinator = app.webRtcCoordinator(request.deviceId)
        if (device == null || coordinator == null || !coordinator.sendCaptureResult(request.captureId, request.kind, mimeType = mimeType, pages = pages)) {
            file.delete()
            return false
        }
        val packet = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
            this["filename"] = file.name
            this["captureId"] = request.captureId
            this["mimeType"] = mimeType
            if (request.target == "clipboard") this["clipboard"] = true
            payload = NetworkPacket.Payload(FileInputStream(file), file.length())
        }
        device.sendPacket(packet, object : Device.SendPacketStatusCallback() {
            override fun onSuccess() {
                file.delete()
                Log.i(TAG, "capture ${request.kind} sent")
            }
            override fun onFailure(e: Throwable) {
                file.delete()
                Log.w(TAG, "capture ${request.kind} not sent (${e.javaClass.simpleName})")
            }
        })
        current = null
        return true
    }

    /** Where captures wait before sending (FileProvider `capture/`), emptied of leftovers. */
    fun folder(context: Context): File = File(context.cacheDir, "capture").apply {
        mkdirs()
        listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L }?.forEach(File::delete)
    }
}
