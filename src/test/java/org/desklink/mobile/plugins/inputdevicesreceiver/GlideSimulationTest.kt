/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.inputdevicesreceiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Fault injection on the phone's glide state: random begin / end / double enter / motion / idle sequences (fixed seed).
 * Whatever happens, a glide belongs to one desk, ends when told or idle, never hands back from inside the screen, and the
 * hand-back point is always on the edge and inside the desk's edge length.
 */
class GlideSimulationTest {
    private val w = 1080
    private val h = 2400
    private val edges = intArrayOf(Glide.LEFT_EDGE, Glide.RIGHT_EDGE, Glide.TOP_EDGE, Glide.BOTTOM_EDGE)

    @Test
    fun randomSequencesKeepTheGlideStateConsistent() {
        for (seed in 1L..3000L) {
            val rnd = Random(seed)
            var now = 1_000L
            repeat(60) {
                now += rnd.nextInt(2_000).toLong()
                when (rnd.nextInt(7)) {
                    0, 1 -> { // an enter, sometimes from another desk, sometimes again while gliding
                        val desk = if (rnd.nextInt(4) == 0) "other" else "desk"
                        val edge = edges[rnd.nextInt(4)]
                        val length = if (rnd.nextBoolean()) 1000.0 + rnd.nextInt(2000) else null
                        Glide.begin(desk, edge, length, now)
                        assertEquals(desk, Glide.activeDesk)
                        assertTrue(Glide.isGliding(desk, now))
                        val (x, y) = Glide.entry(edge, rnd.nextDouble() * 3000 - 500, rnd.nextDouble() * 3000 - 500, length, w, h)
                        assertTrue("entry on screen: $x,$y", x in 0 until w && y in 0 until h)
                    }
                    2 -> { // the desk took it back, the link dropped, the service died, the phone locked: all are end()
                        Glide.end()
                        assertNull(Glide.activeDesk)
                        assertFalse(Glide.isGliding("desk", now))
                        assertFalse(Glide.isGliding("other", now))
                    }
                    3, 4 -> { // pointer motion
                        val edge = Glide.enterEdge
                        if (edge != Glide.NONE_EDGE) {
                            val x = rnd.nextInt(w + 1)
                            val y = rnd.nextInt(h + 1)
                            val dx = rnd.nextDouble() * 200 - 100
                            val dy = rnd.nextDouble() * 200 - 100
                            val may = Glide.mayHandBack(edge, x, y, dx, dy, w, h)
                            val back = Glide.release(edge, x, y, Glide.edgeLength, w, h)
                            if (back != null && may) {
                                val limit = Glide.edgeLength?.toInt() ?: maxOf(w, h)
                                assertTrue("hand-back inside the edge length: $back", back.first in 0..limit && back.second in 0..limit)
                                // Along the edge only: a left/right edge hands back dx 0, a top/bottom one dy 0.
                                if (edge == Glide.LEFT_EDGE || edge == Glide.RIGHT_EDGE) assertEquals(0, back.first) else assertEquals(0, back.second)
                            }
                            // Never from well inside the screen.
                            if (x in 3 until w - 3 && y in 3 until h - 3) assertNull(back)
                        }
                    }
                    5 -> { // silence past the idle bound ends it
                        now += Glide.IDLE_END_MS
                        assertFalse(Glide.isGliding(Glide.activeDesk ?: "desk", now))
                    }
                    else -> { // junk edges are never valid
                        assertFalse(Glide.validEdge(rnd.nextInt(64) * 2 + 16))
                    }
                }
            }
            Glide.end()
            assertNull("seed $seed: not idle", Glide.activeDesk)
        }
    }
}
