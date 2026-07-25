/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebRtcRecoveryPolicyTest {
    @Test
    fun `backoff is bounded and duplicate workers are coalesced`() {
        val policy = WebRtcRecoveryPolicy()
        assertEquals(1_000L, policy.claimDelayMillis())
        assertNull(policy.claimDelayMillis())
        policy.release()
        assertEquals(2_000L, policy.claimDelayMillis())
        policy.release()
        for (delay in listOf(4_000L, 8_000L, 16_000L, 30_000L, 30_000L)) {
            assertEquals(delay, policy.claimDelayMillis())
            policy.release()
        }
    }

    @Test
    fun `successful handover resets backoff`() {
        val policy = WebRtcRecoveryPolicy()
        policy.claimDelayMillis()
        policy.release()
        policy.claimDelayMillis()
        policy.reset()
        assertEquals(0, policy.attempts())
        assertEquals(1_000L, policy.claimDelayMillis())
    }
}
