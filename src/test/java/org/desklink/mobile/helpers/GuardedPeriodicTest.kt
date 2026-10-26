/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import org.desklink.mobile.webrtc.ThreadFileRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A periodic task that throws is recorded and runs again; it is not cancelled silently, and the owner is restarted after five in a row. */
class GuardedPeriodicTest {
    private val recorded = CopyOnWriteArrayList<String>()
    private var oldSink = Guarded.sink

    @Before
    fun setUp() {
        oldSink = Guarded.sink
        Guarded.sink = { name, _ -> recorded += name }
        Guarded.clock = { System.nanoTime() / 1_000_000 + 10_000_000 * recorded.size } // every record counts as a new minute
    }

    @After
    fun tearDown() { Guarded.sink = oldSink; Guarded.clock = System::currentTimeMillis; Guarded.clearStopped() }

    @Test
    fun aThrowingFixedDelayTaskKeepsRunningAndRestartsItsOwnerAfterFiveInARow() {
        val scheduled = Guarded.Scheduled("t-periodic")
        val runs = AtomicInteger()
        val restarted = CountDownLatch(1)
        scheduled.owner = { restarted.countDown() }
        scheduled.scheduleWithFixedDelay({ runs.incrementAndGet(); throw IllegalStateException("tick") }, 1, 1, TimeUnit.MILLISECONDS)
        assertTrue("the owner is restarted", restarted.await(10, TimeUnit.SECONDS))
        assertTrue("it ran again and again, not once: ${runs.get()}", runs.get() >= Guarded.PeriodicGuard.MAX_CONSECUTIVE_FAILURES)
        assertTrue(recorded.contains("t-periodic"))
        val after = runs.get()
        Thread.sleep(100)
        assertTrue("and still runs after the restart", runs.get() > after)
        scheduled.shutdownNow()
    }

    @Test
    fun aTaskThatRecoversStartsCountingFromZeroAndTheFixedRateOneContinuesToo() {
        val scheduled = Guarded.Scheduled("t-rate")
        val runs = AtomicInteger()
        val done = CountDownLatch(1)
        scheduled.scheduleAtFixedRate({
            val n = runs.incrementAndGet()
            if (n == 1) throw IllegalStateException("once")
            if (n >= 4) done.countDown()
        }, 1, 1, TimeUnit.MILLISECONDS)
        assertTrue("ran past the failure", done.await(10, TimeUnit.SECONDS))
        scheduled.shutdownNow()
    }

    @Test
    fun theFileTickerKeepsTickingThroughAThrowingStepThenStopsAfterFive() {
        val runner = ThreadFileRunner()
        val calls = AtomicInteger()
        runner.tickWhile(1) { calls.incrementAndGet(); throw IllegalStateException("step") }
        Thread.sleep(500)
        assertEquals("five tries, then it stops and the next transfer arms it again", Guarded.PeriodicGuard.MAX_CONSECUTIVE_FAILURES, calls.get())
        runner.shutdown()
    }

    @Test
    fun reportsAreKeptAtMostOncePerMinutePerFeature() {
        var now = 1_000_000L
        val oldClock = Guarded.clock
        Guarded.clock = { now }
        recorded.clear()
        repeat(5) { Guarded.record("t-limit", IllegalStateException("x")) }
        now += Guarded.REPORT_EVERY_MS + 1
        Guarded.record("t-limit", IllegalStateException("x"))
        Guarded.clock = oldClock
        assertEquals(2, recorded.count { it == "t-limit" })
    }

    @Test
    fun theFailedOwnerIsRestartedByTapToRestart() {
        val restarted = AtomicInteger()
        Guarded.owners["t-owner"] = { restarted.incrementAndGet() }
        Guarded.record("t-owner", IllegalStateException("x"))
        Guarded.restartStopped()
        assertEquals(1, restarted.get())
        Guarded.owners.remove("t-owner")
    }

    @Test
    fun outOfMemoryIsHandedToThePreviousHandlerAfterAMarker() {
        val file = File.createTempFile("oom", ".txt")
        Guarded.oomFile = file
        val seen = CopyOnWriteArrayList<Throwable>()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> seen += error }
        try {
            Guarded.onOutOfMemory(Thread.currentThread(), OutOfMemoryError())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(old)
            Guarded.oomFile = null
        }
        assertTrue(file.length() > 0)
        assertTrue(seen.single() is OutOfMemoryError)
        file.delete()
    }
}
