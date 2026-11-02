/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun aFileRefusedForLackOfRoomIsAskedForAgainOnlyWhenItFitsAndNeverCountsAsAFailure() {
        val hold = RoomHold()
        val small = Entry(10L shl 20, 1, "a".repeat(64))
        val big = Entry(600L shl 20, 1, "b".repeat(64))
        hold.hold("small.bin", small); hold.hold("big.bin", big)
        assertEquals("under the 500 MB reserve nothing fits", emptyList<String>(), hold.risen(FolderSync.MIN_FREE_BYTES).map { it.first })
        assertEquals("room for the small one only", listOf("small.bin"), hold.risen(FolderSync.MIN_FREE_BYTES + (10L shl 20)).map { it.first })
        assertTrue(hold.holds("big.bin") && !hold.holds("small.bin"))
        assertEquals(listOf("big.bin"), hold.risen(Long.MAX_VALUE / 2).map { it.first })
        assertTrue(hold.isEmpty)
        // A hold is not a try: the tracker still gives its full three.
        val tracker = FetchTracker()
        assertTrue(tracker.failed("big.bin") is FetchTracker.Decision.Retry)
    }

    @Test
    fun heldFilesTheComputerNoLongerWantsAreDroppedFromTheHold() {
        val hold = RoomHold()
        listOf("a", "b/c", "b/d").forEach { hold.hold(it, Entry(1, 1, "x".repeat(64))) }
        hold.retain(setOf("a", "b/c"))
        assertTrue(hold.holds("a") && hold.holds("b/c") && !hold.holds("b/d"))
        hold.forgetWhere { it == "b/c" || it.startsWith("b/c/") }
        assertTrue(hold.holds("a") && !hold.holds("b/c"))
        hold.forgetWhere { true }
        assertTrue(hold.isEmpty)
    }
}
