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

    /** The crash times, in memory: the file is read once, in the background ([preload]), so the main thread never touches it. */
    @Volatile
    private var cached: List<Long>? = null
    private val loaded = java.util.concurrent.CountDownLatch(1)
    private val disk = Any()
    const val LOAD_WAIT_MS = 1_500L

    @androidx.annotation.VisibleForTesting
    internal fun resetForTest() { synchronized(disk) { cached = null } }

    /** Application start: reads the file off the main thread. A time recorded or cleared meanwhile is newer and is kept. */
    fun preload(context: Context) {
        val app = context.applicationContext
        ThreadHelper.execute {
            runCatching { read(app) }.onSuccess { stored -> synchronized(disk) { if (cached == null) cached = stored } }
            loaded.countDown()
        }
    }

    /** From memory; if the background read has not answered yet it is waited for briefly, and failing that read here (never a guess that weakens the breaker). */
    private fun times(context: Context): List<Long> {
        cached?.let { return it }
        runCatching { loaded.await(LOAD_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
        cached?.let { return it }
        val stored = read(context)
        return synchronized(disk) { if (cached == null) cached = stored; cached!! }
    }

    /** Called from the global handler: must be cheap and never throw. */
    fun recordCrash(context: Context, now: Long = System.currentTimeMillis()) = runCatching {
        synchronized(disk) {
            val recent = ((cached ?: read(context)) + now).filter { now - it in 0..WINDOW_MS }.takeLast(LIMIT * 2)
            cached = recent
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { putString(KEY, recent.joinToString(",")) }
        }
    }

    fun safeMode(context: Context, now: Long = System.currentTimeMillis()): Boolean = runCatching { looping(times(context), now) }.getOrDefault(false)

    /** The person opened the app: a fresh start. Memory at once; the file in the background, unless a crash was recorded since. */
    fun clear(context: Context) = runCatching {
        val app = context.applicationContext
        synchronized(disk) { cached = emptyList() }
        ThreadHelper.execute {
            runCatching {
                synchronized(disk) {
                    if (cached.isNullOrEmpty()) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { remove(KEY) }
                }
            }
        }
    }
}
