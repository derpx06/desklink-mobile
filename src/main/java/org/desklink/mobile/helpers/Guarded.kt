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
        val failed = _stopped.value
        _stopped.value = emptySet()
        // The failed periodic owners first (a peer whose liveness task died is rebuilt), then the plugins.
        failed.forEach { name -> owners[name]?.let { owner -> runCatching { owner() } } }
        runCatching { restart() }
    }

    fun clearStopped() { _stopped.value = emptySet() }

    /** The clock the report rate limit uses (a test replaces it). */
    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    private val lastReportAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** At most one kept report per feature per minute: a task failing every second must not fill the report slots. */
    const val REPORT_EVERY_MS = 60_000L

    /** Who restarts a failed periodic owner ("Tap to restart" and the give-up path): by the name of its executor. */
    val owners = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    /** Records [error] for [name] and lists the feature as stopped. Never throws. */
    fun record(name: String, error: Throwable) {
        runCatching { Log.e(TAG, "$name failed: ${error.javaClass.name}: ${LogExport.redact(error.message.orEmpty())}") }
        _stopped.value = _stopped.value + name
        val now = clock()
        val last = lastReportAt[name]
        if (last != null && now - last in 0 until REPORT_EVERY_MS) return
        lastReportAt[name] = now
        runCatching { sink(name, error) }
    }

    /**
     * True for what must not be swallowed: [OutOfMemoryError] (nothing safe can be done after it; [onOutOfMemory] hands it on) and
     * cancellation. (Rethrowing from inside an UncaughtExceptionHandler does nothing: the VM ignores it.)
     */
    fun fatal(error: Throwable): Boolean = error is OutOfMemoryError || error is CancellationException

    private val oomMarker = "DeskLink: out of memory in an owned thread\n".toByteArray()
    @Volatile
    var oomFile: java.io.File? = null

    /** Out of memory on a thread we own: a minimal marker (pre-allocated bytes, no new objects of size), then the previous handler. */
    fun onOutOfMemory(thread: Thread, error: OutOfMemoryError) {
        try {
            oomFile?.let { java.io.FileOutputStream(it, true).use { out -> out.write(oomMarker) } }
        } catch (_: Throwable) {
        }
        Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, error)
    }

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
            setUncaughtExceptionHandler { thread, error ->
                if (error is OutOfMemoryError) onOutOfMemory(thread, error) else if (!fatal(error)) record(name, error)
            }
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

    /**
     * A periodic task that throws is caught per run, recorded, and runs again: backing off (skipping 1, 3, 7, 15 runs) while it keeps
     * failing, and after [MAX_CONSECUTIVE_FAILURES] in a row the owner is restarted (the peer rebuilt, the ticker re-armed). A task
     * that threw used to be cancelled silently by the executor and never ran again.
     */
    class PeriodicGuard(private val name: String, private val task: Runnable, private val giveUp: () -> Unit) : Runnable {
        private var failures = 0
        private var skip = 0
        override fun run() {
            if (skip > 0) { skip--; return }
            try {
                task.run()
                failures = 0
            } catch (error: Throwable) {
                if (fatal(error)) throw error
                failures++
                record(name, error)
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    failures = 0; skip = 0
                    runCatching(giveUp)
                } else {
                    skip = ((1 shl (failures - 1)) - 1).coerceAtMost(15)
                }
            }
        }

        companion object { const val MAX_CONSECUTIVE_FAILURES = 5 }
    }

    class Scheduled(private val name: String) : ScheduledThreadPoolExecutor(1, threadFactory(name)) {
        /** Restarts whatever owns this executor's periodic tasks; also what "Tap to restart" runs. */
        var owner: (() -> Unit)? = null
            set(value) {
                field = value
                if (value != null) owners[name] = value else owners.remove(name)
            }

        override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit) =
            super.scheduleAtFixedRate(PeriodicGuard(name, command) { owner?.invoke() }, initialDelay, period, unit)

        override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit) =
            super.scheduleWithFixedDelay(PeriodicGuard(name, command) { owner?.invoke() }, initialDelay, delay, unit)

        override fun shutdown() {
            owners.remove(name)
            super.shutdown()
        }

        override fun shutdownNow(): MutableList<Runnable> {
            owners.remove(name)
            return super.shutdownNow()
        }

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
