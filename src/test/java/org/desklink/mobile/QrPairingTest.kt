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
}
