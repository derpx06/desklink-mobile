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
    private val results = mutableListOf<String>()
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
        buffered = 0 // drained, but the callback has not run yet
        sender.send("b".toByteArray(), cb("b"))
        assertEquals(emptyList<String>(), wire)
        sender.onBufferedAmountChange()
        assertEquals(listOf("a", "b"), wire)
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
    fun theMarksAreAsContracted() {
        assertEquals(256 * 1024L, FlowControlledSender.EVENTS_HIGH_WATER)
        assertEquals(64 * 1024L, FlowControlledSender.EVENTS_LOW_WATER)
    }
}
