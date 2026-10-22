/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {
    @Test
    fun allowsTheLimitPerWindowAndKeyThenDropsUntilTheNextWindow() {
        var now = 0L
        val limiter = RateLimiter(3, 1000, "test") { now }
        assertEquals(listOf(true, true, true, false, false), (1..5).map { limiter.allow("a") })
        assertTrue("keys are separate", limiter.allow("b"))
        now = 999
        assertFalse(limiter.allow("a"))
        now = 1000
        assertTrue(limiter.allow("a"))
    }
}
