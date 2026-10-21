/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.surfaces

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import org.desklink.mobile.R
import org.desklink.mobile.plugins.clipboard.ClipboardFloatingActivity
import org.desklink.mobile.plugins.terminal.TerminalActivity
import org.desklink.mobile.ui.MainActivity

/**
 * The home-screen widget: the current desk, its link, this phone's battery, terminals with a
 * "finished" badge, now playing, and Lock / Send clipboard / Ring / Open terminals. 2×2 shows the
 * essentials; from about 4×3 the terminals and media rows join them.
 */
class DeskWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, manager, ids)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        update(context, manager, intArrayOf(id))

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, DeskWidgetProvider::class.java))
            if (ids.isNotEmpty()) update(context, manager, ids)
        }

        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val desk = DeskSurfaces.currentDesk(context)?.let { DeskSurfaces.snapshot(context, it) }
            ids.forEach { id ->
                val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    RemoteViews(
                        mapOf(
                            SizeF(110f, 110f) to build(context, R.layout.widget_desk_small, desk),
                            SizeF(280f, 230f) to build(context, R.layout.widget_desk_large, desk),
                        ),
                    )
                } else {
                    val options = manager.getAppWidgetOptions(id)
                    val large = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) >= 280 &&
                        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) >= 230
                    build(context, if (large) R.layout.widget_desk_large else R.layout.widget_desk_small, desk)
                }
                runCatching { manager.updateAppWidget(id, views) }
            }
        }

        private fun build(context: Context, layout: Int, desk: DeskSnapshot?): RemoteViews {
            val views = RemoteViews(context.packageName, layout)
            val large = layout == R.layout.widget_desk_large
            val (level, charging) = DeskSurfaces.phoneBattery(context)
            val battery = if (level >= 0) context.getString(if (charging) R.string.widget_battery_charging else R.string.widget_battery, level) else ""

            views.setOnClickPendingIntent(R.id.widget_header, activity(context, 1, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEVICE_ID, desk?.id)))
            if (desk == null) {
                views.setTextViewText(R.id.widget_name, context.getString(R.string.app_name))
                views.setTextViewText(R.id.widget_status, context.getString(R.string.surfaces_not_paired))
                views.setImageViewResource(R.id.widget_dot, R.drawable.widget_dot_faint)
            } else {
                views.setTextViewText(R.id.widget_name, desk.name)
                val (dot, status) = when (desk.link) {
                    DeskLink.CONNECTED -> R.drawable.widget_dot_mint to context.getString(R.string.connection_connected)
                    DeskLink.CONNECTING -> R.drawable.widget_dot_amber to context.getString(R.string.connection_connecting)
                    DeskLink.OFFLINE -> R.drawable.widget_dot_faint to (
                        DeskSurfaceRules.lastSeenLabel(desk.lastSeen) { context.getString(R.string.widget_last_seen, DateUtils.formatSameDayTime(it, System.currentTimeMillis(), java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)) }
                            ?: context.getString(R.string.connection_offline)
                        )
                }
                views.setImageViewResource(R.id.widget_dot, dot)
                // The small layout has no battery slot: the phone's battery joins the status line.
                views.setTextViewText(R.id.widget_status, if (large || battery.isEmpty()) status else "$status · $battery")
            }
            if (large) views.setTextViewText(R.id.widget_battery, battery)

            val badge = DeskSurfaceRules.terminalBadge(desk?.unseenFinished ?: 0)
            views.setViewVisibility(R.id.widget_badge, if (badge != null) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_badge, badge ?: "")
            if (large) {
                val count = desk?.terminals ?: 0
                views.setTextViewText(
                    R.id.widget_terminal_text,
                    if (count == 0) context.getString(R.string.widget_no_terminals) else context.resources.getQuantityString(R.plurals.widget_terminals, count, count),
                )
                views.setViewVisibility(R.id.widget_attached_dot, if (desk?.terminalAttached == true) View.VISIBLE else View.GONE)
                views.setContentDescription(
                    R.id.widget_terminal_row,
                    listOfNotNull(
                        if (count == 0) context.getString(R.string.widget_no_terminals) else context.resources.getQuantityString(R.plurals.widget_terminals, count, count),
                        context.getString(R.string.widget_terminal_attached).takeIf { desk?.terminalAttached == true },
                        badge?.let { context.resources.getQuantityString(R.plurals.widget_finished, desk?.unseenFinished ?: 0, desk?.unseenFinished ?: 0) },
                    ).joinToString(", "),
                )
                val title = desk?.mediaTitle
                views.setViewVisibility(R.id.widget_media_row, if (title != null) View.VISIBLE else View.GONE)
                if (title != null) {
                    views.setTextViewText(R.id.widget_media_title, title)
                    views.setImageViewResource(R.id.widget_play_pause, if (desk.mediaPlaying) R.drawable.sym_pause else R.drawable.sym_play_arrow)
                    views.setContentDescription(R.id.widget_play_pause, context.getString(if (desk.mediaPlaying) R.string.mpris_pause else R.string.mpris_play))
                    views.setOnClickPendingIntent(R.id.widget_play_pause, action(context, DeskSurfaces.Action.PLAY_PAUSE, desk.id))
                }
            }

            val terminals = Intent(context, TerminalActivity::class.java).putExtra(TerminalActivity.EXTRA_DEVICE_ID, desk?.id.orEmpty())
            if (large) views.setOnClickPendingIntent(R.id.widget_terminal_row, activity(context, 2, terminals))
            views.setOnClickPendingIntent(R.id.widget_terminals, activity(context, 2, terminals))
            views.setOnClickPendingIntent(R.id.widget_lock, action(context, DeskSurfaces.Action.LOCK, desk?.id))
            views.setOnClickPendingIntent(R.id.widget_ring, action(context, DeskSurfaces.Action.RING, desk?.id))
            // Android 10+ reads the clipboard only for the app in front: the floating activity is it.
            views.setOnClickPendingIntent(R.id.widget_clipboard, activity(context, 3, ClipboardFloatingActivity.getIntent(context, true)))
            return views
        }

        private fun activity(context: Context, code: Int, intent: Intent): PendingIntent =
            PendingIntent.getActivity(
                context,
                code,
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun action(context: Context, action: DeskSurfaces.Action, deviceId: String?): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                100 + action.ordinal,
                Intent(context, DeskWidgetActionReceiver::class.java)
                    .setAction(DeskWidgetActionReceiver.ACTION)
                    .putExtra(DeskWidgetActionReceiver.EXTRA_ACTION, action.name)
                    .putExtra(DeskWidgetActionReceiver.EXTRA_DEVICE_ID, deviceId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}

/** The widget's buttons. Not exported: only this app's own PendingIntents reach it. */
class DeskWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val action = intent.getStringExtra(EXTRA_ACTION)?.let { runCatching { DeskSurfaces.Action.valueOf(it) }.getOrNull() } ?: return
        DeskSurfaces.perform(context, action, intent.getStringExtra(EXTRA_DEVICE_ID))
    }

    companion object {
        const val ACTION = "org.desklink.mobile.DESK_WIDGET_ACTION"
        const val EXTRA_ACTION = "action"
        const val EXTRA_DEVICE_ID = "deviceId"
    }
}
