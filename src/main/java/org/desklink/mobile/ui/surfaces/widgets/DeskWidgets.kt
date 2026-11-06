/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.surfaces.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.core.net.toUri
import org.desklink.mobile.R
import org.desklink.mobile.ui.MainActivity
import org.desklink.mobile.ui.compose.screen.settings.SettingsPage
import org.desklink.mobile.ui.surfaces.DeskSurfaces
import org.desklink.mobile.ui.surfaces.DeskWidgetActionReceiver
import java.util.concurrent.Executors

/**
 * The home-screen widget family, built from the same facts as the Home tiles ([WidgetFacts] and [WidgetRules]).
 * Nothing polls: [DeskSurfaces.refresh] redraws on a change, the system redraws on `updatePeriodMillis`
 * (30 minutes, which also corrects a picture left behind by a killed process), and each draw reads the live state.
 */
object DeskWidgets {
    private val family: List<FamilyWidget> = listOf(ComputerStatusWidget())

    /** Redraws every placed widget of the family. Cheap when there are none. */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val placed = family.map { it to manager.getAppWidgetIds(ComponentName(context, it.javaClass)) }.filter { it.second.isNotEmpty() }
        if (placed.isEmpty()) return
        val facts = runCatching { WidgetFacts.current(context) }.getOrNull()
        placed.forEach { (widget, ids) -> widget.update(context, manager, ids, facts) }
    }

    private const val WORKER = "desklink-widgets"
    private val worker = Executors.newSingleThreadExecutor { Thread(it, WORKER).apply { isDaemon = true } }

    internal fun onWorker() = Thread.currentThread().name == WORKER

    internal fun background(job: () -> Unit) = worker.execute { runCatching(job) }

    // ---- taps ---------------------------------------------------------------------------------

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** One PendingIntent per (kind, computer, extra): they differ in their data, which is what Android tells them apart by. */
    private fun key(kind: String, id: String?, extra: String = "") = "desklink-widget:///$kind/${id.orEmpty()}/$extra".toUri()

    private fun activity(context: Context, intent: Intent, kind: String, id: String?, extra: String = ""): PendingIntent =
        PendingIntent.getActivity(context, 0, intent.setData(key(kind, id, extra)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS)

    /** Opens the app on the computer's Home. */
    fun openApp(context: Context, id: String?): PendingIntent =
        activity(context, Intent(context, MainActivity::class.java).apply { if (id != null) putExtra(MainActivity.EXTRA_DEVICE_ID, id) }, "app", id)

    fun openFiles(context: Context, id: String?): PendingIntent =
        activity(context, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_FILES, true).apply { if (id != null) putExtra(MainActivity.EXTRA_DEVICE_ID, id) }, "files", id)

    fun openSettings(context: Context, id: String?): PendingIntent =
        activity(
            context,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SETTINGS_PAGE, SettingsPage.PRIVACY.route).apply { if (id != null) putExtra(MainActivity.EXTRA_DEVICE_ID, id) },
            "settings", id,
        )

    fun newNote(context: Context): PendingIntent = activity(context, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_NEW_NOTE), "note-new", null)

    /** "" opens the Notes list; an id opens that note. */
    fun openNote(context: Context, noteId: String): PendingIntent =
        activity(context, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_NOTE_ID, noteId), "note", null, noteId)

    /** A button that checks the live state first, then opens the screen, the file picker, the clipboard or a terminal. */
    fun act(context: Context, action: WidgetAction, id: String?): PendingIntent =
        activity(
            context,
            Intent(context, WidgetActionActivity::class.java).putExtra(WidgetActionActivity.EXTRA_ACTION, action.name).apply { if (id != null) putExtra(WidgetActionActivity.EXTRA_DEVICE_ID, id) },
            "act-${action.name}", id,
        )

    /** Ring and Lock go out at once, through the existing non-exported receiver and its checks. */
    fun send(context: Context, action: DeskSurfaces.Action, id: String?): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            200 + action.ordinal,
            Intent(context, DeskWidgetActionReceiver::class.java)
                .setAction(DeskWidgetActionReceiver.ACTION)
                .setData(key("send-${action.name}", id))
                .putExtra(DeskWidgetActionReceiver.EXTRA_ACTION, action.name)
                .apply { if (id != null) putExtra(DeskWidgetActionReceiver.EXTRA_DEVICE_ID, id) },
            FLAGS,
        )

    // ---- shared drawing -----------------------------------------------------------------------

    internal fun dot(tone: Tone): Int = when (tone) {
        Tone.OK -> R.drawable.widget_dot_mint
        Tone.WAIT -> R.drawable.widget_dot_amber
        Tone.OFF -> R.drawable.widget_dot_faint
    }

    internal fun clock(context: Context, at: Long): String = DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME)

    internal fun batteryText(context: Context, f: ComputerFacts): String? = f.batteryPercent?.let {
        context.getString(if (f.charging) R.string.widget_pc_battery_charging else R.string.widget_pc_battery, it)
    }

    /** Shows [view] as asked or off: the look of "not now", with the reason in the spoken description. */
    internal fun setEnabledLook(views: RemoteViews, @IdRes view: Int, enabled: Boolean) {
        views.setFloat(view, "setAlpha", if (enabled) 1f else 0.38f)
    }
}

/** A widget of the family: one layout, or one per size band, rebuilt from the live facts each time. */
abstract class FamilyWidget : AppWidgetProvider() {
    /** The widths and heights (dp) it has a layout for, smallest first. */
    protected abstract val sizes: List<SizeF>

    abstract fun render(context: Context, facts: ComputerFacts?, size: SizeF): RemoteViews

    /** Reads the keystore or the disk: drawn off the main thread. */
    protected open val background = false

    /** A widget that draws off the main thread keeps the broadcast open until it has: a cold process is not killed mid-draw. */
    override fun onReceive(context: Context, intent: Intent) {
        if (!background) return super.onReceive(context, intent)
        val pending = goAsync()
        DeskWidgets.background { try { super.onReceive(context, intent) } finally { pending.finish() } }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context, manager, ids)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refresh(context, manager, intArrayOf(id))

    private fun refresh(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val facts = runCatching { WidgetFacts.current(context) }.getOrNull()
        update(context, manager, ids, facts)
    }

    fun update(context: Context, manager: AppWidgetManager, ids: IntArray, facts: ComputerFacts?) {
        val app = context.applicationContext
        val job = {
            ids.forEach { id ->
                val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    RemoteViews(sizes.associateWith { render(app, facts, it) })
                } else {
                    val options = manager.getAppWidgetOptions(id)
                    val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                    val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                    render(app, facts, sizes.lastOrNull { it.width <= w && it.height <= h } ?: sizes.first())
                }
                runCatching { manager.updateAppWidget(id, views) }
            }
        }
        if (background && !DeskWidgets.onWorker()) DeskWidgets.background(job) else job()
    }

    protected fun views(context: Context, @LayoutRes layout: Int) = RemoteViews(context.packageName, layout)

    protected fun RemoteViews.show(@IdRes id: Int, visible: Boolean) = setViewVisibility(id, if (visible) View.VISIBLE else View.GONE)
}
