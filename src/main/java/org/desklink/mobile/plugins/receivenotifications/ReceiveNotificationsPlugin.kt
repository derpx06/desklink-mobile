/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.receivenotifications

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.ui.MainActivity
import org.desklink.mobile.R
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

@LoadablePlugin
class ReceiveNotificationsPlugin : Plugin() {
    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_receive_notifications)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_receive_notifications_desc)

    override val isEnabledByDefault: Boolean = false

    override fun onCreate(): Boolean {
        // request all existing notifications
        val np = NetworkPacket(PACKET_TYPE_NOTIFICATION_REQUEST)
        np["request"] = true
        device.sendPacket(np)
        return true
    }

    /**
     * Power-of-two downscale factor that brings [srcWidth]x[srcHeight] to at
     * most [targetWidth]x[targetHeight], so a hostile or merely careless icon
     * cannot size a decode buffer.
     */
    private fun sampleSizeFor(
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return 1
        }
        var sampleSize = 1
        while (srcWidth / sampleSize > targetWidth || srcHeight / sampleSize > targetHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        // Both of these returned without touching the payload. Over WebRTC a
        // payload is a FileInputStream over a temp file whose deletion happens
        // in Payload.close(), so dropping one leaks a file descriptor *and*
        // leaves the temp file behind.
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
        if (np.getBoolean("snapshot", false)) {
            // S1 desk -> phone: every posted notification of this desk not listed is gone there.
            np.payload?.close()
            val keep = np.getStringList("ids").orEmpty().toSet()
            val posted = manager?.activeNotifications.orEmpty().mapNotNull { it.tag }
            DeskNotification.stale(posted, device.deviceId, keep).forEach { manager?.cancel(it, NOTIFICATION_ID) }
            return true
        }
        val note = DeskNotification.parse(np)
        if (note == null) {
            Log.e("NotificationsPlugin", "Received notification packet lacks properties")
            np.payload?.close()
            return true
        }
        if (note.cancel) {
            np.payload?.close()
            manager?.cancel(DeskNotification.tag(device.deviceId, note.id), NOTIFICATION_ID)
            return true
        }

        val resultPendingIntent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        var largeIcon: Bitmap? = null
        val payload = np.payload
        if (payload != null && payload.payloadSize != 0L) {
            val width = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
            val height = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height)
            // Bounded decode. This used to hand the peer's bytes straight to
            // decodeStream with no dimension cap, so a 20000x20000 PNG asked
            // for a ~1.6 GB allocation and took the process down with an OOM
            // before the payload below could even be closed.
            //
            // The bytes are read once into memory (payloadSize is already
            // bounded by the transfer layer) so the bounds pass and the real
            // decode can both see them without a second stream.
            val encoded = try {
                payload.inputStream?.use { it.readBytes() } ?: ByteArray(0)
            } catch (error: Exception) {
                Log.w("NotificationsPlugin", "Could not read a notification icon", error)
                ByteArray(0)
            } finally {
                payload.close()
            }

            if (encoded.isNotEmpty()) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, width, height)
                }
                largeIcon = try {
                    BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
                } catch (error: OutOfMemoryError) {
                    Log.w("NotificationsPlugin", "Notification icon too large to decode", error)
                    null
                }
            }

            if (largeIcon != null) {
                // Log.i("NotificationsPlugin", "hasPayload: size=${largeIcon.width}/${largeIcon.height} opti=$width/$height")
                if (largeIcon.width > width || largeIcon.height > height) {
                    // older API levels don't scale notification icons automatically, therefore:
                    largeIcon = largeIcon.scale(width, height, false)
                }
            }
        }

        val notificationManager = manager ?: return true
        val channel = DeskNotification.channelId(device.deviceId)
        // Its own channel, "From <desk>", so the person can silence the desk without the rest.
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(channel, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.desk_notifications_channel, device.name))
                .build(),
        )
        val tag = DeskNotification.tag(device.deviceId, note.id)
        // Swiped away here: closed on the desk too (contract C1).
        val dismiss = PendingIntent.getBroadcast(
            context,
            tag.hashCode(),
            Intent(context, DismissReceiver::class.java)
                .putExtra(EXTRA_DEVICE, device.deviceId)
                .putExtra(EXTRA_ID, note.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val noti =
            NotificationCompat.Builder(context, channel)
                .setContentTitle(note.title)
                .setContentText(note.text)
                .setSubText(note.appName.takeIf { it != note.title })
                .setContentIntent(resultPendingIntent)
                .setDeleteIntent(dismiss)
                .setTicker(note.text)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(largeIcon)
                .setAutoCancel(true)
                .setOngoing(!note.clearable)
                .setLocalOnly(true) // to avoid bouncing the notification back to other kdeconnect nodes
                // A resync (silent) re-posts what is already here: never a second sound.
                .setSilent(note.silent)
                .setOnlyAlertOnce(true)
                .setStyle(NotificationCompat.BigTextStyle().bigText(note.text))
                .apply { if (note.time > 0) setWhen(note.time).setShowWhen(true) }
                .build()

        notificationManager.notify(tag, NOTIFICATION_ID, noti)

        return true
    }

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_NOTIFICATION)

    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_NOTIFICATION_REQUEST)

    override val requiredPermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf()
    }

    override val permissionExplanation: Int = R.string.receive_notifications_permission_explanation

    /** Swiped away on the phone: ask the desk to close it (`notification.request {cancel}`). */
    class DismissReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val deviceId = intent.getStringExtra(EXTRA_DEVICE) ?: return
            val id = intent.getStringExtra(EXTRA_ID) ?: return
            val device = DeskLinkApplication.getInstance().getDevice(deviceId) ?: return
            device.sendPacket(NetworkPacket(PACKET_TYPE_NOTIFICATION_REQUEST).apply { this["cancel"] = id })
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 7330
        private const val EXTRA_DEVICE = "deviceId"
        private const val EXTRA_ID = "id"
        private const val PACKET_TYPE_NOTIFICATION = DeskLinkProtocol.PACKET_TYPE_NOTIFICATION
        private const val PACKET_TYPE_NOTIFICATION_REQUEST = DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_REQUEST
    }
}
