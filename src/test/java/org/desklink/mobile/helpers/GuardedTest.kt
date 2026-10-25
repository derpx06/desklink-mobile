/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GuardedTest {
    private val recorded = CopyOnWriteArrayList<Pair<String, Throwable>>()
    private var oldSink = Guarded.sink

    @Before
    fun setUp() {
        oldSink = Guarded.sink
        Guarded.sink = { name, error -> recorded += name to error }
        Guarded.clearStopped()
    }

    @After
    fun tearDown() { Guarded.sink = oldSink; Guarded.clearStopped() }

    private fun nextTaskRuns(submit: (Runnable) -> Unit) {
        submit(Runnable { throw IllegalStateException("boom") })
        val ran = CountDownLatch(1)
        submit(Runnable { ran.countDown() })
        assertTrue("the next task ran after a throwing one", ran.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun aThrowingTaskOnEachOwnedPoolDoesNotKillTheNextOneAndIsRecorded() {
        val single = Guarded.single("t-single")
        val scheduled = Guarded.Scheduled("t-scheduled")
        val cached = Guarded.cached("t-cached")
        val pool = Guarded.Pool(1, 2, 1000, java.util.concurrent.LinkedBlockingQueue(4), "t-pool")
        nextTaskRuns { single.execute(it) }
        nextTaskRuns { scheduled.execute(it) }
        nextTaskRuns { cached.execute(it) }
        nextTaskRuns { pool.execute(it) }
        // submit() and schedule() keep the exception in the future: it is found and recorded too.
        nextTaskRuns { single.submit(it) }
        nextTaskRuns { scheduled.schedule(it, 1, TimeUnit.MILLISECONDS) }
        listOf(single, scheduled, cached, pool).forEach { it.shutdown() }
        val names = recorded.map { it.first }.toSet()
        assertEquals(setOf("t-single", "t-scheduled", "t-cached", "t-pool"), names)
        assertTrue(recorded.all { it.second is IllegalStateException })
        assertTrue("the feature is listed as stopped", Guarded.stopped.value.containsAll(names))
    }

    @Test
    fun aThrowingCoroutineInAnOwnedScopeIsRecordedAndTheScopeLives() {
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO + Guarded.handler("t-scope"))
        scope.launch { throw IllegalArgumentException("x") }
        val ran = CountDownLatch(1)
        scope.launch { ran.countDown() }
        assertTrue(ran.await(10, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 5000
        while (recorded.none { it.first == "t-scope" } && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(recorded.any { it.first == "t-scope" && it.second is IllegalArgumentException })
    }

    @Test
    fun outOfMemoryAndCancellationAreNotSwallowed() {
        assertTrue(Guarded.fatal(OutOfMemoryError()))
        assertTrue(Guarded.fatal(kotlinx.coroutines.CancellationException("c")))
        assertFalse(Guarded.fatal(RuntimeException()))
        var thrown: Throwable? = null
        try { Guarded.wrap("w", Runnable { throw OutOfMemoryError() }).run() } catch (e: Throwable) { thrown = e }
        assertTrue(thrown is OutOfMemoryError)
    }

    @Test
    fun anOwnedThreadsEscapedErrorIsRecordedNotCrashing() {
        val done = CountDownLatch(1)
        val t = Guarded.threadFactory("t-thread").newThread { throw IllegalStateException("escaped") }
        t.start(); t.join(5000)
        assertTrue(recorded.any { it.first == "t-thread" })
        done.countDown()
    }

    @Test
    fun theKeptReportIsRedactedAndBounded() {
        val dir = java.nio.file.Files.createTempDirectory("g").toFile()
        repeat(8) { CrashReporter.keep(dir, LogExport.redact("failed at 192.168.1.20 #$it"), now = 100L + it) }
        val kept = CrashReporter.reports(dir)
        assertEquals(CrashReporter.MAX_REPORTS, kept.size)
        assertFalse(kept.first().readText().contains("192.168.1.20"))
        dir.deleteRecursively()
    }

    @Test
    fun aPoolOfFourCoreThreadsReallyRunsFourAtOnce() {
        val pool = Guarded.Pool(4, 4, 1000, java.util.concurrent.LinkedBlockingQueue(16), "t-four")
        pool.allowCoreThreadTimeOut(true)
        val started = CountDownLatch(4)
        val release = CountDownLatch(1)
        repeat(4) { pool.execute { started.countDown(); release.await(10, TimeUnit.SECONDS) } }
        assertTrue("four tasks run at the same time", started.await(10, TimeUnit.SECONDS))
        release.countDown()
        pool.shutdown()
    }
}
