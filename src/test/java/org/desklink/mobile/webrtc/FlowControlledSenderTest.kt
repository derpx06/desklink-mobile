/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.desklink.mobile.transport.TransportCallback
import org.desklink.mobile.transport.TransportError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowControlledSenderTest {
    private var buffered = 0L
    private val wire = mutableListOf<String>()
    private val results = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val sender = FlowControlledSender({ buffered }, { wire += String(it); true }, highWater = 100, lowWater = 20)

    private fun cb(name: String) = object : TransportCallback {
        override fun onSuccess() { results += "$name ok" }
        override fun onFailure(error: TransportError) { results += "$name ${error.code}" }
    }

    @Test
    fun anIdleChannelSendsAtOnce() {
        sender.send("a".toByteArray(), cb("a"))
        assertEquals(listOf("a"), wire)
        assertEquals(listOf("a ok"), results)
    }

    @Test
    fun aBackedUpChannelHoldsInOrderAndResumesUnderTheLowWater() {
        buffered = 150
        sender.send("a".toByteArray(), cb("a"))
        sender.send("b".toByteArray(), cb("b"))
        assertEquals(emptyList<String>(), wire)
        buffered = 50 // down, but not yet under the low-water mark
        sender.onBufferedAmountChange()
        assertEquals(emptyList<String>(), wire)
        buffered = 10
        sender.onBufferedAmountChange()
        assertEquals(listOf("a", "b"), wire)
        assertEquals(0, sender.heldCount())
    }

    @Test
    fun aNewMessageNeverOvertakesHeldOnes() {
        buffered = 150
        sender.send("a".toByteArray(), cb("a"))
        buffered = 0 // drained, but nothing has told the sender yet
        sender.send("b".toByteArray(), cb("b"))
        assertEquals("a goes first, then b", listOf("a", "b"), wire)
    }

    @Test
    fun anOverfullQueueReportsInsteadOfDroppingSilently() {
        buffered = 1_000
        repeat(FlowControlledSender.MAX_HELD_MESSAGES) { sender.send("x".toByteArray(), cb("m$it")) }
        sender.send("overflow".toByteArray(), cb("overflow"))
        assertEquals(listOf("overflow SEND_FAILED"), results)
        val bytes = FlowControlledSender(
            { 1_000 }, { true }, highWater = 100, lowWater = 20,
        )
        val big = ByteArray(FlowControlledSender.MAX_HELD_BYTES.toInt())
        bytes.send(big, cb("big"))
        bytes.send(ByteArray(1), cb("one"))
        assertEquals("one SEND_FAILED", results.last())
    }

    @Test
    fun aClosedChannelReportsEveryHeldMessage() {
        buffered = 1_000
        sender.send("a".toByteArray(), cb("a"))
        sender.send("b".toByteArray(), cb("b"))
        sender.failAll("closed")
        assertEquals(listOf("a CLOSED", "b CLOSED"), results)
        assertTrue(wire.isEmpty())
    }

    @Test
    fun aCallbackThatThrowsDoesNotStrandTheMessagesBehindIt() {
        val bad = object : TransportCallback {
            override fun onSuccess() = error("boom")
            override fun onFailure(error: TransportError) = Unit
        }
        buffered = 150
        sender.send("a".toByteArray(), bad)
        sender.send("b".toByteArray(), cb("b"))
        buffered = 0
        sender.onBufferedAmountChange()
        assertEquals(listOf("a", "b"), wire)
        assertEquals(0, sender.heldCount())
    }

    @Test
    fun theMarksAreAsContracted() {
        assertEquals(256 * 1024L, FlowControlledSender.EVENTS_HIGH_WATER)
        assertEquals(64 * 1024L, FlowControlledSender.EVENTS_LOW_WATER)
    }

    /** The native send and the callbacks run outside the monitor: a callback may call back into the sender. */
    @Test
    fun aCallbackMayReenterTheSenderWithoutDeadlock() {
        val reentrant = object : TransportCallback {
            override fun onSuccess() {
                sender.send("again".toByteArray(), cb("again"))
                sender.onBufferedAmountChange()
            }

            override fun onFailure(error: TransportError) = Unit
        }
        val done = java.util.concurrent.CountDownLatch(1)
        Thread { sender.send("first".toByteArray(), reentrant); done.countDown() }.start()
        assertTrue("deadlocked", done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf("first", "again"), wire)
    }

    @Test
    fun concurrentSendersAndTheObserverKeepEveryMessageOnceAndInOrderPerSender() {
        val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
        val concurrent = FlowControlledSender({ 0 }, { sent += String(it); true }, highWater = 100, lowWater = 20)
        val threads = (0 until 4).map { t ->
            Thread { repeat(200) { n -> concurrent.send("$t:$n".toByteArray(), cb("x")); concurrent.onBufferedAmountChange() } }
        }
        threads.forEach(Thread::start)
        threads.forEach { it.join(10_000) }
        assertEquals(800, sent.size)
        for (t in 0 until 4) assertEquals((0 until 200).map { "$t:$it" }, sent.filter { it.startsWith("$t:") })
    }
}
