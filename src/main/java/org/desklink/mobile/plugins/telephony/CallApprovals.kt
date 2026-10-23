/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.telephony

import android.os.SystemClock
import java.security.SecureRandom

/**
 * A call the desk asked for waits here until the person taps Allow on the phone. A request is a one-time nonce that lives a
 * minute, bound to one desk and one number; a desk may ask only a few times a minute, and asking again replaces what it
 * asked before.
 */
class CallApprovals(private val now: () -> Long = SystemClock::elapsedRealtime) {
    data class Pending(val nonce: String, val deviceId: String, val number: String, val expiresAt: Long)

    private val pending = LinkedHashMap<String, Pending>()
    private val asks = HashMap<String, ArrayDeque<Long>>()
    private val random = SecureRandom()

    /** A new request for [number] from [deviceId], or null when that desk asks too often. */
    @Synchronized
    fun ask(deviceId: String, number: String): Pending? {
        val t = now()
        val recent = asks.getOrPut(deviceId) { ArrayDeque() }
        while (recent.isNotEmpty() && t - recent.first() >= WINDOW_MS) recent.removeFirst()
        if (recent.size >= MAX_ASKS_PER_WINDOW) return null
        recent.addLast(t)
        pending.values.removeAll { it.deviceId == deviceId || it.expiresAt <= t }
        val nonce = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        return Pending(nonce, deviceId, number, t + LIFETIME_MS).also { pending[nonce] = it }
    }

    /** The request [nonce] names, once: used up by this call, null if unknown, used or expired. */
    @Synchronized
    fun take(nonce: String?): Pending? {
        val found = pending.remove(nonce ?: return null) ?: return null
        return found.takeIf { it.expiresAt > now() }
    }

    /** Dropped without placing anything (Deny). */
    @Synchronized
    fun discard(nonce: String?) { pending.remove(nonce ?: return) }

    companion object {
        const val LIFETIME_MS = 60_000L
        const val WINDOW_MS = 60_000L
        const val MAX_ASKS_PER_WINDOW = 5
    }
}
