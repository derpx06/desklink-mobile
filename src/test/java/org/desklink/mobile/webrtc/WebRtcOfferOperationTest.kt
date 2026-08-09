/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcOfferOperationTest {
    @Test
    fun only_one_offer_can_be_in_flight() {
        val gate = OfferOperationGate()

        assertTrue(gate.begin())
        assertFalse(gate.begin())
        assertEquals(OfferOperationState.CREATING, gate.state())
    }

    @Test
    fun stale_create_callback_cannot_complete_a_failed_offer() {
        val gate = OfferOperationGate()

        assertTrue(gate.begin())
        assertTrue(gate.fail())
        assertFalse(gate.beginSettingLocalDescription())
        assertFalse(gate.complete())
        assertEquals(OfferOperationState.FAILED, gate.state())
    }

    @Test
    fun offer_completes_only_after_local_description_is_set() {
        val gate = OfferOperationGate()

        assertTrue(gate.begin())
        assertTrue(gate.beginSettingLocalDescription())
        assertTrue(gate.complete())
        assertFalse(gate.fail())
        assertEquals(OfferOperationState.COMPLETED, gate.state())
    }
}
