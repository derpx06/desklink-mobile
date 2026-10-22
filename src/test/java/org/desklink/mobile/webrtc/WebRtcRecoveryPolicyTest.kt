/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcRecoveryPolicyTest {
    @Test
    fun `backoff is bounded and duplicate workers are coalesced`() {
        val policy = WebRtcRecoveryPolicy()
        // The ladder is in lockstep with the desk (contract B3): six rungs, then the last repeats.
        assertEquals(250L, policy.claimDelayMillis())
        assertNull(policy.claimDelayMillis())
        policy.release()
        assertEquals(1_000L, policy.claimDelayMillis())
        policy.release()
        for (delay in listOf(2_000L, 4_000L, 8_000L, 16_000L, 16_000L)) {
            assertEquals(delay, policy.claimDelayMillis())
            policy.release()
        }
    }

    @Test
    fun `an explicit reset event starts the ladder over`() {
        val policy = WebRtcRecoveryPolicy()
        policy.claimDelayMillis()
        policy.release()
        policy.claimDelayMillis()
        policy.reset()
        assertEquals(0, policy.attempts())
        assertEquals(250L, policy.claimDelayMillis())
    }

    private fun failures(policy: WebRtcRecoveryPolicy, count: Int): List<Long> = List(count) {
        policy.claimDelayMillis()!!.also { policy.release() }
    }

    @Test
    fun `feature-ready alone does not reset the ladder`() {
        var now = 0L
        val policy = WebRtcRecoveryPolicy { now }
        assertEquals(listOf(250L, 1_000L, 2_000L), failures(policy, 3))
        // Ready, then it drops 59 s later: still on the ladder.
        policy.markReady()
        now += 59_999
        assertEquals(4_000L, policy.claimDelayMillis())
    }

    @Test
    fun `sixty seconds of healthy feature-ready starts the ladder over`() {
        var now = 0L
        val policy = WebRtcRecoveryPolicy { now }
        failures(policy, 4)
        policy.markReady()
        now += WebRtcRecoveryPolicy.HEALTHY_RESET_MS
        assertEquals(250L, policy.claimDelayMillis())
    }

    @Test
    fun `a flapping link climbs the ladder instead of redialing at the first rung forever`() {
        var now = 0L
        val policy = WebRtcRecoveryPolicy { now }
        val delays = mutableListOf<Long>()
        repeat(8) {
            delays += policy.claimDelayMillis()!!
            policy.release()
            policy.markReady()
            now += 5_000 // ready for 5 s, then it drops again
        }
        assertEquals(listOf(250L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 16_000L, 16_000L), delays)
    }

    @Test
    fun `after the last rung it is unavailable but keeps going, until a reset event`() {
        val policy = WebRtcRecoveryPolicy()
        failures(policy, 6)
        assertFalse(policy.unavailable())
        assertEquals(16_000L, policy.claimDelayMillis())
        assertTrue(policy.unavailable())
        policy.release()
        assertEquals(16_000L, policy.claimDelayMillis())
        policy.reset()
        assertFalse(policy.unavailable())
        assertEquals(250L, policy.claimDelayMillis())
    }
}
