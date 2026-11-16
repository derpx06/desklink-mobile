/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PeerGateTest {
    /** A stats poll in flight finishes before close() returns; none runs after (no call on a disposed peer). */
    @Test
    fun closeWaitsForACallInFlightAndRefusesLaterOnes() {
        val gate = PeerGate()
        val inside = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val poll = thread {
            gate.ifOpen {
                inside.countDown()
                Thread.sleep(200)
                events += "poll done"
            }
        }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        gate.close()
        events += "closed"
        poll.join()
        assertEquals(listOf("poll done", "closed"), events)
        assertNull(gate.ifOpen { events += "after close" })
        assertEquals(2, events.size)
    }

    @Test
    fun anOpenGateRunsTheCall() {
        assertEquals(7, PeerGate().ifOpen { 7 })
    }
}
