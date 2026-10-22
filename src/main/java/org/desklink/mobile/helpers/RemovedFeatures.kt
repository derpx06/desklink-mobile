/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.io.File

/**
 * DeskLink 1.3 removed automations, run commands, the drawing pad, presenter, TV remote, the remote
 * keyboard and send-keystrokes. What they stored is deleted, once, quietly: nothing reads it any
 * more and some of it names commands the person typed. Pairing records, certificates and every
 * other preference are never touched; only the names listed here.
 */
object RemovedFeatures {
    /** A dedicated file, so the marker never mixes with a preference the app reads. */
    internal const val MARKER_PREFS = "desklink_removed_features"
    internal const val MARKER_KEY = "cleaned_1_3"

    /** The automation rules, with the extra files `AtomicFile` leaves beside them. */
    internal val FILES = listOf("automations.json", "automations.json.new", "automations.json.bak", "automations.json.unreadable")

    /** Whole preference files: quick commands, and the Run Command widget's device choices. */
    internal val PREFERENCE_FILES = listOf("desklink_quick_commands", "org.desklink.mobile.WidgetProvider")

    /** Keys in the default preferences: the pad, TV remote, presenter, keyboard and send-keystrokes settings. */
    internal val KEYS = setOf(
        "digitizer_draw_button_side", "digitizer_hide_draw_button",
        "bigscreen_hide_mouse_input", "bigscreen_show_back", "bigscreen_show_home",
        "pref_presenter_enable_volume_keys", "remotekeyboard_editing_only",
        "runcommand_category_device_controls", "set_runcommand_name_as_title",
        "pref_sendkeystrokes_enabled", "pref_send_safe_text_immediately", "compose_send_input_cache",
    )

    /** Per-desk command lists saved by the Run Command screen: `commands_preference_<device id>`. */
    internal const val COMMANDS_PREFIX = "commands_preference_"

    /** The class the automation alarm was delivered to, and its action. */
    private const val ALARM_RECEIVER = "org.desklink.mobile.plugins.automations.AutomationAlarms\$Receiver"
    private const val ALARM_ACTION = "org.desklink.mobile.plugins.automations.TIME"

    /** The preference keys among [all] that a removed feature wrote. */
    fun staleKeys(all: Collection<String>): List<String> = all.filter { it in KEYS || it.startsWith(COMMANDS_PREFIX) }

    /** Deletes the removed features' files under [filesDir]; the names deleted. Never throws. */
    fun deleteFiles(filesDir: File): List<String> = FILES.filter { name ->
        val file = File(filesDir, name)
        file.exists() && runCatching { file.delete() }.getOrDefault(false)
    }

    /** Runs the cleanup the first time this version starts. Safe to call on every start. */
    fun runOnce(context: Context) {
        val app = context.applicationContext
        val marker = app.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE)
        if (marker.getBoolean(MARKER_KEY, false)) return
        runCatching { deleteFiles(app.filesDir) }
        runCatching { cancelStaleAlarm(app) }
        runCatching {
            PREFERENCE_FILES.forEach { name ->
                app.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) { clear() }
                app.deleteSharedPreferences(name)
            }
        }
        runCatching {
            val prefs = PreferenceManager.getDefaultSharedPreferences(app)
            val stale = staleKeys(prefs.all.keys)
            if (stale.isNotEmpty()) prefs.edit { stale.forEach(::remove) }
        }
        marker.edit { putBoolean(MARKER_KEY, true) }
    }

    /**
     * The automations' time rule left one inexact alarm behind. Its receiver is gone, so the
     * alarm could only ever be delivered to nothing: cancel it, built by name because the class
     * no longer exists. `FLAG_NO_CREATE` returns null when none is set.
     */
    private fun cancelStaleAlarm(app: Context) {
        val intent = Intent().setClassName(app.packageName, ALARM_RECEIVER).setAction(ALARM_ACTION)
        val pending = PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        app.getSystemService(AlarmManager::class.java)?.cancel(pending)
        pending.cancel()
    }
}
