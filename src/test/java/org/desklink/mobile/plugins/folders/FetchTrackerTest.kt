/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

import org.junit.Assert.assertEquals
import org.junit.Test

class FetchTrackerTest {
    @Test
    fun aRefusedFileIsAskedForAgainWithABackoffThenGivenUpAfterThree() {
        val t = FetchTracker()
        assertEquals(FetchTracker.Decision.Retry(2_000), t.failed("a.txt"))
        assertEquals(FetchTracker.Decision.Retry(4_000), t.failed("a.txt"))
        assertEquals(FetchTracker.Decision.Retry(8_000), t.failed("a.txt"))
        assertEquals(FetchTracker.Decision.GiveUp, t.failed("a.txt"))
    }

    @Test
    fun theNextFileIsNotHeldUpByTheOneThatFailedAndASuccessStartsOver() {
        val t = FetchTracker()
        t.failed("a.txt")
        // b.txt arrives: its own count is untouched by a.txt, and a success clears a path's tries.
        assertEquals(FetchTracker.Decision.Retry(2_000), t.failed("b.txt"))
        t.succeeded("b.txt")
        assertEquals(FetchTracker.Decision.Retry(2_000), t.failed("b.txt"))
        assertEquals(FetchTracker.Decision.Retry(4_000), t.failed("a.txt"))
    }

    @Test
    fun aRequestNothingIsHeardOfForTwoMinutesIsLost() {
        val now = 10 * 60_000L
        val lost = FetchTracker.silent(
            listOf("quiet", "moving", "fresh"),
            requestedAt = mapOf("quiet" to 1_000L, "moving" to 1_000L, "fresh" to now - 1_000),
            lastProgress = mapOf("moving" to now - 5_000),
            now = now,
        )
        assertEquals(listOf("quiet"), lost)
    }
}
