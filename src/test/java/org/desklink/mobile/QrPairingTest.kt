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

    @Test
    fun `qr v2 addresses are parsed by octet and only private lan ranges pass`() {
        assertEquals(listOf(10, 0, 0, 5).map(Int::toByte), QrPairing.lanIpv4("10.0.0.5"))
        assertEquals(listOf(172, 16, 4, 1).map(Int::toByte), QrPairing.lanIpv4("172.16.4.1"))
        assertEquals(listOf(172, 31, 255, 255).map(Int::toByte), QrPairing.lanIpv4("172.31.255.255"))
        for (bad in listOf("999.1.1.1", "010.0.0.1", "192.168.1", "192.168.1.1.1", "192.168.01.1", "172.32.0.1", "172.15.0.1",
            "169.254.1.1", "100.64.0.1", "127.0.0.1", "8.8.8.8", "evil.example", "", "1e1.0.0.1", "+10.0.0.1")) {
            assertEquals(bad, null, QrPairing.lanIpv4(bad))
        }
        assertEquals(listOf("192.168.1.20"), QrPairing.parseAddresses("999.1.1.1,192.168.1.20,010.0.0.1").map { it.hostAddress })
    }

    @Test
    fun aCodeForTheComputerAlreadyPairedUnderTheSameIdentityIsRecognised() {
        val fp = ByteArray(32) { it.toByte() }
        org.junit.Assert.assertTrue(QrPairing.isSamePairing(fp.copyOf(), fp))
        org.junit.Assert.assertFalse(QrPairing.isSamePairing(ByteArray(32), fp))
    }
}
