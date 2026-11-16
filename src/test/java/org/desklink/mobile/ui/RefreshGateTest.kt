/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home redraws only when what it shows changed; every other screen keeps the one-second refresh. */
class RefreshGateTest {
    @Test
    fun aScreenWithoutAStampRefreshesEverySecond() {
        val gate = RefreshGate()
        assertTrue(gate.next(null))
        assertTrue(gate.next(null))
    }

    @Test
    fun anUnchangedHomeDoesNotRefreshAndAChangedOneDoes() {
        val gate = RefreshGate()
        assertTrue("the first stamp always draws", gate.next(listOf("desk", 1)))
        assertFalse(gate.next(listOf("desk", 1)))
        assertTrue(gate.next(listOf("desk", 2)))
        assertFalse(gate.next(listOf("desk", 2)))
    }

    @Test
    fun leavingAndComingBackToHomeRefreshesEvenWhenTheStampIsTheSame() {
        val gate = RefreshGate()
        gate.next(listOf("desk", 1))
        assertTrue(gate.next(null))
        assertTrue("another screen was drawn in between", gate.next(listOf("desk", 1)))
    }
}
