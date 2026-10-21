/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The same vector is pinned by the desktop's
 * `the_proof_matches_the_shared_cross_platform_vector` (pairing_qr.rs):
 * if either side's message format drifts, QR pairing silently fails.
 */
class QrPairingTest {
    @Test
    fun `proof matches the shared cross-platform vector`() {
        val proof = QrPairing.proofBytes(
            ByteArray(32) { 7 },
            "desk",
            "phone",
            ByteArray(32) { 1 },
            ByteArray(32) { 2 },
        )
        assertEquals(
            "bd84b158ada359358ce90dce45c314f15be4b48f00f8a96aae82b12769f9fc41",
            proof.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `qr v2 addresses are private ipv4 literals only`() {
        assertEquals(
            listOf("192.168.1.20", "10.0.0.5"),
            QrPairing.parseAddresses("192.168.1.20, 10.0.0.5,8.8.8.8,evil.example,127.0.0.1,192.168.1.20,").map { it.hostAddress },
        )
        assertEquals(emptyList<java.net.InetAddress>(), QrPairing.parseAddresses(null))
    }

    @Test
    fun `qr v2 port is in DeskLink's range`() {
        assertEquals(1716, QrPairing.parsePort("1716"))
        assertEquals(null, QrPairing.parsePort("80"))
        assertEquals(null, QrPairing.parsePort("x"))
        assertEquals(null, QrPairing.parsePort(null))
    }
}
