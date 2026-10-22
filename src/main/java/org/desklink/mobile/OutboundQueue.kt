/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The phone's outbound packet queue, in two lanes served by one sender. Interactive packets (a tap,
 * a lock, a notification, a heartbeat-adjacent state) always go before bulk ones (contacts, file
 * share requests, manifests), so a long batch can never sit in front of something the person is
 * waiting for. A full lane refuses the new item and says so — [offer] returns false, and the caller
 * reports it — rather than dropping silently.
 */
class OutboundQueue<T>(private val interactiveCapacity: Int = INTERACTIVE_CAPACITY, private val bulkCapacity: Int = BULK_CAPACITY) {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val interactive = ArrayDeque<T>()
    private val bulk = ArrayDeque<T>()
    private var closed = false

    /** False when the lane is full or the queue is closed: the caller must report it. */
    fun offer(item: T, isBulk: Boolean): Boolean = lock.withLock {
        if (closed) return false
        val lane = if (isBulk) bulk else interactive
        if (lane.size >= (if (isBulk) bulkCapacity else interactiveCapacity)) return false
        lane.addLast(item)
        notEmpty.signal()
        true
    }

    /** Blocks for the next item, interactive first; null once closed and empty. */
    fun take(): T? = lock.withLock {
        while (interactive.isEmpty() && bulk.isEmpty()) {
            if (closed) return null
            notEmpty.await()
        }
        if (interactive.isNotEmpty()) interactive.removeFirst() else bulk.removeFirst()
    }

    /** Stops [take] and refuses new items; what is still queued is returned so it can be reported. */
    fun close(): List<T> = lock.withLock {
        closed = true
        notEmpty.signalAll()
        (interactive + bulk).also { interactive.clear(); bulk.clear() }
    }

    fun reopen() = lock.withLock { closed = false }

    fun size(): Int = lock.withLock { interactive.size + bulk.size }

    companion object {
        const val INTERACTIVE_CAPACITY = 64
        const val BULK_CAPACITY = 256
    }
}
