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
}
