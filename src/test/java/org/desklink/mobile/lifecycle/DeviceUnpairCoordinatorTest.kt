/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DeviceUnpairCoordinatorTest {
    @Test
    fun duplicateGenerationSchedulesOnlyOneCleanup() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = DeviceUnpairCoordinator(scope)
        val calls = AtomicInteger()
        val done = CountDownLatch(1)

        assertTrue(coordinator.beginUnpair(7) {
            calls.incrementAndGet()
            done.countDown()
        })
        assertFalse(coordinator.beginUnpair(7) { calls.incrementAndGet() })

        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(1, calls.get())
        coordinator.close()
    }

    @Test
    fun aLaterPairingGenerationCanScheduleCleanup() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = DeviceUnpairCoordinator(scope)
        val calls = AtomicInteger()
        val done = CountDownLatch(2)

        assertTrue(coordinator.beginUnpair(1) {
            calls.incrementAndGet()
            done.countDown()
        })
        assertTrue(coordinator.beginUnpair(2) {
            calls.incrementAndGet()
            done.countDown()
        })

        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(2, calls.get())
        coordinator.close()
    }

    @Test
    fun failuresDoNotStopTheSerializedQueue() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = DeviceUnpairCoordinator(scope)
        val completed = CountDownLatch(1)
        val calls = AtomicInteger()

        coordinator.beginUnpair(1) {
            calls.incrementAndGet()
            error("simulated plugin failure")
        }
        coordinator.enqueue("following cleanup") {
            calls.incrementAndGet()
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(2, calls.get())
        coordinator.close()
    }

    @Test
    fun cleanupRunsOffTheCallingThread() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = DeviceUnpairCoordinator(scope)
        val caller = Thread.currentThread()
        val worker = AtomicReference<Thread>()
        val done = CountDownLatch(1)

        coordinator.beginUnpair(1) {
            worker.set(Thread.currentThread())
            done.countDown()
        }

        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertNotEquals(caller, worker.get())
        coordinator.close()
    }
}
