/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.handoff

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.plugins.terminal.TerminalActivity
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.webrtc.terminal.TerminalProtocol

/** What a `desklink.handoff` names: the terminal to continue, its title and its folder. */
data class Handoff(val sessionId: String, val title: String, val path: String) {
    companion object {
        /** The session id must be a terminal id the terminal channel would accept. */
        fun parse(np: NetworkPacket): Handoff? {
            val id = np.getStringOrNull("sessionId") ?: return null
            if (id.isEmpty() || id.length > TerminalProtocol.MAX_TERMINAL_ID_LENGTH || !id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }) return null
            return Handoff(id, np.getString("title", "").take(TerminalProtocol.MAX_TITLE_CHARS), np.getString("path", "~").take(TerminalProtocol.MAX_CWD_CHARS))
        }
    }
}

/**
 * Lock & Hand Off at the desk: "You locked arch-desk · Continue ~/api on your phone?" as a heads-up
 * with Not now / Continue. Continue opens that terminal here; nothing is attached before that.
 */
@LoadablePlugin
class HandoffPlugin : Plugin() {
    override val displayName: String
        get() = context.getString(R.string.pref_plugin_handoff)

    override val description: String
        get() = context.getString(R.string.pref_plugin_handoff_desc)

    // A missing notification permission is caught by runCatching: the hand-off is just not offered.
    @android.annotation.SuppressLint("MissingPermission")
    override fun onPacketReceived(np: NetworkPacket): Boolean {
        val handoff = Handoff.parse(np) ?: return false
        val tag = tag(device.deviceId)
        val open = PendingIntent.getActivity(
            context,
            tag.hashCode(),
            TerminalActivity.intent(context, device.deviceId)
                .putExtra(TerminalActivity.EXTRA_TERMINAL_ID, handoff.sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismiss = PendingIntent.getBroadcast(
            context,
            tag.hashCode(),
            Intent(context, DismissReceiver::class.java).putExtra(EXTRA_TAG, tag),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.handoff_title, device.name))
            .setContentText(context.getString(R.string.handoff_text, handoff.path))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.handoff_not_now), dismiss)
            .addAction(0, context.getString(R.string.handoff_continue), open)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, notification) }
        return true
    }

    override val supportedPacketTypes: Array<String> = arrayOf(DeskLinkProtocol.PACKET_TYPE_HANDOFF)
    override val outgoingPacketTypes: Array<String> = emptyArray()

    /** Not now: just the notification goes. */
    class DismissReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra(EXTRA_TAG)?.let { NotificationManagerCompat.from(context).cancel(it, NOTIFICATION_ID) }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 7320
        private const val EXTRA_TAG = "tag"
        private const val TIMEOUT_MS = 10 * 60 * 1000L
        fun tag(deviceId: String) = "handoff:$deviceId"
    }
}
