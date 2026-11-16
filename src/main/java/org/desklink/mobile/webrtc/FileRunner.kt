/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// Where file work runs: the FileRunner seam (a fake in tests) and its
// one-thread implementation.

/** Where file work runs: one dedicated thread per manager, so nothing on libwebrtc's callback thread touches a file. */
interface FileRunner {
    fun post(task: Runnable)
    fun postDelayed(delayMs: Long, task: Runnable)
    fun every(periodMs: Long, task: Runnable)

    /** Runs [step] every [periodMs] for as long as it returns true, then stops (nothing ticks while idle). */
    fun tickWhile(periodMs: Long, step: () -> Boolean) = Unit
}

class ThreadFileRunner : FileRunner {
    private val executor: ScheduledExecutorService = org.desklink.mobile.helpers.Guarded.Scheduled("DeskLink-file")

    override fun post(task: Runnable) {
        runCatching { executor.execute(task) }
    }

    override fun postDelayed(delayMs: Long, task: Runnable) {
        runCatching { executor.schedule(task, delayMs, TimeUnit.MILLISECONDS) }
    }

    override fun every(periodMs: Long, task: Runnable) {
        runCatching { executor.scheduleWithFixedDelay(task, periodMs, periodMs, TimeUnit.MILLISECONDS) }
    }

    override fun tickWhile(periodMs: Long, step: () -> Boolean) {
        val loop = object : Runnable {
            private var failures = 0
            override fun run() {
                // A step that throws is recorded and tried again (it used to end the ticker silently); after five in a row the
                // ticker stops and the next transfer arms it again.
                val again = try {
                    step().also { failures = 0 }
                } catch (error: Throwable) {
                    if (org.desklink.mobile.helpers.Guarded.fatal(error)) throw error
                    org.desklink.mobile.helpers.Guarded.record("DeskLink-file", error)
                    ++failures < org.desklink.mobile.helpers.Guarded.PeriodicGuard.MAX_CONSECUTIVE_FAILURES
                }
                if (again) runCatching { executor.schedule(this, periodMs, TimeUnit.MILLISECONDS) }
            }
        }
        runCatching { executor.schedule(loop, periodMs, TimeUnit.MILLISECONDS) }
    }

    fun shutdown() = executor.shutdownNow()
}
