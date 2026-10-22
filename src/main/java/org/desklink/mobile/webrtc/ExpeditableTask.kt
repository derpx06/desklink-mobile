/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One delayed task that can be brought forward. The recovery ladder schedules its next attempt up
 * to 16 s away; when a reset event arrives (the phone woke, the network changed) that long wait is
 * pointless, so [expedite] pulls it in to a short delay instead of letting it run out.
 */
class ExpeditableTask(
    private val executor: ScheduledExecutorService,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var future: ScheduledFuture<*>? = null
    private var task: Runnable? = null
    private var dueAt = 0L

    @Synchronized
    fun schedule(task: Runnable, delayMs: Long) {
        future?.cancel(false)
        this.task = task
        dueAt = clock() + delayMs
        val pending = arrayOfNulls<ScheduledFuture<*>>(1)
        future = executor.schedule({
            synchronized(this) { if (future === pending[0]) future = null }
            task.run()
        }, delayMs, TimeUnit.MILLISECONDS).also { pending[0] = it }
    }

    /** If the task would wait longer than [maxDelayMs], runs it after [maxDelayMs] instead. True if it was brought forward. */
    @Synchronized
    fun expedite(maxDelayMs: Long): Boolean {
        val current = future ?: return false
        val pendingTask = task ?: return false
        if (current.isDone || dueAt - clock() <= maxDelayMs) return false
        schedule(pendingTask, maxDelayMs)
        return true
    }
}
