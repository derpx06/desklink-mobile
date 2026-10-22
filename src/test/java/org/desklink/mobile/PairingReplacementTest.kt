/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import org.desklink.mobile.PairingReplacement.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingReplacementTest {
    private val fresh = Entry("new", "Work Laptop", "laptop", paired = true, reachable = true)

    @Test
    fun anOfflinePairedRecordWithTheSameNameAndModelIsReplaced() {
        val old = Entry("old", " work laptop ", "laptop", paired = true, reachable = false)
        assertEquals(listOf("old"), PairingReplacement.superseded(fresh, listOf(old, fresh)))
    }

    @Test
    fun aReachableTwinIsAnotherMachineAndStays() {
        assertEquals(emptyList<String>(), PairingReplacement.superseded(fresh, listOf(Entry("twin", "Work Laptop", "laptop", paired = true, reachable = true))))
    }

    @Test
    fun otherNamesModelsAndUnpairedRecordsAreLeftAlone() {
        val others = listOf(
            Entry("a", "Home Laptop", "laptop", paired = true, reachable = false),
            Entry("b", "Work Laptop", "desktop", paired = true, reachable = false),
            Entry("c", "Work Laptop", "laptop", paired = false, reachable = false),
        )
        assertEquals(emptyList<String>(), PairingReplacement.superseded(fresh, others))
    }

    @Test
    fun severalStaleTwinsAreAllReplaced() {
        val others = listOf(
            Entry("o1", "Work Laptop", "laptop", paired = true, reachable = false),
            Entry("o2", "Work Laptop", "laptop", paired = true, reachable = false),
        )
        assertEquals(listOf("o1", "o2"), PairingReplacement.superseded(fresh, others))
    }
}
