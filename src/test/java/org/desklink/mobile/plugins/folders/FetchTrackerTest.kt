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
    fun aLiveReceiveOrAPausedSyncIsNeverJudgedSilent() {
        val now = 10 * 60_000L
        // The caller leaves out paths whose receive is live (or tracked); nothing quiet is left to judge for them.
        val outstanding = listOf("live", "quiet")
        val judged = FetchTracker.silent(outstanding - setOf("live"), mapOf("live" to 0L, "quiet" to 0L), emptyMap(), now)
        assertEquals(listOf("quiet"), judged)
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

    @Test
    fun aGiveUpSurvivesRescansUntilTheFileChangesOrRetry() {
        val gaveUp = mutableMapOf("a.txt" to "h1")
        // The same file is wanted again by the next scan: still given up.
        assertEquals(setOf("a.txt"), FetchTracker.keepGivenUp(mapOf("a.txt" to "h1", "b.txt" to "h2"), gaveUp))
        assertEquals(mapOf("a.txt" to "h1"), gaveUp)
        // It changed on the computer: a new chance.
        assertEquals(emptySet<String>(), FetchTracker.keepGivenUp(mapOf("a.txt" to "h9"), gaveUp))
        assertEquals(emptyMap<String, String>(), gaveUp)
        // No longer wanted at all (deleted, or already here): forgotten.
        val other = mutableMapOf("c.txt" to "h3")
        assertEquals(emptySet<String>(), FetchTracker.keepGivenUp(emptyMap(), other))
        assertEquals(emptyMap<String, String>(), other)
    }
}
