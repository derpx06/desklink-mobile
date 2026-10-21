/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.desklink.mobile.heartbeatPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.webrtc.PeerLiveness.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerLivenessTest {
    /** Ticks once a second from [from] to [to] inclusive, returning the last verdict and any probes. */
    private fun PeerLiveness.run(from: Long, to: Long): Pair<Verdict, Int> {
        var last = Verdict.OK
        var probes = 0
        var now = from
        while (now <= to) {
            last = tick(now)
            if (last == Verdict.PROBE) probes++
            now += 1_000
        }
        return last to probes
    }

    @Test
    fun silenceProbesAfterFiveSecondsAndIsDeadAfterFifteen() {
        val liveness = PeerLiveness(5_000, 15_000).apply { start(0) }
        assertEquals(Verdict.OK, liveness.run(1_000, 4_000).first)
        assertEquals(Verdict.PROBE, liveness.tick(5_000))
        val (verdict, probes) = liveness.run(6_000, 15_000)
        assertEquals(Verdict.DEAD, verdict)
        assertEquals(1, probes) // at 10 s; one probe per 5 s
    }

    @Test
    fun inboundDataKeepsThePeerAlive() {
        val liveness = PeerLiveness(5_000, 15_000).apply { start(0) }
        liveness.run(1_000, 4_000)
        liveness.onInbound(4_500)
        assertEquals(Verdict.OK, liveness.run(5_000, 9_000).first)
    }

    @Test
    fun aFrozenProcessDoesNotDeclareTheDeskDeadOnItsFirstTick() {
        val liveness = PeerLiveness(5_000, 15_000).apply { start(0) }
        liveness.run(1_000, 3_000)
        // Frozen with the screen off for a minute; the first tick after the thaw comes before the
        // buffered data is delivered.
        assertEquals(Verdict.OK, liveness.tick(63_000))
        liveness.onInbound(63_200)
        assertEquals(Verdict.OK, liveness.run(64_000, 67_000).first)
        // A gap of exactly the threshold is still an ordinary tick.
        val steady = PeerLiveness(5_000, 15_000).apply { start(0) }
        steady.run(1_000, 12_000)
        assertEquals(Verdict.DEAD, steady.tick(15_000))
    }

    @Test
    fun heartbeatsSayWhetherThePhoneIsDozing() {
        val probe = heartbeatPacket(reply = false, interactive = false)
        assertEquals(DeskLinkProtocol.PACKET_TYPE_HEARTBEAT, probe.type)
        assertTrue(probe.getBoolean("dozing", false))
        assertFalse(probe.has("reply"))
        val answer = heartbeatPacket(reply = true, interactive = true)
        assertTrue(answer.getBoolean("reply", false))
        assertFalse(answer.getBoolean("dozing", true))
    }
}
