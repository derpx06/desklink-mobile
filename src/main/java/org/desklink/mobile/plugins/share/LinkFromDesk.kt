/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.desklink.mobile.R
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.plugins.clipboard.ClipboardListener

/**
 * Links (contract C3). The desk's `share.request {url}` becomes a heads-up "Open link from <desk>"
 * with Open and Copy: Android 10+ forbids starting the browser from the background. The other way,
 * the share sheet's "Open on computer" sends the link as `url`.
 */
object LinkFromDesk {
    private const val EXTRA_URL = "url"
    private const val EXTRA_ID = "id"
    /** The share-sheet entry "Open on computer" (an alias of the share activity). */
    const val OPEN_ON_DESK_ALIAS = "org.desklink.mobile.plugins.share.OpenOnDesk"
    private val LINK = Regex("""https?://[^\s<>"]+""")

    /** Shared through "Open on computer": the first web link in [text], or null otherwise. */
    @JvmStatic
    fun openOnDeskLink(intent: Intent?, text: String): String? =
        if (intent?.component?.className == OPEN_ON_DESK_ALIAS) firstLink(text) else null

    fun firstLink(text: String): String? = LINK.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';', ':')

    @JvmStatic
    fun show(context: Context, deskName: String, url: String) {
        val id = url.hashCode()
        val open = PendingIntent.getActivity(
            context, id,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val copy = PendingIntent.getBroadcast(
            context, id,
            Intent(context, CopyReceiver::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.link_from_desk, deskName))
            .setContentText(url)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.link_open), open)
            .addAction(0, context.getString(R.string.link_copy), copy)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(TAG, id, notification)
    }

    private const val TAG = "link-from-desk"

    /** "Copy": on the clipboard as the desk's copy (never sent back), and the notification goes. */
    class CopyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val url = intent.getStringExtra(EXTRA_URL) ?: return
            ClipboardListener.instance(context).setText(url)
            NotificationManagerCompat.from(context).cancel(TAG, intent.getIntExtra(EXTRA_ID, 0))
        }
    }
}
