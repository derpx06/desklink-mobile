/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.screen

/**
 * What the desk viewer says while its request for the computer's screen has had no answer.
 * The computer's own dialog asks the person there to allow it, which the phone cannot see,
 * so a silent wait is explained after a few seconds and given up on after a while.
 * Pure: the caller passes both times (one monotonic clock), so tests need no clock.
 */
enum class ViewWait {
    /** Just asked: the plain "Requesting live screen". */
    ASKING,

    /** No answer yet: probably waiting for someone to allow it at the computer. */
    LOOK_AT_DESK,

    /** Too long: stop the spinner and offer Try again / Close. */
    NOTHING_HAPPENED,
    ;

    companion object {
        const val LOOK_AT_DESK_AFTER_MS = 3_000L
        const val NOTHING_HAPPENED_AFTER_MS = 45_000L

        /** The monotonic clock both the request stamp and the screen read (plain JVM, so tests can run the plugin). */
        fun clockMs(): Long = System.nanoTime() / 1_000_000

        fun of(requestedAtMs: Long, nowMs: Long): ViewWait {
            val waited = nowMs - requestedAtMs
            return when {
                waited >= NOTHING_HAPPENED_AFTER_MS -> NOTHING_HAPPENED
                waited >= LOOK_AT_DESK_AFTER_MS -> LOOK_AT_DESK
                else -> ASKING
            }
        }
    }
}
