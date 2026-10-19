package org.desklink.mobile.webrtc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeferredTaskExecutorTest {
    @Test
    fun `blocking native work does not hold the caller session lock`() {
        val workStarted = CountDownLatch(1)
        val releaseWork = CountDownLatch(1)
        val workFinished = CountDownLatch(1)
        val executor = DeferredTaskExecutor("test-deferred-work")
        val sessionLock = Any()

        try {
            synchronized(sessionLock) {
                executor.execute {
                    workStarted.countDown()
                    releaseWork.await()
                    workFinished.countDown()
                }
            }

            assertTrue("work should have started", workStarted.await(1, TimeUnit.SECONDS))
            assertTrue(
                "session state remains accessible while deferred work is blocked",
                synchronized(sessionLock) { true },
            )
        } finally {
            releaseWork.countDown()
            executor.close()
        }

        assertTrue("deferred work should eventually finish", workFinished.await(1, TimeUnit.SECONDS))
    }
}
