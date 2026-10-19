/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the desktop's `a_new_instance_id_means_the_peer_restarted`. */
class RestartEpochTest {
    @Test
    fun `a different epoch means the peer restarted`() {
        assertTrue(DeviceInfo.peerRestarted("epoch-a", "epoch-b"))
        assertFalse(DeviceInfo.peerRestarted("epoch-a", "epoch-a"))
    }

    @Test
    fun `an unknown epoch on either side never counts as a restart`() {
        assertFalse(DeviceInfo.peerRestarted(null, "epoch-b"))
        assertFalse(DeviceInfo.peerRestarted("epoch-a", null))
        assertFalse(DeviceInfo.peerRestarted("", "epoch-b"))
    }
}
