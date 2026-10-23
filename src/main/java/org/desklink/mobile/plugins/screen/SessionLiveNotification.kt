/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.screen

import android.app.NotificationChannel
import org.desklink.mobile.helpers.DeskNames
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import java.util.concurrent.ConcurrentHashMap

/**
 * The coral "in use" notification while this phone views the desk's screen or has its terminals
 * open, with End. It is the way back to stopping a session after leaving the app; the phone's own
 * screen share has its own notification (the MediaProjection one, with Stop).
 */
object SessionLiveNotification {
    /** Shared with the MediaProjection notification: both say a session is live right now. */
    const val CHANNEL = "desklink-screen-capture"
    const val CORAL = 0xFFE5533D.toInt()
    private const val ACTION_END = "org.desklink.mobile.plugins.screen.END_SESSION"
    private const val EXTRA_DEVICE_ID = "deviceId"
    private const val BASE_ID = 1710

    private data class Live(val screen: Boolean = false, val terminals: Int = 0, val glide: Boolean = false)

    private val live = ConcurrentHashMap<String, Live>()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_screen_sharing), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    /**
     * Derived, never asserted: what is live is read from the session state itself (the desk view
     * from the screen plugin, terminals from the app-owned terminal client), so a lost link, a
     * closed activity or a reconnect can never leave the notification out of step.
     */
    fun refresh(context: Context, deviceId: String?) {
        deviceId ?: return
        val app = DeskLinkApplication.getInstance()
        val screen = app.getDevice(deviceId)?.getPlugin(ScreenControlPlugin::class.java)?.remoteLive == true
        val terminals = app.terminalClient(deviceId)?.state?.value?.sessions?.count { it.attached && !it.gone } ?: 0
        val glide = org.desklink.mobile.plugins.inputdevicesreceiver.Glide.isGliding(deviceId)
        update(context, deviceId, screen, terminals, glide)
    }

    private fun update(context: Context, deviceId: String?, screen: Boolean, terminals: Int, glide: Boolean = false) {
        deviceId ?: return
        val now = live.compute(deviceId) { _, old ->
            Live(screen, terminals, glide).takeIf { it.screen || it.terminals > 0 || it.glide }
        }
        val manager = NotificationManagerCompat.from(context)
        val id = BASE_ID + (deviceId.hashCode() and 0xFFF)
        if (now == null) {
            manager.cancel(id)
            return
        }
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)
        val desk = DeskNames.of(deviceId)
        val parts = buildList {
            if (now.screen) add(context.getString(R.string.session_live_screen))
            if (now.glide) add(context.getString(R.string.session_live_glide, desk))
            if (now.terminals > 0) add(context.resources.getQuantityString(R.plurals.session_live_terminals, now.terminals, now.terminals))
        }
        val end = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, EndReceiver::class.java).setAction(ACTION_END).putExtra(EXTRA_DEVICE_ID, deviceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.session_live_title, desk))
            .setContentText(parts.joinToString(" · "))
            .setColor(CORAL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(R.drawable.sym_stop_circle, context.getString(R.string.active_session_end), end)
            .build()
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Notifications not allowed: the in-app row still offers End.
        }
    }

    /** End from the notification: the same as Home's End. */
    class EndReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_END) return
            val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return
            endAll(context, deviceId)
        }
    }

    /** Stops the desk view, detaches the terminals and ends sharing this phone's screen. */
    fun endAll(context: Context, deviceId: String) {
        val app = DeskLinkApplication.getInstance()
        app.stopRemoteSession(deviceId)
        app.terminalClient(deviceId)?.detachAll()
        if (PhoneScreenCaptureService.sharingDeviceId == deviceId) {
            context.stopService(Intent(context, PhoneScreenCaptureService::class.java))
        }
        // F1: this phone's microphone or sound to that desk ends too, and the desk's sound here.
        app.webRtcCoordinator(deviceId)?.stopAudioSending("stopped", notifyPeer = true)
        app.webRtcCoordinator(deviceId)?.stopDeskSound()
        // F4: a glide ends and the desk gets its pointer back.
        app.getDevice(deviceId)?.getPlugin(org.desklink.mobile.plugins.inputdevicesreceiver.InputDevicesReceiverPlugin::class.java)
            ?.takeIf { org.desklink.mobile.plugins.inputdevicesreceiver.Glide.activeDesk == deviceId }?.stop(release = true)
        update(context, deviceId, screen = false, terminals = 0)
    }

    /** Whether a notification is up for [deviceId] (for tests and the Home row). */
    fun isLive(deviceId: String) = live.containsKey(deviceId)
}
