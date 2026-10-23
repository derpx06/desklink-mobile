/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The proof of the session password is bound to the session id, which differs for every peer; `generation` is a constant. */
class SessionPasswordBindingTest {
    @Test
    fun twoAttemptsOfOneDeviceHaveDifferentSessionIdsButTheSameGeneration() {
        val a = WebRtcWireBinding.fromAttempt("phone", "desk", "11111111-2222-3333-4444-555555555555")
        val b = WebRtcWireBinding.fromAttempt("phone", "desk", "99999999-2222-3333-4444-555555555555")
        assertNotEquals("the binding a desk keys the unlock on", a.sessionId, b.sessionId)
        assertEquals("generation is constant and must never be a security input", a.generation, b.generation)
    }

    @Test
    fun aDesksPasswordDenialsAreRecognisedOnEveryConnection() {
        assertEquals(SessionPasswordDenial.Required, SessionPassword.denial("password-required"))
        assertEquals(SessionPasswordDenial.Wrong, SessionPassword.denial("password-wrong"))
        assertEquals(SessionPasswordDenial.Locked(30), SessionPassword.denial("password-locked:30"))
    }
}
