/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExpeditableTaskTest {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val task = ExpeditableTask(executor)

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun aSixteenSecondWaitIsBroughtForwardAndRunsOnce() {
        val ran = AtomicInteger()
        val done = CountDownLatch(1)
        task.schedule({ ran.incrementAndGet(); done.countDown() }, 16_000)
        assertTrue(task.expedite(50))
        assertTrue("the woken phone waited out the rung", done.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(1, ran.get())
    }

    @Test
    fun aTaskAlreadyDueSoonerIsLeftAlone() {
        task.schedule({ }, 100)
        assertFalse(task.expedite(500))
    }

    @Test
    fun withNothingScheduledOrAfterItRanThereIsNothingToBringForward() {
        assertFalse(task.expedite(10))
        val done = CountDownLatch(1)
        task.schedule({ done.countDown() }, 10)
        assertTrue(done.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertFalse(task.expedite(10))
    }
}
