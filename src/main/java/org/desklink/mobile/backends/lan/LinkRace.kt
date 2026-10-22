/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.backends.lan

/**
 * An inbound dial and a redial that land within [WINDOW_MS] of each other are one reconnection, not
 * two: keeping the newest each time made both sides close the socket the other had just kept (a
 * generation churn). The tie is broken by who dialed: the link dialed by the lower device id (the
 * deterministic WebRTC initiator) survives on both sides, so both sides keep the same socket. A
 * second link dialed by the same side is a plain replacement, as before.
 */
class LinkRace {
    private class Kept(val dialer: String, val at: Long)

    private val kept = HashMap<String, Kept>()

    /**
     * A link to [peerId] was just authenticated, dialed by us ([weDialed]) or by the peer. Returns
     * whether it becomes the link; when false the caller closes its socket.
     */
    @Synchronized
    fun arrive(peerId: String, myId: String, weDialed: Boolean, now: Long): Boolean {
        val dialer = if (weDialed) myId else peerId
        val existing = kept[peerId]
        val racing = existing != null && now - existing.at < WINDOW_MS && existing.dialer != dialer
        if (racing && dialer > existing!!.dialer) return false
        kept[peerId] = Kept(dialer, now)
        return true
    }

    /** The link to [peerId] is gone: the next one is never a race. */
    @Synchronized
    fun forget(peerId: String) {
        kept.remove(peerId)
    }

    companion object {
        const val WINDOW_MS = 1_000L
    }
}
