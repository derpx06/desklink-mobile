/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import java.util.ArrayDeque

/**
 * Small bootstrap queue used while a newly created Device is being attached
 * to its WebRTC coordinator. It prevents signed signaling that arrives in
 * that narrow window from being discarded, while keeping memory bounded.
 */
internal class BoundedPendingQueue<T>(private val maximumSize: Int) {
    private val items = ArrayDeque<T>()

    init {
        require(maximumSize > 0) { "Pending queue size must be positive" }
    }

    @Synchronized
    fun offer(item: T): T? {
        val evicted = if (items.size == maximumSize) items.removeFirst() else null
        items.addLast(item)
        return evicted
    }

    /**
     * Like [offer], but when full it evicts the oldest item [disposable] allows (a status snapshot
     * that a newer one replaces) before it evicts anything else, so something the person asked for
     * is the last to go. Returns the evicted item, if any.
     */
    @Synchronized
    fun offerPreferring(item: T, disposable: (T) -> Boolean): T? {
        val evicted = if (items.size == maximumSize) {
            (items.firstOrNull(disposable) ?: items.first).also { items.remove(it) }
        } else null
        items.addLast(item)
        return evicted
    }

    @Synchronized
    fun isEmpty(): Boolean = items.isEmpty()

    @Synchronized
    fun drain(): List<T> = buildList(items.size) {
        while (items.isNotEmpty()) add(items.removeFirst())
    }

    /** Removes and returns, in order, every item matching [predicate]. */
    @Synchronized
    fun removeIf(predicate: (T) -> Boolean): List<T> {
        val removed = items.filter(predicate)
        items.removeAll(removed.toSet())
        return removed
    }
}
