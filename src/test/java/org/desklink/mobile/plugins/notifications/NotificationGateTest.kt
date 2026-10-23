/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Nothing is mirrored on a guess about the person's per-app settings: held until they are loaded, and a failed load is retried. */
@RunWith(RobolectricTestRunner::class)
class NotificationGateTest {
    private class Harness(var failures: Int = 0) {
        val later = ArrayDeque<Pair<Long, () -> Unit>>()
        val released = mutableListOf<List<String>>()
        var loads = 0
        val gate = NotificationGate<String>(
            load = { loads++; if (failures > 0) { failures--; throw java.io.IOException("/data/secret/path") } },
            runWorker = { it() },
            postLater = { delay, task -> later.addLast(delay to task) },
            onReady = { released += it },
        )
        fun runLater() { later.removeFirst().second() }
    }

    @Test
    fun whatArrivesBeforeTheSettingsAreLoadedIsHeldNotSent() {
        val h = Harness(failures = 1)
        h.gate.hold("a")
        h.gate.hold("b")
        assertFalse(h.gate.ready)
        assertEquals("nothing is released while the load has failed", emptyList<List<String>>(), h.released)
        h.runLater() // the retry succeeds
        assertTrue(h.gate.ready)
        assertEquals("what was held goes out once, in order", listOf(listOf("a", "b")), h.released)
    }

    @Test
    fun aFailedLoadIsTriedAgainWithAGrowingPauseUntilItWorks() {
        val h = Harness(failures = 3)
        h.gate.start()
        assertEquals(listOf(1_000L), h.later.map { it.first })
        h.runLater()
        h.runLater()
        assertEquals("1 s, then 2 s, then 4 s", listOf(4_000L), h.later.map { it.first })
        h.runLater()
        assertTrue(h.gate.ready)
        assertEquals(4, h.loads)
    }

    @Test
    fun thePauseIsCappedAndAReadyGateDoesNotLoadAgain() {
        val h = Harness(failures = 20)
        h.gate.start()
        repeat(15) { h.runLater() }
        assertEquals(NotificationGate.MAX_DELAY_MS, h.later.single().first)
        h.failures = 0
        h.runLater()
        val loads = h.loads
        h.gate.start()
        h.gate.hold("late")
        assertEquals("once ready, held items are not kept and nothing loads again", loads, h.loads)
    }

    @Test
    fun theHoldIsBoundedAndDropsTheOldestRatherThanSendingTheWrongThing() {
        val h = Harness(failures = 1)
        repeat(NotificationGate.CAPACITY + 5) { h.gate.hold("n$it") }
        h.runLater()
        val out = h.released.single()
        assertEquals(NotificationGate.CAPACITY, out.size)
        assertEquals("n5", out.first())
    }

    @Test
    fun aStoppedGateSendsNothing() {
        val h = Harness(failures = 1)
        h.gate.hold("a")
        h.gate.stop()
        h.runLater()
        assertEquals(emptyList<List<String>>(), h.released)
    }
}
