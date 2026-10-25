/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.backends.lan

import android.os.SystemClock
import java.net.InetAddress

/**
 * What a stranger on the LAN may cost this phone. Devices that are not paired are limited the way the computer limits them:
 * at most [MAX_UNPAIRED_DEVICES] different unpaired ids at a time (each would get a `Device`, a session and a link), and at
 * most [MAX_PER_ADDRESS] of them from one address. An id is forgotten after [FORGET_AFTER_MS] without a sign.
 */
class UnpairedLimits(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private class Seen(val address: InetAddress?, var lastSeen: Long, var reservedUntil: Long = 0L)

    private val seen = LinkedHashMap<String, Seen>()

    /** Whether the unpaired [deviceId] speaking from [address] may be taken up. */
    @Synchronized
    fun admit(deviceId: String, address: InetAddress?): Boolean {
        val t = now()
        seen.values.removeAll { t - it.lastSeen >= FORGET_AFTER_MS }
        seen[deviceId]?.let { it.lastSeen = t; return true }
        if (address != null && seen.values.count { it.address == address } >= MAX_PER_ADDRESS) return false
        if (seen.size >= MAX_UNPAIRED_DEVICES) {
            // Full: the id idle the longest (and not one being paired) makes room, so a crowd of made-up ids cannot keep the real
            // computer out; a crowd that is all fresh still cannot grow past the cap.
            val oldest = seen.entries.filter { t >= it.value.reservedUntil && t - it.value.lastSeen >= IDLE_MS }.minByOrNull { it.value.lastSeen }
                ?: return false
            seen.remove(oldest.key)
        }
        seen[deviceId] = Seen(address, t)
        return true
    }

    /**
     * The person is pairing with [deviceId] now (a scanned code, a tapped computer): it is admitted beyond the cap (a few extra
     * places), kept for a while, and never the one evicted.
     */
    @Synchronized
    fun reserve(deviceId: String) {
        val t = now()
        seen.values.removeAll { t - it.lastSeen >= FORGET_AFTER_MS && t >= it.reservedUntil }
        val entry = seen[deviceId] ?: run {
            if (seen.count { it.value.reservedUntil > t } >= RESERVED_SLOTS) return
            Seen(null, t).also { seen[deviceId] = it }
        }
        entry.lastSeen = t
        entry.reservedUntil = t + RESERVE_MS
    }

    /** It paired (or was forgotten): no longer counted. */
    @Synchronized
    fun release(deviceId: String) {
        seen.remove(deviceId)
    }

    companion object {
        const val MAX_UNPAIRED_DEVICES = 42
        const val MAX_PER_ADDRESS = 2

        /** An id quiet this long may be evicted when the table is full. */
        const val IDLE_MS = 30_000L
        const val RESERVED_SLOTS = 4
        const val RESERVE_MS = 5 * 60_000L

        /** The one table, shared by the LAN provider (which admits) and the pairing flows (which reserve). */
        @JvmField
        val shared = UnpairedLimits()
        const val FORGET_AFTER_MS = 10 * 60_000L

        /** A line from a paired peer (signaling, heartbeats, pairing): generous. */
        const val MAX_PAIRED_LINE = 4 * 1024 * 1024

        /** A line from a peer that is not paired: identity, pairing and nothing else. */
        const val MAX_UNPAIRED_LINE = 256 * 1024

        /** An identity packet (UDP or the first TCP line). */
        const val MAX_IDENTITY_PACKET = 64 * 1024
    }
}
