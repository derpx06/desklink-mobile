package org.desklink.mobile.plugins.mousepad

import org.desklink.mobile.NetworkPacket
import org.junit.Assert.assertEquals
import org.junit.Test

class PointerMotionCoalescerTest {
    private val frames = ArrayList<Runnable>()
    private val sent = ArrayList<NetworkPacket>()
    private val coalescer = PointerMotionCoalescer({ frames += it }, { sent += it })

    private fun vsync() {
        val due = frames.toList()
        frames.clear()
        due.forEach(Runnable::run)
    }

    private fun describe(np: NetworkPacket) = when {
        np.has("singleclick") -> "click"
        np.has("dx") -> "d(${np.getDouble("dx")},${np.getDouble("dy")})"
        else -> "p(${np.getInt("x")},${np.getInt("y")})"
    }

    @Test
    fun deltasWithinOneFrameBecomeOnePacket() {
        coalescer.addDelta(1.0, 2.0)
        coalescer.addDelta(3.0, -1.0)
        coalescer.addDelta(0.5, 0.5)
        assertEquals(emptyList<String>(), sent.map(::describe))
        assertEquals(1, frames.size)
        vsync()
        assertEquals(listOf("d(4.5,1.5)"), sent.map(::describe))
        vsync()
        assertEquals(1, sent.size)
    }

    @Test
    fun onlyTheLatestAbsolutePositionIsSentPerFrame() {
        coalescer.moveTo(10, 10)
        coalescer.moveTo(20, 30)
        vsync()
        coalescer.moveTo(40, 50)
        vsync()
        assertEquals(listOf("p(20,30)", "p(40,50)"), sent.map(::describe))
    }

    @Test
    fun aDiscretePacketFlushesPendingMotionFirst() {
        coalescer.moveTo(5, 6)
        coalescer.sendAfterMotion(NetworkPacket(MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST).apply { this["singleclick"] = true })
        vsync()
        assertEquals(listOf("p(5,6)", "click"), sent.map(::describe))
    }

    @Test
    fun switchingBetweenRelativeAndAbsoluteKeepsOrder() {
        coalescer.addDelta(1.0, 1.0)
        coalescer.moveTo(7, 8)
        coalescer.addDelta(2.0, 2.0)
        vsync()
        assertEquals(listOf("d(1.0,1.0)", "p(7,8)", "d(2.0,2.0)"), sent.map(::describe))
    }
}
