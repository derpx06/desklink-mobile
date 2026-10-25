/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * One feature failing must not take the app down. Every executor, pool and coroutine scope the app owns is built here (or wraps its
 * work with [guard]): a task that throws is caught at the task boundary, logged (class and a redacted message), kept as a crash
 * report (bounded, the newest offered in the app), the feature it belongs to is listed in [stopped] ("stopped, tap to restart"),
 * and the thread and the next task carry on. [OutOfMemoryError] and cancellation are not swallowed. Threads and scopes the app
 * does not own (the main thread, libraries) still reach the global handler, which records and then crashes as usual.
 */
object Guarded {
    private const val TAG = "DeskLink/Guarded"

    /** Where a failure is kept; replaced by the application (and by tests). */
    @Volatile
    var sink: (name: String, error: Throwable) -> Unit = { name, error -> CrashReporter.recordGuarded(name, error) }

    /** Called by "tap to restart" after the list is cleared: reload what stopped. Set by the application. */
    @Volatile
    var restart: () -> Unit = {}

    private val _stopped = MutableStateFlow<Set<String>>(emptySet())
    /** Names of features that threw and are listed as stopped. */
    val stopped: StateFlow<Set<String>> = _stopped

    fun restartStopped() {
        _stopped.value = emptySet()
        runCatching { restart() }
    }

    fun clearStopped() { _stopped.value = emptySet() }

    /** Records [error] for [name] and lists the feature as stopped. Never throws. */
    fun record(name: String, error: Throwable) {
        runCatching { Log.e(TAG, "$name failed: ${error.javaClass.name}: ${LogExport.redact(error.message.orEmpty())}") }
        _stopped.value = _stopped.value + name
        runCatching { sink(name, error) }
    }

    /** True for what must not be swallowed. */
    fun fatal(error: Throwable): Boolean = error is OutOfMemoryError || error is CancellationException

    fun wrap(name: String, task: Runnable): Runnable = Runnable {
        try {
            task.run()
        } catch (error: Throwable) {
            if (fatal(error)) throw error
            record(name, error)
        }
    }

    inline fun <T> guard(name: String, block: () -> T): T? = try {
        block()
    } catch (error: Throwable) {
        if (fatal(error)) throw error
        record(name, error)
        null
    }

    fun handler(name: String) = CoroutineExceptionHandler { _, error -> if (!fatal(error)) record(name, error) }

    /** A thread factory whose threads have their own handler: whatever still escapes is recorded and does not reach the global one. */
    fun threadFactory(name: String, daemon: Boolean = true): ThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, name).apply {
            isDaemon = daemon
            setUncaughtExceptionHandler { _, error -> if (!fatal(error)) record(name, error) else throw error }
        }
    }

    /** A pool that guards every task, however it was submitted (`execute`, `submit`, `schedule`). */
    open class Pool(core: Int, max: Int, keepAliveMs: Long, queue: BlockingQueue<Runnable>, private val name: String, policy: RejectedExecutionHandler = AbortPolicy()) :
        ThreadPoolExecutor(core, max, keepAliveMs, TimeUnit.MILLISECONDS, queue, threadFactory(name), policy) {
        // A submitted task is a Future that keeps its own exception (read in afterExecute); a bare Runnable is wrapped.
        override fun execute(command: Runnable) = super.execute(if (command is Future<*>) command else wrap(name, command))
        override fun afterExecute(runnable: Runnable?, throwable: Throwable?) {
            super.afterExecute(runnable, throwable)
            reportFuture(name, runnable)
        }
    }

    class Scheduled(private val name: String) : ScheduledThreadPoolExecutor(1, threadFactory(name)) {
        override fun afterExecute(runnable: Runnable?, throwable: Throwable?) {
            super.afterExecute(runnable, throwable)
            reportFuture(name, runnable)
        }
    }

    // A FutureTask keeps a task's exception inside the future: look in it once the task has run.
    private fun reportFuture(name: String, runnable: Runnable?) {
        val future = runnable as? Future<*> ?: return
        if (!future.isDone || future.isCancelled) return
        try {
            future.get()
        } catch (e: ExecutionException) {
            e.cause?.let { if (!fatal(it)) record(name, it) }
        } catch (_: Exception) {
        }
    }

    fun single(name: String): java.util.concurrent.ExecutorService = Pool(1, 1, 0, LinkedBlockingQueue(), name)

    /** The shared cached pool: unbounded threads, each idle one kept 60 s. */
    fun cached(name: String): Pool = Pool(0, Int.MAX_VALUE, 60_000, java.util.concurrent.SynchronousQueue<Runnable>() as BlockingQueue<Runnable>, name)
}
