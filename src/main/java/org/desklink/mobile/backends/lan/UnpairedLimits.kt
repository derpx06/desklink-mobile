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
    private class Seen(val address: InetAddress?, var lastSeen: Long)

    private val seen = LinkedHashMap<String, Seen>()

    /** Whether the unpaired [deviceId] speaking from [address] may be taken up. */
    @Synchronized
    fun admit(deviceId: String, address: InetAddress?): Boolean {
        val t = now()
        seen.values.removeAll { t - it.lastSeen >= FORGET_AFTER_MS }
        seen[deviceId]?.let { it.lastSeen = t; return true }
        if (seen.size >= MAX_UNPAIRED_DEVICES) return false
        if (address != null && seen.values.count { it.address == address } >= MAX_PER_ADDRESS) return false
        seen[deviceId] = Seen(address, t)
        return true
    }

    /** It paired (or was forgotten): no longer counted. */
    @Synchronized
    fun release(deviceId: String) {
        seen.remove(deviceId)
    }

    companion object {
        const val MAX_UNPAIRED_DEVICES = 42
        const val MAX_PER_ADDRESS = 2
        const val FORGET_AFTER_MS = 10 * 60_000L

        /** A line from a paired peer (signaling, heartbeats, pairing): generous. */
        const val MAX_PAIRED_LINE = 4 * 1024 * 1024

        /** A line from a peer that is not paired: identity, pairing and nothing else. */
        const val MAX_UNPAIRED_LINE = 256 * 1024

        /** An identity packet (UDP or the first TCP line). */
        const val MAX_IDENTITY_PACKET = 64 * 1024
    }
}
