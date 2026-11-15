/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class FixedRateClockTest {
    /** A still screen's input-rate estimate (7 fps) must not stretch the encoder's frame clock. */
    @Test
    fun theFrameClockStaysAtTheMaximumRateWhileTheBitrateFollowsLibwebrtc() {
        val clock = DeskLinkVideoEncoderFactories.FixedRateClock()
        clock.pin(60)
        clock.setTargets(16_000_000, 60.0)
        clock.setTargets(12_000_000, 7.0)
        assertEquals(60.0, clock.adjustedFramerateFps, 0.0)
        assertEquals(12_000_000, clock.adjustedBitrateBps)
        clock.pin(30) // off the LAN the stream is capped at 30
        clock.setTargets(5_000_000, 9.0)
        assertEquals(30.0, clock.adjustedFramerateFps, 0.0)
    }
}
