/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedPendingQueueTest {
    @Test
    fun `queue preserves order and evicts the oldest item at its limit`() {
        val queue = BoundedPendingQueue<String>(2)

        assertEquals(null, queue.offer("one"))
        assertEquals(null, queue.offer("two"))
        assertEquals("one", queue.offer("three"))
        assertEquals(listOf("two", "three"), queue.drain())
        assertEquals(emptyList<String>(), queue.drain())
    }

    @Test
    fun `expired items are removed in order and the rest stay queued`() {
        val queue = BoundedPendingQueue<Int>(8)
        listOf(1, 2, 3, 4).forEach { queue.offer(it) }
        assertEquals(listOf(1, 3), queue.removeIf { it % 2 == 1 })
        assertEquals(listOf(2, 4), queue.drain())
    }

    @Test
    fun aFullQueueEvictsADisposableItemBeforeAnythingThePersonAskedFor() {
        val queue = BoundedPendingQueue<String>(3)
        queue.offer("ring")        // asked for
        queue.offer("snapshot-1")  // disposable
        queue.offer("lock")        // asked for
        assertEquals("snapshot-1", queue.offerPreferring("snapshot-2") { it.startsWith("snapshot") })
        assertEquals(listOf("ring", "lock", "snapshot-2"), queue.drain())
    }

    @Test
    fun withNothingDisposableTheOldestGoesAndIsReturnedToBeReported() {
        val queue = BoundedPendingQueue<String>(2)
        queue.offer("ring"); queue.offer("lock")
        assertEquals("ring", queue.offerPreferring("unlock") { false })
        assertEquals(listOf("lock", "unlock"), queue.drain())
    }
}
