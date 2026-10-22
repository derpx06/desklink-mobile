/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundQueueTest {
    @Test
    fun interactiveGoesBeforeBulkAndEachLaneKeepsItsOrder() {
        val q = OutboundQueue<String>()
        q.offer("bulk1", true); q.offer("bulk2", true); q.offer("tap1", false); q.offer("tap2", false)
        assertEquals(listOf("tap1", "tap2", "bulk1", "bulk2"), List(4) { q.take() })
    }

    @Test
    fun aFullBulkLaneNeverBlocksTheInteractiveOne() {
        val q = OutboundQueue<Int>()
        repeat(OutboundQueue.BULK_CAPACITY) { assertTrue(q.offer(it, true)) }
        assertFalse("bulk lane is full: refused, so the caller can report it", q.offer(-1, true))
        assertTrue(q.offer(1, false))
        assertEquals(1, q.take())
    }

    @Test
    fun aFullInteractiveLaneRefusesTheNewItemRatherThanDroppingAnOlderOne() {
        val q = OutboundQueue<Int>()
        repeat(OutboundQueue.INTERACTIVE_CAPACITY) { assertTrue(q.offer(it, false)) }
        assertFalse(q.offer(999, false))
        assertEquals(0, q.take())
    }

    @Test
    fun closingReturnsWhatWasWaitingAndStopsTake() {
        val q = OutboundQueue<String>()
        q.offer("a", false); q.offer("b", true)
        assertEquals(listOf("a", "b"), q.close())
        assertNull(q.take())
        assertFalse(q.offer("c", false))
        q.reopen()
        assertTrue(q.offer("c", false))
    }

    @Test
    fun takeWaitsForAnItemFromAnotherThread() {
        val q = OutboundQueue<String>()
        var got: String? = null
        val t = Thread { got = q.take() }.also { it.start() }
        Thread.sleep(50)
        q.offer("x", false)
        t.join(2000)
        assertEquals("x", got)
    }

    @Test
    fun theCapacitiesAreAsDocumented() {
        assertEquals(64, OutboundQueue.INTERACTIVE_CAPACITY)
        assertEquals(256, OutboundQueue.BULK_CAPACITY)
    }

    @Test
    fun anItemOlderThanTheLimitIsReportedNotSentWhenItReachesTheFront() {
        var now = 0L
        val expired = mutableListOf<String>()
        val q = OutboundQueue<String>(clock = { now }, onExpired = { expired += it })
        q.offer("ring", false)
        now = OutboundQueue.MAX_AGE_MS + 1
        q.offer("fresh", false)
        assertEquals("fresh", q.take())
        assertEquals(listOf("ring"), expired)
    }

    @Test
    fun purgeReportsStaleItemsWhileNobodyIsTaking() {
        var now = 0L
        val expired = mutableListOf<String>()
        val q = OutboundQueue<String>(clock = { now }, onExpired = { expired += it })
        q.offer("a", false); q.offer("b", true)
        now = 10_000
        q.offer("c", false)
        now = OutboundQueue.MAX_AGE_MS + 1
        assertEquals(2, q.purgeExpired())
        assertEquals(listOf("a", "b"), expired)
        assertEquals(1, q.size())
    }

    @Test
    fun anItemExactlyAtTheLimitIsStillSent() {
        var now = 0L
        val q = OutboundQueue<String>(clock = { now })
        q.offer("x", false)
        now = OutboundQueue.MAX_AGE_MS
        assertEquals("x", q.take())
    }

    @Test
    fun aBulkManifestAndALaterRemoveForTheSamePairStayInOrder() {
        // The Bucket sends every folders packet through the bulk lane, so one lane's FIFO order is the order.
        val q = OutboundQueue<String>()
        q.offer("manifest", true); q.offer("remove", true)
        assertEquals(listOf("manifest", "remove"), List(2) { q.take() })
    }
}
