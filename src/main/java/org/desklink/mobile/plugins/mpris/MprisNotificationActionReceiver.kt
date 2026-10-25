/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mpris

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.desklink.mobile.DeskLinkApplication

/** The media notification's own buttons (play, pause, previous, next, dismiss). Not exported: only this app's PendingIntents reach it. */
class MprisNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == MprisMediaNotificationReceiver.ACTION_CLOSE_NOTIFICATION) {
            // The user dismissed the notification: actually handle its removal correctly
            MprisMediaSession.instance.closeMediaNotification()
            return
        }
        val deviceId = intent.getStringExtra(MprisMediaNotificationReceiver.EXTRA_DEVICE_ID)
        val plugin = DeskLinkApplication.getInstance().getDevicePlugin(deviceId, MprisPlugin::class.java) ?: return
        val player = plugin.getPlayerStatus(intent.getStringExtra(MprisMediaNotificationReceiver.EXTRA_MPRIS_PLAYER)) ?: return
        when (action) {
            MprisMediaNotificationReceiver.ACTION_PLAY -> player.sendPlay()
            MprisMediaNotificationReceiver.ACTION_PAUSE -> player.sendPause()
            MprisMediaNotificationReceiver.ACTION_PREVIOUS -> player.sendPrevious()
            MprisMediaNotificationReceiver.ACTION_NEXT -> player.sendNext()
            else -> Log.w("M.M.N.Receiver", "Unknown action: $action, ignore.")
        }
    }
}
