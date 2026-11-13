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
import org.desklink.mobile.ui.MainActivity
import org.desklink.mobile.ui.surfaces.widgets.DeskWidgets

/**
 * The home-screen widget: the current desk with the DeskLink mark, its link as a dot plus words, this phone's battery, terminals with a
 * "finished" badge, now playing, and Lock / Send clipboard / Ring / Open terminals. A short 2×2 shows the computer with
 * Lock and Clipboard, a taller one all four actions; from about 4×3 the terminals and media rows join them.
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

        /** The layouts by the smallest size (dp) each needs: 2x2 short, 2x2 with two rows of actions, 4x3 and up. */
        internal val layouts = listOf(
            SizeF(110f, 110f) to R.layout.widget_desk_small,
            SizeF(110f, 180f) to R.layout.widget_desk_medium,
            SizeF(250f, 230f) to R.layout.widget_desk_large,
        )

        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val desk = DeskSurfaces.currentDesk(context)?.let { DeskSurfaces.snapshot(context, it) }
            ids.forEach { id ->
                val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    RemoteViews(layouts.associate { (size, layout) -> size to build(context, layout, desk) })
                } else {
                    val options = manager.getAppWidgetOptions(id)
                    val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                    val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                    build(context, (layouts.lastOrNull { it.first.width <= w && it.first.height <= h } ?: layouts.first()).second, desk)
                }
                runCatching { manager.updateAppWidget(id, views) }
            }
        }

        internal fun build(context: Context, layout: Int, desk: DeskSnapshot?): RemoteViews {
            val views = RemoteViews(context.packageName, layout)
            val large = layout == R.layout.widget_desk_large
            val (level, charging) = DeskSurfaces.phoneBattery(context)
            val battery = if (level >= 0) context.getString(if (charging) R.string.widget_battery_charging else R.string.widget_battery, level) else null
            val name = desk?.name
            val open = activity(context, 1, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEVICE_ID, desk?.id))

            val (tone, status) = when (desk?.link) {
                null -> org.desklink.mobile.ui.surfaces.widgets.Tone.OFF to context.getString(R.string.surfaces_not_paired)
                DeskLink.CONNECTED -> org.desklink.mobile.ui.surfaces.widgets.Tone.OK to context.getString(R.string.connection_connected)
                DeskLink.CONNECTING -> org.desklink.mobile.ui.surfaces.widgets.Tone.WAIT to context.getString(R.string.connection_connecting)
                DeskLink.OFFLINE -> org.desklink.mobile.ui.surfaces.widgets.Tone.OFF to (
                    DeskSurfaceRules.lastSeenLabel(desk?.lastSeen) { context.getString(R.string.widget_last_seen, DateUtils.formatSameDayTime(it, System.currentTimeMillis(), java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)) }
                        ?: context.getString(R.string.connection_offline)
                    )
            }
            // This phone's battery joins the status words: it is the phone's, so it never sits beside the computer's name.
            val words = listOfNotNull(status, battery?.takeIf { desk != null }).joinToString(" · ")
            DeskWidgets.header(context, views, name, tone, words, open)

            // Lock, Clipboard and Ring need the feature link; off, they stay (dimmed) and a tap says why. Terminals always opens.
            val ready = desk?.ready == true
            val reason = if (desk == null) context.getString(R.string.surfaces_not_paired) else status
            // At huge text the icons stay (each with its spoken name); the labels would be cut off.
            val labels = org.desklink.mobile.ui.surfaces.widgets.WidgetRules.roomForExtras(DeskWidgets.fontScale(context))
            fun button(root: Int, icon: Int, label: Int, named: Int, plain: Int, enabled: Boolean, tap: PendingIntent) {
                DeskWidgets.setEnabledLook(views, icon, label, enabled)
                views.setViewVisibility(label, if (labels) View.VISIBLE else View.GONE)
                views.setContentDescription(root, DeskWidgets.spokenAction(context, DeskWidgets.named(context, named, plain, name), enabled, reason))
                // With no computer at all, every button opens the app, where one is paired.
                views.setOnClickPendingIntent(root, if (desk == null) open else tap)
            }

            val badge = DeskSurfaceRules.terminalBadge(desk?.unseenFinished ?: 0)
            val finished = badge?.let { context.resources.getQuantityString(R.plurals.widget_finished, desk?.unseenFinished ?: 0, desk?.unseenFinished ?: 0) }
            views.setViewVisibility(R.id.widget_badge, if (badge != null) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_badge, badge ?: "")

            // The Terminals tab of the app, on this computer: the one list of terminals.
            val terminals = activity(context, 2, MainActivity.terminalsIntent(context, desk?.id))
            button(R.id.widget_lock, R.id.widget_lock_icon, R.id.widget_lock_label, R.string.widget_cd_lock, R.string.home_lock_desk, ready, action(context, DeskSurfaces.Action.LOCK, desk?.id))
            // Android 10+ reads the clipboard only for the app in front: the floating activity is it.
            button(R.id.widget_clipboard, R.id.widget_clipboard_icon, R.id.widget_clipboard_label, R.string.widget_cd_clipboard, R.string.send_clipboard, ready, activity(context, 3, ClipboardFloatingActivity.getIntent(context, true)))
            button(R.id.widget_ring, R.id.widget_ring_icon, R.id.widget_ring_label, R.string.widget_cd_ring, R.string.home_ring_desk, ready, action(context, DeskSurfaces.Action.RING, desk?.id))
            button(R.id.widget_terminals, R.id.widget_terminals_icon, R.id.widget_terminals_label, R.string.widget_cd_terminals, R.string.widget_open_terminals, desk != null, terminals)
            // The badge is a number on the button: its meaning goes into the spoken name.
            if (finished != null && desk != null) views.setContentDescription(R.id.widget_terminals, context.getString(R.string.widget_cd_terminals, name) + ". " + finished)

            if (large) {
                val count = desk?.terminals ?: 0
                val attached = desk?.terminalAttached == true
                val countText = if (count == 0) context.getString(R.string.widget_no_terminals) else context.resources.getQuantityString(R.plurals.widget_terminals, count, count)
                // "open here" in words beside the coral dot: the dot alone was colour-only information.
                views.setTextViewText(R.id.widget_terminal_text, listOfNotNull(countText, context.getString(R.string.widget_terminal_open_here).takeIf { attached }).joinToString(" · "))
                views.setViewVisibility(R.id.widget_attached_dot, if (attached) View.VISIBLE else View.GONE)
                views.setContentDescription(
                    R.id.widget_terminal_row,
                    listOfNotNull(
                        DeskWidgets.named(context, R.string.widget_cd_terminals, R.string.widget_open_terminals, name),
                        countText,
                        context.getString(R.string.widget_terminal_attached).takeIf { attached },
                        finished,
                    ).joinToString(". "),
                )
                views.setOnClickPendingIntent(R.id.widget_terminal_row, if (desk == null) open else terminals)
                val title = desk?.mediaTitle
                views.setViewVisibility(R.id.widget_media_row, if (title != null) View.VISIBLE else View.GONE)
                if (title != null) {
                    views.setTextViewText(R.id.widget_media_title, title)
                    views.setImageViewResource(R.id.widget_play_pause, if (desk.mediaPlaying) R.drawable.sym_pause else R.drawable.sym_play_arrow)
                    views.setContentDescription(R.id.widget_play_pause, context.getString(if (desk.mediaPlaying) R.string.widget_cd_pause else R.string.widget_cd_play, desk.name) + ". " + title)
                    views.setOnClickPendingIntent(R.id.widget_play_pause, action(context, DeskSurfaces.Action.PLAY_PAUSE, desk.id))
                }
            }
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
