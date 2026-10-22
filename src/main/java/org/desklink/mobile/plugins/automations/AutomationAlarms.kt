/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.automations

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * F7 `time {at, days?}` for phone-run rules: one inexact alarm at the next due minute (no polling,
 * no exact-alarm permission), rescheduled whenever the rules change and after each firing.
 */
object AutomationAlarms {
    /** Android may deliver an inexact alarm this late; a time rule runs within this window. */
    const val WINDOW_MS = 60_000L

    private const val ACTION = "org.desklink.mobile.plugins.automations.TIME"
    private const val EXTRA_AT = "at"

    /** The next occurrence after [now] of "HH:MM" on [days] ("1234567", Monday = 1; null = every day). */
    fun next(at: String, days: String?, now: Long): Long? {
        val minute = Rules.minutes(at) ?: return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            if (cal.timeInMillis > now && (days.isNullOrEmpty() || Rules.dayOfWeek(cal.timeInMillis).toString() in days)) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    /** The `time` event for the minute at [at]. */
    fun timeEvent(at: Long): Event {
        val minute = Rules.minuteOfDay(at)
        return Event(Rules.T_TIME, mapOf("at" to "%02d:%02d".format(minute / 60, minute % 60), "days" to "${Rules.dayOfWeek(at)}"))
    }

    fun reschedule(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val due = Automations.timeRules(app).mapNotNull { rule -> rule.trigger["at"]?.let { next(it, rule.trigger["days"], now) } }.minOrNull()
        // The minute it stands for travels with it, so a late delivery still runs that minute's rules.
        val fire = Intent(app, Receiver::class.java).setAction(ACTION).putExtra(EXTRA_AT, due ?: 0L)
        val intent = PendingIntent.getBroadcast(app, 0, fire, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (due == null) {
            manager.cancel(intent)
        } else {
            manager.setWindow(AlarmManager.RTC_WAKEUP, due, WINDOW_MS, intent)
        }
    }

    /** Fires the minute that is due, then schedules the next. Not exported: only our alarm reaches it. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            val scheduled = intent.getLongExtra(EXTRA_AT, 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
            Automations.fire(context, timeEvent(scheduled))
            reschedule(context)
        }
    }
}
