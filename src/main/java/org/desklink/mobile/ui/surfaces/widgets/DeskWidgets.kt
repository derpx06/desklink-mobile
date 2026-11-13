/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.surfaces.widgets

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.core.net.toUri
import org.desklink.mobile.R
import org.desklink.mobile.ui.MainActivity
import org.desklink.mobile.ui.surfaces.DeskSurfaces
import org.desklink.mobile.ui.surfaces.DeskWidgetActionReceiver
import java.util.concurrent.Executors

/**
 * The home-screen widget family, built from the same facts as the Home tiles ([WidgetFacts] and [WidgetRules]).
 * Nothing polls: [DeskSurfaces.refresh] redraws on a change, the system redraws on `updatePeriodMillis`
 * (30 minutes, which also corrects a picture left behind by a killed process), and each draw reads the live state.
 */
object DeskWidgets {
    private val family: List<FamilyWidget> = listOf(ComputerStatusWidget(), QuickActionsWidget(), NotesWidget(), TransfersWidget(), TerminalsWidget())

    /** The phone's screen went off and no one has unlocked it since: notes stay hidden even before the keyguard says so. */
    @Volatile
    internal var screenOff = false

    /** The facts of the last good read: a transient failure keeps the picture (or the last facts), it never says "No computer paired". */
    @Volatile
    private var lastFacts: Result? = null

    /** The computer's facts now, or the last good ones; null (skip this redraw) when there are none and the read failed. */
    internal fun facts(context: Context): Result? {
        runCatching { WidgetFacts.current(context) }.onSuccess { return Result(it).also { r -> lastFacts = r } }
        return lastFacts
    }

    /** [facts] is null when no computer is paired. */
    internal class Result(val facts: ComputerFacts?)

    /** Redraws every placed widget of the family. Cheap when there are none. */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val placed = family.map { it to manager.getAppWidgetIds(ComponentName(context, it.javaClass)) }.filter { it.second.isNotEmpty() }
        if (placed.isEmpty()) return
        val facts = facts(context) ?: return
        placed.forEach { (widget, ids) -> widget.update(context, manager, ids, facts.facts) }
    }

    /** Hides the notes at once when the screen goes off and shows them again when the phone is unlocked. */
    fun watchLock(app: Context) {
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                screenOff = intent.action == Intent.ACTION_SCREEN_OFF
                runCatching { NotesWidget.redraw(context) }
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(receiver, filter)
        }
    }

    private const val WORKER = "desklink-widgets"
    private val worker = Executors.newSingleThreadExecutor { Thread(it, WORKER).apply { isDaemon = true } }

    internal fun onWorker() = Thread.currentThread().name == WORKER

    internal fun background(job: () -> Unit) = worker.execute { runCatching(job) }

    internal fun locked(context: Context): Boolean =
        screenOff || context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

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

    /**
     * The look of "not now": the icon at 38 % and its label at 70 % (still 6:1 or better, so it can be read), with the reason in the
     * spoken name. The button stays and still answers a tap, with the reason. `View.setAlpha` can be called through RemoteViews
     * only from Android 12; before it that call broke the whole widget ("Can't load widget"), so there the icon's own image
     * is dimmed instead (`ImageView.setImageAlpha`) and the label keeps its colour.
     */
    internal fun setEnabledLook(views: RemoteViews, @IdRes icon: Int, @IdRes label: Int?, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setFloat(icon, "setAlpha", if (enabled) 1f else 0.38f)
            if (label != null) views.setFloat(label, "setAlpha", if (enabled) 1f else 0.7f)
        } else {
            views.setInt(icon, "setImageAlpha", if (enabled) 255 else 97)
        }
    }

    internal fun fontScale(context: Context): Float = context.resources.configuration.fontScale

    /** "Lock arch-desk", or plain "Lock desk" when there is no computer to name. */
    internal fun named(context: Context, @StringRes withName: Int, @StringRes plain: Int, name: String?): String =
        if (name.isNullOrBlank()) context.getString(plain) else context.getString(withName, name)

    /** An action's spoken name: what it does, and when it is off, that it is off and why. */
    internal fun spokenAction(context: Context, label: String, enabled: Boolean, reason: String?): String =
        if (enabled) label else context.getString(R.string.widget_action_off, label, reason.orEmpty()).trim()

    /**
     * The header every computer widget shares (layout `widget_part_header`): the computer's name, its state as a dot and
     * words, and a tap ([tap]; none when the whole widget is the target) that opens the app on it. Not paired: "DeskLink",
     * "No computer paired", and the tap opens the app to pair one.
     */
    internal fun header(context: Context, v: RemoteViews, name: String?, tone: Tone, words: String, tap: PendingIntent?) {
        v.setTextViewText(R.id.w_name, name ?: context.getString(R.string.app_name))
        // At huge text the decorative mark gives its room to the name.
        v.setViewVisibility(R.id.w_mark, if (WidgetRules.roomForExtras(fontScale(context))) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.w_status, words)
        v.setImageViewResource(R.id.w_dot, dot(tone))
        if (tap != null) {
            v.setOnClickPendingIntent(R.id.w_header, tap)
            val hint = if (name == null) context.getString(R.string.widget_not_paired_hint) else null
            v.setContentDescription(R.id.w_header, listOfNotNull(named(context, R.string.widget_cd_open, R.string.widget_cd_open_app, name), words, hint).joinToString(". "))
        }
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
        val facts = DeskWidgets.facts(context) ?: return
        update(context, manager, ids, facts.facts)
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
