package org.desklink.mobile.plugins.screen

import kotlinx.coroutines.runBlocking
import org.desklink.mobile.plugins.screen.ExtendLayout.Ask
import org.desklink.mobile.plugins.screen.ExtendLayout.Measured
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendLayoutTest {
    private val land = 2400 to 1080
    private val port = 1080 to 2400

    @Test
    fun aSettledLandscapeViewWithTheBarsHiddenIsAskedAtOnce() {
        assertEquals(Ask(land), ExtendLayout.decide(Measured(land, 0), nowMs = ExtendLayout.AREA_STABLE_MS, waitedMs = 300, barsHidden = true))
    }

    @Test
    fun aSizeThatJustChangedIsNotAskedYet() {
        // Two frames after the first layout: the bars or the cutout may still be about to change it.
        assertNull(ExtendLayout.decide(Measured(land, 1000), nowMs = 1030, waitedMs = 300, barsHidden = true))
        assertNull(ExtendLayout.decide(Measured(land, 0), nowMs = 1000, waitedMs = 300, barsHidden = false))
    }

    @Test
    fun aPortraitViewIsWaitedOutThenAskedAsItIs() {
        // Android 16 ignores the landscape request on a tablet: the monitor then matches the visible portrait view.
        assertNull(ExtendLayout.decide(Measured(port, 0), nowMs = 1000, waitedMs = 1000, barsHidden = true))
        assertEquals(Ask(port), ExtendLayout.decide(Measured(port, 0), nowMs = 2100, waitedMs = ExtendLayout.AREA_WAIT_MS, barsHidden = true))
    }

    @Test
    fun nothingMeasuredAsksWithNoSizeAfterTheWait() {
        assertNull(ExtendLayout.decide(null, nowMs = 1000, waitedMs = 1000, barsHidden = true))
        assertEquals(Ask(null), ExtendLayout.decide(null, nowMs = 2100, waitedMs = ExtendLayout.AREA_WAIT_MS, barsHidden = true))
    }

    @Test
    fun aViewThatNeverSettlesIsAskedAtTheHardLimit() {
        assertNull(ExtendLayout.decide(Measured(land, 3990), nowMs = 4000, waitedMs = 3999, barsHidden = true))
        assertEquals(Ask(land), ExtendLayout.decide(Measured(land, 3990), nowMs = 4000, waitedMs = 2 * ExtendLayout.AREA_WAIT_MS, barsHidden = true))
    }

    @Test
    fun theWaitLoopRunsOnTheInjectedClock() = runBlocking {
        var now = 10_000L
        // The first layout is portrait (the turn is in progress), then landscape at +200 ms, with the bars hidden at +100 ms.
        val answer = ExtendLayout.awaitAsk(
            startMs = now,
            now = { now },
            measured = { if (now < 10_200) Measured(port, 10_000) else Measured(land, 10_200) },
            barsHidden = { now >= 10_100 },
            pause = { now += it },
        )
        assertEquals(Ask(land), answer)
        // Landscape from 10_200, settled 250 ms later, bars already hidden.
        assertEquals(10_450L, now)
    }

    @Test
    fun theWaitLoopGivesUpOnAViewThatStaysPortrait() = runBlocking {
        var now = 0L
        val answer = ExtendLayout.awaitAsk(0, { now }, { Measured(port, 0) }, { true }, { now += it })
        assertEquals(Ask(port), answer)
        assertEquals(ExtendLayout.AREA_WAIT_MS, now)
    }

    @Test
    fun aChangeFromWhatWasSentIsAskedAgainEvenIfNothingWasMeasuredThen() {
        assertTrue(ExtendLayout.needsReask(land, sent = null))
        assertTrue(ExtendLayout.needsReask(land, sent = 2340 to 1080))
        assertFalse(ExtendLayout.needsReask(land, sent = land))
        assertFalse(ExtendLayout.needsReask(null, sent = land))
    }
}
