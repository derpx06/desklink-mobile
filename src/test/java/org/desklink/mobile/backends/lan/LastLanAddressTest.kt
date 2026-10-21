/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.backends.lan

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class LastLanAddressTest {
    @Test
    fun onlyUnlinkedDesksAtNumericPrivateAddressesAreDialled() {
        val stored = mapOf(
            "desk" to "192.168.43.20",
            "linked" to "192.168.1.5",
            "public" to "8.8.8.8",
            "named" to "evil.example",
            "never" to null,
            "loopback" to "127.0.0.1",
            "v6" to "fe80::1",
            "same" to "192.168.43.20",
        )
        assertEquals(
            listOf(InetAddress.getByName("192.168.43.20"), InetAddress.getByName("fe80::1")),
            LastLanAddress.targets(stored, linked = setOf("linked")),
        )
    }
}
