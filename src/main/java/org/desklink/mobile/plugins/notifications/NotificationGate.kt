/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

import android.util.Log

/**
 * Notifications wait here until the per-app settings (which apps are on, which have contents or pictures blocked) are in
 * memory: with the settings unknown nothing may be mirrored, because "unknown" must never read as "allowed". A load that
 * fails is tried again with a growing pause, for as long as the plugin lives; whatever arrived meanwhile is handed on
 * once the settings are there.
 */
class NotificationGate<T>(
    private val load: () -> Unit,
    private val runWorker: (() -> Unit) -> Unit,
    private val postLater: (delayMs: Long, task: () -> Unit) -> Unit,
    /** Called once the settings are loaded, with what was held back (oldest first). */
    private val onReady: (List<T>) -> Unit,
    private val capacity: Int = CAPACITY,
) {
    @Volatile var ready = false
        private set

    private var loading = false
    private var retryPending = false
    private var attempt = 0
    private var stopped = false
    private val held = ArrayDeque<T>()

    /** Starts loading unless it is done or under way. */
    @Synchronized
    fun start() {
        if (ready || loading || stopped || retryPending) return
        loading = true
        runWorker {
            try {
                load()
                loaded()
            } catch (error: Throwable) {
                failed(error)
            }
        }
    }

    /** Holds [item] until the settings are loaded (the oldest goes first when full: nothing is mirrored, never the wrong thing). */
    @Synchronized
    fun hold(item: T) {
        if (held.size >= capacity) held.removeFirst()
        held.addLast(item)
        start()
    }

    @Synchronized
    fun stop() {
        stopped = true
        held.clear()
    }

    private fun loaded() {
        val items = synchronized(this) {
            ready = true
            loading = false
            attempt = 0
            held.toList().also { held.clear() }
        }
        onReady(items)
    }

    private fun failed(error: Throwable) {
        // The class only: a message can carry a path or a package name.
        Log.w("DeskLink/Notifications", "per-app settings could not be loaded (${error.javaClass.simpleName}); trying again")
        val delay = synchronized(this) {
            loading = false
            retryPending = true // a new notification does not skip the pause
            minOf(MAX_DELAY_MS, BASE_DELAY_MS shl minOf(attempt++, 10))
        }
        postLater(delay) {
            synchronized(this) { retryPending = false }
            start()
        }
    }

    companion object {
        const val CAPACITY = 100
        const val BASE_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 30_000L
    }
}
