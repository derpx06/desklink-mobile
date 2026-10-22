/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.backends.lan

import org.desklink.mobile.backends.lan.LastLanAddress.Known
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LastLanAddressTest {
    @Test
    fun onlyUnlinkedDesksAtNumericPrivateAddressesAreAnnouncedTo() {
        val stored = mapOf(
            "desk" to listOf(Known("192.168.43.20", 1716)),
            "linked" to listOf(Known("192.168.1.5", 0)),
            "public" to listOf(Known("8.8.8.8", 1716)),
            "named" to listOf(Known("evil.example", 1716)),
            "never" to emptyList(),
            "loopback" to listOf(Known("127.0.0.1", 1716)),
            "v6" to listOf(Known("fe80::1", 0)),
            "same" to listOf(Known("192.168.43.20", 0)),
        )
        assertEquals(
            listOf(InetAddress.getByName("192.168.43.20"), InetAddress.getByName("fe80::1")),
            LastLanAddress.announceTargets(stored, linked = setOf("linked")),
        )
    }

    @Test
    fun theLastThreeAddressesAreKeptMostRecentFirst() {
        var list = emptyList<Known>()
        for (ip in listOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4")) list = AddressBook.remember(list, ip, 1716)
        assertEquals(listOf("10.0.0.4", "10.0.0.3", "10.0.0.2"), list.map { it.host })
        list = AddressBook.remember(list, "10.0.0.2", 1716)
        assertEquals(listOf("10.0.0.2", "10.0.0.4", "10.0.0.3"), list.map { it.host })
    }

    @Test
    fun anUnknownPortNeverErasesAKnownOne() {
        val list = AddressBook.remember(AddressBook.remember(emptyList(), "10.0.0.1", 1720), "10.0.0.1", 0)
        assertEquals(listOf(Known("10.0.0.1", 1720)), list)
        assertEquals(listOf(Known("10.0.0.1", 1721)), AddressBook.remember(list, "10.0.0.1", 1721))
    }

    @Test
    fun theStoredFormRoundTripsAndRefusesGarbage() {
        val list = listOf(Known("192.168.1.9", 1716), Known("fe80::1%wlan0", 0))
        assertEquals(list, AddressBook.decode(AddressBook.encode(list)))
        assertEquals(emptyList<Known>(), AddressBook.decode(null))
        assertEquals(emptyList<Known>(), AddressBook.decode("nonsense,|1,10.0.0.1|notaport,10.0.0.1|99999"))
        assertEquals(AddressBook.MAX, AddressBook.decode("1.1.1.1|1,2.2.2.2|1,3.3.3.3|1,4.4.4.4|1").size)
    }

    @Test
    fun onlyKnownPortsAreDialledAndABackedOffAddressWaits() {
        val backoff = DialBackoff()
        val stored = mapOf(
            "desk" to listOf(Known("192.168.1.9", 1716), Known("192.168.1.8", 0), Known("192.168.1.7", 1717)),
            "linked" to listOf(Known("192.168.1.5", 1716)),
        )
        fun dialed(now: Long) = LastLanAddress.dialTargets(stored, setOf("linked"), backoff, now).map { it.address.hostAddress }
        assertEquals(listOf("192.168.1.9", "192.168.1.7"), dialed(0))
        backoff.failed("desk/192.168.1.9:1716", 0)
        assertEquals(listOf("192.168.1.7"), dialed(1_000))
        assertEquals(listOf("192.168.1.9", "192.168.1.7"), dialed(DialBackoff.FIRST_DELAY_MS))
    }

    @Test
    fun theBackoffGrowsIsCappedAndResetsOnSuccessOrANewNetwork() {
        val b = DialBackoff()
        var now = 0L
        val waits = mutableListOf<Long>()
        repeat(12) {
            b.failed("a", now)
            val wait = (DialBackoff.delayAfter(it + 1))
            waits += wait
            assertFalse(b.canDial("a", now + wait - 1))
            assertTrue(b.canDial("a", now + wait))
            now += wait
        }
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L), waits.take(4))
        assertEquals(DialBackoff.MAX_DELAY_MS, waits.last())
        b.succeeded("a")
        assertTrue(b.canDial("a", now))
        b.failed("a", now)
        b.reset()
        assertTrue(b.canDial("a", now))
    }
}
