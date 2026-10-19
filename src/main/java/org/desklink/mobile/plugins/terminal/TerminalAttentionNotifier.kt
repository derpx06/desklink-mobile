/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.helpers.NotificationHelper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Raises a local notification when a terminal that is not on screen starts waiting for the person
 * or finishes. Output has to settle first, so a prompt is judged once it is fully drawn.
 */
class TerminalAttentionNotifier(
    private val context: Context,
    private val deviceId: String,
    private val terminalId: String,
    private val scheduler: ScheduledExecutorService = SHARED_SCHEDULER,
) : TerminalScreen.Observer {
    private val tracker = AttentionTracker()
    private var pending: ScheduledFuture<*>? = null

    override fun onOutput(screen: TerminalScreen) {
        synchronized(this) {
            pending?.cancel(false)
            pending = scheduler.schedule({ settled(screen) }, SETTLE_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    override fun onEnded(screen: TerminalScreen, exitStatus: Long?) {
        if (TerminalForeground.terminalId == terminalId) return
        post(TerminalAttention.FINISHED, exitStatus?.let { context.getString(R.string.terminal_exited, it) }.orEmpty(), yesNo = false)
    }

    private fun settled(screen: TerminalScreen) {
        val tail = screen.tailText(TAIL_ROWS)
        val attention = tracker.onSettled(tail)
        if (attention == TerminalAttention.NONE || TerminalForeground.terminalId == terminalId) return
        post(attention, lastLine(tail), yesNo = attention == TerminalAttention.WAITING && offersYesNo(tail))
    }

    private fun post(attention: TerminalAttention, detail: String, yesNo: Boolean) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val deviceName = DeskLinkApplication.getInstance().devices[deviceId]?.name.orEmpty()
        val title = context.getString(
            if (attention == TerminalAttention.WAITING) R.string.terminal_attention_waiting else R.string.terminal_attention_finished,
        )
        val text = listOf(deviceName, detail).filter { it.isNotEmpty() }.joinToString(": ")
        val builder = NotificationCompat.Builder(context, NotificationHelper.Channels.TERMINAL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // Prompts can quote private output; the lock screen gets the title only.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, NotificationHelper.Channels.TERMINAL_ATTENTION)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .build(),
            )
            .setContentIntent(openIntent())
            .addAction(0, context.getString(R.string.terminal_notification_open), openIntent())
        if (yesNo) {
            builder.addAction(0, context.getString(R.string.terminal_notification_yes), replyIntent(TerminalAttentionReceiver.ACTION_YES))
            builder.addAction(0, context.getString(R.string.terminal_notification_no), replyIntent(TerminalAttentionReceiver.ACTION_NO))
        }
        // `notify` is a no-op without the permission, which was checked above.
        @Suppress("MissingPermission")
        manager.notify(tag(deviceId, terminalId), NOTIFICATION_ID, builder.build())
    }

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode("open"),
        Intent(context, TerminalActivity::class.java)
            .putExtra(TerminalActivity.EXTRA_DEVICE_ID, deviceId)
            .putExtra(TerminalActivity.EXTRA_TERMINAL_ID, terminalId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun replyIntent(action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode(action),
        Intent(context, TerminalAttentionReceiver::class.java)
            .setAction(action)
            .putExtra(TerminalActivity.EXTRA_DEVICE_ID, deviceId)
            .putExtra(TerminalActivity.EXTRA_TERMINAL_ID, terminalId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun requestCode(kind: String) = "$deviceId/$terminalId/$kind".hashCode()

    companion object {
        /** Output must be quiet this long before it is judged. */
        const val SETTLE_MILLIS = 1500L
        private const val TAIL_ROWS = 16
        private const val NOTIFICATION_ID = 1

        private val SHARED_SCHEDULER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "DeskLink-Terminal-Attention").apply { isDaemon = true }
        }

        fun tag(deviceId: String, terminalId: String) = "terminal:$deviceId:$terminalId"
    }
}

/**
 * The Yes / No buttons on an attention notification. Not exported, and the reply text is fixed
 * per action rather than taken from an extra, so nothing else can type into a terminal through it.
 */
class TerminalAttentionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val deviceId = intent.getStringExtra(TerminalActivity.EXTRA_DEVICE_ID) ?: return
        val terminalId = intent.getStringExtra(TerminalActivity.EXTRA_TERMINAL_ID) ?: return
        val reply = when (intent.action) {
            ACTION_YES -> "y\r"
            ACTION_NO -> "n\r"
            else -> return
        }
        DeskLinkApplication.getInstance().terminalClient(deviceId)?.sendInput(terminalId, reply.toByteArray())
        NotificationManagerCompat.from(context).cancel(TerminalAttentionNotifier.tag(deviceId, terminalId), 1)
    }

    companion object {
        const val ACTION_YES = "org.desklink.mobile.terminal.YES"
        const val ACTION_NO = "org.desklink.mobile.terminal.NO"
    }
}

/** The production screens: an emulator per terminal, wired to the attention notifier. */
object TerminalSinks {
    fun factory(context: Context, deviceId: String) = org.desklink.mobile.webrtc.terminal.TerminalSinkFactory { id, rows, cols, reply ->
        TerminalScreen(rows, cols, reply, TerminalAttentionNotifier(context.applicationContext, deviceId, id))
    }
}
