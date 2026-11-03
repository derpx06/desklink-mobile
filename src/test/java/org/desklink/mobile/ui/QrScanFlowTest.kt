/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import org.desklink.mobile.QrPairing
import org.desklink.mobile.ui.QrScanFlow.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrScanFlowTest {
    private fun token(flow: QrScanFlow) = (flow.state as State.Pairing).token

    private fun attempt(undone: IntArray) = QrPairing.Attempt().also { it.undo = { undone[0]++ } }

    @Test
    fun aRefusedCodeClosesTheAttemptWithItsMessageAndKeepsTheCameraOff() {
        val flow = QrScanFlow()
        assertTrue(flow.start("desk"))
        flow.progress(token(flow), QrPairing.Stage.VERIFYING)
        assertTrue(flow.fail(token(flow), "expired"))
        assertEquals(State.Stopped("expired"), flow.state)
        assertFalse("the code still in view must not start another attempt", flow.accepts())
    }

    @Test
    fun cancelStopsScanningUntilTheUserRescans() {
        val flow = QrScanFlow()
        flow.start("desk")
        assertTrue(flow.cancel { false })
        assertEquals(State.Stopped(null), flow.state)
        assertFalse(flow.accepts())
        val before = flow.round
        flow.rescan()
        assertEquals(State.Scanning, flow.state)
        assertTrue(flow.round != before)
        assertTrue(flow.accepts())
    }

    @Test
    fun theSameCodeCanBeTriedAgainAfterAFailure() {
        val flow = QrScanFlow()
        flow.start("desk")
        flow.fail(token(flow), "refused")
        flow.rescan()
        assertTrue(flow.start("desk"))
        assertEquals("desk", (flow.state as State.Pairing).id)
    }

    @Test
    fun aSecondAttemptCannotStartWhileOneRuns() {
        val flow = QrScanFlow()
        flow.start("a")
        assertFalse(flow.start("b"))
        assertEquals("a", (flow.state as State.Pairing).id)
    }

    @Test
    fun aTimeoutEndsOnlyTheAttemptItWasArmedFor() {
        val flow = QrScanFlow()
        flow.start("desk")
        val first = token(flow)
        flow.cancel { false }
        flow.rescan()
        flow.start("desk")
        assertFalse("a stale timer of the cancelled attempt", flow.timedOut(first, "late"))
        assertTrue(token(flow) != first)
        assertTrue(flow.timedOut(token(flow), "too slow"))
        assertEquals(State.Stopped("too slow"), flow.state)
    }

    @Test
    fun lateCallbacksOfAFinishedOrSupersededAttemptAreIgnored() {
        val flow = QrScanFlow()
        flow.start("desk")
        val old = token(flow)
        flow.cancel { false }
        assertFalse(flow.fail(old, "canceled by user"))
        assertEquals(State.Stopped(null), flow.state)
        flow.progress(old, QrPairing.Stage.VERIFYING)
        assertEquals(State.Stopped(null), flow.state)
        // The same computer again: the old attempt's callbacks still do not move the new one.
        flow.rescan()
        flow.start("desk")
        flow.progress(old, QrPairing.Stage.VERIFYING)
        assertEquals(QrPairing.Stage.CONNECTING, (flow.state as State.Pairing).stage)
        assertFalse(flow.fail(old, "stale"))
        assertTrue(flow.state is State.Pairing)
    }

    @Test
    fun aCodeThatIsNotOursStopsTheCameraWithAMessage() {
        val flow = QrScanFlow()
        assertTrue(flow.reject("not a pairing code"))
        assertEquals(State.Stopped("not a pairing code"), flow.state)
        assertFalse(flow.reject("again"))
    }

    @Test
    fun resetReturnsToScanningFromAnywhere() {
        val flow = QrScanFlow()
        flow.start("desk")
        flow.reset()
        assertEquals(State.Scanning, flow.state)
    }

    // ---- the attempt's lifecycle: leaving the scan screen must never take back a pairing that worked ----

    @Test
    fun aCompletedPairingSurvivesLeavingTheScanScreen() {
        val undone = intArrayOf(0)
        val flow = QrScanFlow()
        flow.start("desk")
        flow.attach(token(flow), attempt(undone))
        flow.succeeded("desk")
        flow.leave { false }
        assertEquals("the pairing was not cancelled", 0, undone[0])
        assertEquals(State.Scanning, flow.state)
    }

    @Test
    fun aPairingThatCompletedBeforeTheScreenNoticedIsNotTakenBackEither() {
        val undone = intArrayOf(0)
        val flow = QrScanFlow()
        flow.start("desk")
        flow.attach(token(flow), attempt(undone))
        flow.leave { id -> id == "desk" }
        assertEquals(0, undone[0])
    }

    @Test
    fun leavingMidNegotiationTakesTheAttemptBack() {
        val undone = intArrayOf(0)
        val flow = QrScanFlow()
        flow.start("desk")
        flow.attach(token(flow), attempt(undone))
        flow.leave { false }
        assertEquals(1, undone[0])
    }

    @Test
    fun cancelAfterSuccessDoesNotCallUndo() {
        val undone = intArrayOf(0)
        val attempt = attempt(undone)
        attempt.markSucceeded()
        attempt.cancel()
        assertEquals(0, undone[0])
        assertFalse(attempt.cancelled)
    }

    @Test
    fun theCancelButtonAndAFailureTakeTheAttemptBackOnce() {
        val undone = intArrayOf(0)
        val flow = QrScanFlow()
        flow.start("desk")
        flow.attach(token(flow), attempt(undone))
        flow.cancel { false }
        flow.leave { false }
        assertEquals(1, undone[0])
    }

    @Test
    fun aFailureTheWorkerReportedItselfIsNotUndoneAgain() {
        val undone = intArrayOf(0)
        val flow = QrScanFlow()
        flow.start("desk")
        flow.attach(token(flow), attempt(undone))
        flow.fail(token(flow), "not reached", undo = false)
        assertEquals(0, undone[0])
    }

    @Test
    fun aWorkerThatEndedBeforeItWasAttachedIsNotKept() {
        val undone = intArrayOf(0)
        val flow = QrScanFlow()
        flow.start("desk")
        val t = token(flow)
        flow.fail(t, "not reached", undo = false)
        flow.attach(t, attempt(undone))
        flow.leave { false }
        assertEquals(0, undone[0])
    }

    @Test
    fun cancelTappedAfterThePairingCompletedDoesNotUndoIt() {
        val undone = intArrayOf(0)
        val flow = QrScanFlow()
        flow.start("desk")
        flow.attach(token(flow), attempt(undone))
        assertFalse("nothing was cancelled", flow.cancel { it == "desk" })
        assertTrue(flow.state is State.Pairing)
        flow.leave { false }
        assertEquals(0, undone[0])
    }
}
