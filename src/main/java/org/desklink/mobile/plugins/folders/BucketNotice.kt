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

/** F8b: "<desk> turned on the Bucket": a tap opens it to choose this phone's folder. */
object BucketNotice {
    private const val ID = 7480

    fun show(context: Context, deviceId: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val desk = DeskLinkApplication.getInstance().getDevice(deviceId)?.name.orEmpty()
        val open = BucketActivity.intent(context, deviceId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(context, ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            manager.notify(
                "bucket:$deviceId", ID,
                NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.bucket_offer_title, desk))
                    .setContentText(context.getString(R.string.bucket_offer_text))
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .setTimeoutAfter(BucketEngine.OFFER_LAPSES_MS)
                    .build(),
            )
        }
    }
}
