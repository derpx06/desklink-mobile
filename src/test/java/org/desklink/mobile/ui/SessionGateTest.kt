/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.ui

import org.desklink.mobile.ui.SessionGate.Step
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionGateTest {
    private fun step(
        alreadyPassed: Boolean = false,
        passwordKnown: Boolean = false,
        stored: Boolean = false,
        strongEnrolled: Boolean = true,
        phoneGate: Boolean = false,
        anyAuth: Boolean = true,
    ) = SessionGate.step(alreadyPassed, passwordKnown, stored, strongEnrolled, phoneGate, anyAuth)

    @Test
    fun onceAConnectionPassedNothingIsAskedAgain() {
        assertEquals(Step.OPEN, step(alreadyPassed = true, stored = true, phoneGate = true))
    }

    @Test
    fun aRememberedPasswordIsOpenedWithTheFingerprint() {
        assertEquals(Step.UNLOCK_STORED, step(stored = true))
        assertEquals(Step.UNLOCK_STORED, step(stored = true, phoneGate = true))
        // Already known in memory (typed this run): nothing to decrypt.
        assertEquals(Step.OPEN, step(stored = true, passwordKnown = true))
        // Fingerprints removed: the screen asks for the password itself.
        assertEquals(Step.OPEN, step(stored = true, strongEnrolled = false))
    }

    @Test
    fun withoutAPasswordThePhoneOnlyCheckIsOptional() {
        assertEquals(Step.OPEN, step())
        assertEquals(Step.PHONE_CHECK, step(phoneGate = true))
        assertEquals(Step.PHONE_CHECK, step(phoneGate = true, strongEnrolled = false))
        // No screen lock at all: nothing to check with.
        assertEquals(Step.OPEN, step(phoneGate = true, strongEnrolled = false, anyAuth = false))
    }

    @Test
    fun theStoredBlobRoundTripsAndRejectsJunk() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(24) { (it * 3).toByte() }
        val (readIv, readCiphertext) = SessionGate.decodeBlob(SessionGate.encodeBlob(iv, ciphertext))!!
        assertArrayEquals(iv, readIv)
        assertArrayEquals(ciphertext, readCiphertext)
        assertNull(SessionGate.decodeBlob(null))
        assertNull(SessionGate.decodeBlob(""))
        assertNull(SessionGate.decodeBlob("abc"))
        assertNull(SessionGate.decodeBlob(SessionGate.encodeBlob(ByteArray(8), ciphertext)))
        assertNull(SessionGate.decodeBlob(SessionGate.encodeBlob(iv, ByteArray(16))))
        assertNull(SessionGate.decodeBlob("!!!:???"))
    }

    @Test
    fun aGatePassedOnOneConnectionIsNotPassedOnTheNext() {
        SessionGate.clearPassed()
        SessionGate.markPassed("desk-1", 1001L)
        assertEquals(true, SessionGate.isPassed("desk-1", 1001L))
        // A rebuilt peer is a new session id: the gate is asked again, whatever the constant generation says.
        assertEquals(false, SessionGate.isPassed("desk-1", 1002L))
        assertEquals(false, SessionGate.isPassed("desk-2", 1001L))
        assertEquals(false, SessionGate.isPassed("desk-1", null))
        SessionGate.clearPassed()
    }

    @Test
    fun cancellingTheFingerprintStillMeetsThePhoneOnlyCheckWhenItIsOn() {
        assertEquals(Step.PHONE_CHECK, SessionGate.afterStoredFails(phoneGate = true, anyAuth = true))
        assertEquals("nothing to check with: the screen opens", Step.OPEN, SessionGate.afterStoredFails(phoneGate = true, anyAuth = false))
        assertEquals("the person never asked for the check", Step.OPEN, SessionGate.afterStoredFails(phoneGate = false, anyAuth = true))
    }
}
