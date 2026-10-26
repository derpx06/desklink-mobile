/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.content.Context
import androidx.core.content.edit

/**
 * A sticky foreground service that crashes at start is restarted by Android for ever. Real crashes (the global handler's, not the
 * guarded ones) are counted; three within two minutes put the app in safe mode: the service does not start its features by
 * itself, and the report is offered. Opening the app clears it (the person's own start).
 */
object CrashLoop {
    private const val PREFS = "crash_loop"
    private const val KEY = "times"
    const val WINDOW_MS = 2 * 60_000L
    const val LIMIT = 3

    /** Pure: whether [times] (crash timestamps) contain [LIMIT] within [WINDOW_MS] of [now]. */
    fun looping(times: List<Long>, now: Long): Boolean = times.count { now - it in 0..WINDOW_MS } >= LIMIT

    private fun read(context: Context): List<Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    /** Called from the global handler: must be cheap and never throw. */
    fun recordCrash(context: Context, now: Long = System.currentTimeMillis()) = runCatching {
        val recent = (read(context) + now).filter { now - it in 0..WINDOW_MS }.takeLast(LIMIT * 2)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { putString(KEY, recent.joinToString(",")) }
    }

    fun safeMode(context: Context, now: Long = System.currentTimeMillis()): Boolean = runCatching { looping(read(context), now) }.getOrDefault(false)

    /** The person opened the app: a fresh start. */
    fun clear(context: Context) = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY) }
    }
}
