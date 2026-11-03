/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import org.desklink.mobile.QrPairing
import org.desklink.mobile.ui.QrScanFlow.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrScanFlowTest {
    private fun pairing(flow: QrScanFlow) = flow.state as State.Pairing

    @Test
    fun aRefusedCodeClosesTheAttemptWithItsMessageAndKeepsTheCameraOff() {
        val flow = QrScanFlow()
        assertTrue(flow.start("desk"))
        flow.progress("desk", QrPairing.Stage.VERIFYING)
        assertTrue(flow.fail("desk", "expired"))
        assertEquals(State.Stopped("expired"), flow.state)
        assertFalse("the code still in view must not start another attempt", flow.accepts())
    }

    @Test
    fun cancelStopsScanningUntilTheUserRescans() {
        val flow = QrScanFlow()
        flow.start("desk")
        assertTrue(flow.cancel())
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
        flow.fail("desk", "refused")
        flow.rescan()
        assertTrue(flow.start("desk"))
        assertEquals("desk", pairing(flow).id)
    }

    @Test
    fun aSecondAttemptCannotStartWhileOneRuns() {
        val flow = QrScanFlow()
        flow.start("a")
        assertFalse(flow.start("b"))
        assertEquals("a", pairing(flow).id)
    }

    @Test
    fun aTimeoutEndsOnlyTheAttemptItWasArmedFor() {
        val flow = QrScanFlow()
        flow.start("desk")
        val first = pairing(flow).token
        flow.cancel()
        flow.rescan()
        flow.start("desk")
        assertFalse("a stale timer of the cancelled attempt", flow.timedOut(first, "late"))
        assertTrue(pairing(flow).token != first)
        assertTrue(flow.timedOut(pairing(flow).token, "too slow"))
        assertEquals(State.Stopped("too slow"), flow.state)
    }

    @Test
    fun lateCallbacksOfAFinishedAttemptAreIgnored() {
        val flow = QrScanFlow()
        flow.start("desk")
        flow.cancel()
        assertFalse(flow.fail("desk", "canceled by user"))
        assertEquals(State.Stopped(null), flow.state)
        flow.progress("desk", QrPairing.Stage.VERIFYING)
        assertEquals(State.Stopped(null), flow.state)
    }

    @Test
    fun aFailureForAnotherComputerDoesNotEndThisAttempt() {
        val flow = QrScanFlow()
        flow.start("a")
        assertFalse(flow.fail("b", "x"))
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
}
