/*
 * SPDX-FileCopyrightText: 2017 Matthijs Tijink <matthijstijink@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.desklink.mobile.plugins.mpris

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.extensions.getParcelableCompat

/**
 * Called when the mpris media notification's buttons are pressed
 */
class MprisMediaNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Only the system's media buttons arrive here (this receiver is exported for them): any other action is ignored.
        // The notification's own buttons go to [MprisNotificationActionReceiver], which is not exported.
        if (Intent.ACTION_MEDIA_BUTTON != intent.action) return
        val mediaSession = MprisMediaSession.getMediaSession() ?: return
        mediaSession.controller.dispatchMediaButtonEvent(intent.getParcelableCompat(Intent.EXTRA_KEY_EVENT))
    }

    companion object {
        const val TAG: String = "M.M.N.Receiver"

        const val ACTION_PLAY: String = "ACTION_PLAY"
        const val ACTION_PAUSE: String = "ACTION_PAUSE"
        const val ACTION_PREVIOUS: String = "ACTION_PREVIOUS"
        const val ACTION_NEXT: String = "ACTION_NEXT"
        const val ACTION_CLOSE_NOTIFICATION: String = "ACTION_CLOSE_NOTIFICATION"

        const val EXTRA_DEVICE_ID: String = "deviceId"
        const val EXTRA_MPRIS_PLAYER: String = "player"
    }
}
