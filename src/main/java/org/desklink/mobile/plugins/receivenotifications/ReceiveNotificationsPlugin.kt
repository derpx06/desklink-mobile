/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.receivenotifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import org.desklink.mobile.helpers.NotificationHelper
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
        // leaves the temp file behind. The silent case is the common one:
        // NotificationsPlugin attaches an icon on every resync and marks it
        // silent, so each reconnect leaked one per notification.
        if ("ticker" !in np || "appName" !in np || "id" !in np) {
            Log.e("NotificationsPlugin", "Received notification packet lacks properties")
            np.payload?.close()
            return true
        }

        if (np.getBoolean("silent", false)) {
            np.payload?.close()
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

        val notificationManager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return true

        val noti =
            NotificationCompat.Builder(context, NotificationHelper.Channels.RECEIVENOTIFICATION)
                .setContentTitle(np.getString("appName"))
                .setContentText(np.getString("ticker"))
                .setContentIntent(resultPendingIntent)
                .setTicker(np.getString("ticker"))
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(largeIcon)
                .setAutoCancel(true)
                .setLocalOnly(true) // to avoid bouncing the notification back to other kdeconnect nodes
                .setDefaults(Notification.DEFAULT_ALL)
                .setStyle(NotificationCompat.BigTextStyle().bigText(np.getString("ticker")))
                .build()

        val id = np.getString("id")
        val intId = try { id.toInt() } catch (e: NumberFormatException) { 0 }
        notificationManager.notify("kdeconnectId:${id}", intId, noti)

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

    companion object {
        private const val PACKET_TYPE_NOTIFICATION = DeskLinkProtocol.PACKET_TYPE_NOTIFICATION
        private const val PACKET_TYPE_NOTIFICATION_REQUEST = DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_REQUEST
    }
}
