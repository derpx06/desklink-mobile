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

    @Synchronized
    fun drain(): List<T> = buildList(items.size) {
        while (items.isNotEmpty()) add(items.removeFirst())
    }
}
