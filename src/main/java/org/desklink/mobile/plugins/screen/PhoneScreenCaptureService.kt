package org.desklink.mobile.plugins.screen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/** Foreground MediaProjection owner for phone -> desktop screen frames. */
class PhoneScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var deviceId: String? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private val sequence = AtomicLong()
    private var lastFrameAt = 0L

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
        if (projection != null) return
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

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        thread = HandlerThread("desklink-phone-screen").also { it.start() }
        handler = Handler(thread!!.looper)
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }
        projection?.registerCallback(projectionCallback!!, handler!!)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = minOf(metrics.widthPixels, 1280)
        val height = (metrics.heightPixels.toFloat() * width / metrics.widthPixels).toInt().coerceAtLeast(1)

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ imageReader ->
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < 160) {
                imageReader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rowPadding = (plane.rowStride - plane.pixelStride * width)
                    .coerceAtLeast(0) / plane.pixelStride.coerceAtLeast(1)
                val padded = Bitmap.createBitmap(width + rowPadding, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = if (rowPadding == 0) padded
                else Bitmap.createBitmap(padded, 0, 0, width, height)
                val bytes = ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 60, output)
                    output.toByteArray()
                }
                bitmap.recycle()
                if (padded !== bitmap) padded.recycle()
                val plugin = DeskLinkApplication.getInstance()
                    .getDevicePlugin(deviceId, ScreenControlPlugin::class.java)
                if (plugin != null) {
                    plugin.sendPhoneScreenFrame(bytes, width, height, sequence.incrementAndGet())
                    lastFrameAt = now
                }
            } catch (error: Exception) {
                Log.w(TAG, "Failed to encode phone screen frame", error)
            } finally {
                image.close()
            }
        }, handler)
        display = projection!!.createVirtualDisplay(
            "DeskLink phone screen", width, height, metrics.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
    }

    override fun onDestroy() {
        display?.release()
        reader?.close()
        projectionCallback?.let { projection?.unregisterCallback(it) }
        projection?.stop()
        thread?.quitSafely()
        thread = null
        handler = null
        projection = null
        projectionCallback = null
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
