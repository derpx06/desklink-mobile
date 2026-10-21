/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

/**
 * The desktop's optional session password, which guards screen view, control and terminals.
 *
 * Mirror of `DeskLink/src/device_links/session_lock.rs`. The phone proves it once per WebRTC
 * connection by sending it as `password` on the requests the desktop checks; it is held in memory
 * only, per device, and forgotten with the connection.
 */
object SessionPassword {
    const val MAX_BYTES = 256

    const val REQUIRED = "password-required"
    const val WRONG = "password-wrong"
    const val LOCKED = "password-locked"

    fun fits(password: String): Boolean = password.toByteArray(Charsets.UTF_8).size <= MAX_BYTES

    /** The password problem a desktop denial or terminal error names, or null for any other reason. */
    fun denial(reason: String?): SessionPasswordDenial? = when {
        reason == REQUIRED -> SessionPasswordDenial.Required
        reason == WRONG -> SessionPasswordDenial.Wrong
        reason != null && reason.startsWith("$LOCKED:") ->
            reason.substringAfter(':').toLongOrNull()?.takeIf { it >= 0 }?.let(SessionPasswordDenial::Locked)
        else -> null
    }
}

sealed interface SessionPasswordDenial {
    /** None was offered: ask for it. */
    data object Required : SessionPasswordDenial

    /** The one offered was wrong: ask again, saying so. */
    data object Wrong : SessionPasswordDenial

    /** Too many wrong guesses: no prompt until [seconds] have passed. */
    data class Locked(val seconds: Long) : SessionPasswordDenial
}
