/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

/**
 * When this phone tells the desk's Phone Screen window whether its keys reached a text field (`input-focus`): a refusal
 * at once, the same one again at most every [everyMs] while keys keep failing, and success only after a refusal (to
 * clear the desk's words). Never per key.
 */
class TypingReports(private val everyMs: Long = 3_000) {
    private var last: String? = null
    private var lastAt = Long.MIN_VALUE

    /** Whether to report [refusal] (null: typed) now. */
    @Synchronized
    fun shouldSend(refusal: String?, now: Long): Boolean {
        val send = if (refusal == null) last != null else refusal != last || now - lastAt >= everyMs
        if (send) {
            last = refusal
            lastAt = now
        }
        return send
    }
}
