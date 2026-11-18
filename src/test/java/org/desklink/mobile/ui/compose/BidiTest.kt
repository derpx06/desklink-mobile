/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In a right-to-left layout a size ("80x24") or a round trip ("1 ms") must keep its left-to-right reading. */
class BidiTest {
    @Test
    fun aTechnicalRunIsIsolatedLeftToRight() {
        val size = ltrRun("80×24")
        assertEquals('⁦', size.first())
        assertEquals('⁩', size.last())
        assertEquals("80×24", size.substring(1, size.length - 1))
    }

    @Test
    fun joinedPartsAreIsolatedOneByOneSoTheOrderOfTheLineIsTheLayoutsNotTheText() {
        val line = listOf("~/projects", "arch-desk", "1 ms").joinToString(" · ") { ltrRun(it) }
        assertEquals(3, line.count { it == '⁦' })
        assertEquals(3, line.count { it == '⁩' })
        assertTrue(line.contains("⁩ · ⁦"))
    }

    @Test
    fun anEmptyRunStaysEmpty() = assertEquals("", ltrRun(""))
}
