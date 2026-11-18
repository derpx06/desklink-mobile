/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In a right-to-left layout a size ("80x24") or a round trip ("1 ms") must keep its left-to-right reading. */
class BidiTest {
    private val lri = '⁦'
    private val pdi = '⁩'

    @Test
    fun aTechnicalRunIsIsolatedLeftToRight() {
        val size = ltrRun("80×24")
        assertEquals(lri, size.first())
        assertEquals(pdi, size.last())
        assertEquals("80×24", size.substring(1, size.length - 1))
    }

    @Test
    fun anEmptyRunStaysEmpty() {
        assertEquals("", ltrRun(""))
        assertEquals("", isolatedRun(""))
    }

    /** A computer's name is not ours: it must not close our isolate (PDI) nor reorder what follows (RLO, RLI, embeddings). */
    @Test
    fun aHostileNameCannotEscapeItsIsolate() {
        val evil = "evil⁩‮abc⁧‪⁦"
        val run = ltrRun(evil)
        assertEquals("evilabc", withoutBidiControls(evil))
        assertEquals("one isolate, ours", 1, run.count { it == lri })
        assertEquals("one close, ours", 1, run.count { it == pdi })
        assertFalse(run.any { it.code in 0x202A..0x202E })
        assertEquals("a name in its own direction is isolated and stripped the same", 1, isolatedRun(evil).count { it == pdi })
    }

    /** The header's parts are passed, never split: a part holding the separator cannot fake a second part. */
    @Test
    fun theDetailLineIsOneIsolateAndOneSeparatorPerPart() {
        val line = detailLine(listOf("~/projects", "arch-desk · fake", "1 ms", ""))
        assertEquals("the empty part is gone", 3, line.count { it == lri })
        assertEquals(3, line.count { it == pdi })
        assertEquals("two separators between three parts, none from inside a name", 2, Regex("⁩ · ⁦").findAll(line).count())
        assertTrue(line.contains("arch-desk · fake"))
    }
}
