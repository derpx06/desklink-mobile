/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import org.desklink.mobile.PairingReplacement.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingReplacementTest {
    private val fresh = Entry("new", "Work Laptop", "laptop", paired = true, reachable = true, addresses = setOf("192.168.1.20"))
    private fun old(id: String = "old", name: String = "Work Laptop", type: String = "laptop", paired: Boolean = true, reachable: Boolean = false, vararg hosts: String = arrayOf("192.168.1.20")) =
        Entry(id, name, type, paired, reachable, hosts.toSet())

    @Test
    fun anOfflinePairedRecordWithTheSameNameAndModelIsReplaced() {
        val old = old(name = " work laptop ")
        assertEquals(listOf("old"), PairingReplacement.superseded(fresh, listOf(old, fresh)))
    }

    @Test
    fun aReachableTwinIsAnotherMachineAndStays() {
        assertEquals(emptyList<String>(), PairingReplacement.superseded(fresh, listOf(old(id = "twin", reachable = true))))
    }

    @Test
    fun otherNamesModelsAndUnpairedRecordsAreLeftAlone() {
        val others = listOf(
            old(id = "a", name = "Home Laptop"),
            old(id = "b", type = "desktop"),
            old(id = "c", paired = false),
        )
        assertEquals(emptyList<String>(), PairingReplacement.superseded(fresh, others))
    }

    @Test
    fun severalStaleTwinsAreAllReplaced() {
        val others = listOf(
            old(id = "o1"),
            old(id = "o2"),
        )
        assertEquals(listOf("o1", "o2"), PairingReplacement.superseded(fresh, others))
    }

    /** Two laptops that both use the default hostname are not the same machine without a shared address. */
    @Test
    fun twoMachinesWithTheSameDefaultNameAtDifferentAddressesAreBothKept() {
        assertEquals(emptyList<String>(), PairingReplacement.superseded(fresh, listOf(old(hosts = arrayOf("192.168.1.99")))))
        assertEquals(emptyList<String>(), PairingReplacement.superseded(fresh, listOf(old(hosts = emptyArray()))))
        assertEquals(emptyList<String>(), PairingReplacement.superseded(fresh.copy(addresses = emptySet()), listOf(old())))
    }

    @Test
    fun oneSharedAddressAmongSeveralIsEnough() {
        assertEquals(listOf("old"), PairingReplacement.superseded(fresh, listOf(old(hosts = arrayOf("10.0.0.5", "192.168.1.20")))))
    }
}
