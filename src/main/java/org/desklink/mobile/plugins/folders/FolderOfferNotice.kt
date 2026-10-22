/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.helpers.NotificationHelper

/** P1: "<desk> wants to sync “Projects”": a tap opens Synced folders to pick the phone's side. */
object FolderOfferNotice {
    private const val ID = 7480

    fun show(context: Context, deviceId: String, name: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val desk = DeskLinkApplication.getInstance().getDevice(deviceId)?.name.orEmpty()
        val open = Intent(context, SyncedFoldersActivity::class.java).putExtra("deviceId", deviceId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(context, ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            manager.notify(
                "folders:$deviceId", ID,
                NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.folders_offer_title, desk, name))
                    .setContentText(context.getString(R.string.folders_offer_text))
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .setTimeoutAfter(FolderEngine.OFFER_LAPSES_MS)
                    .build(),
            )
        }
    }
}
