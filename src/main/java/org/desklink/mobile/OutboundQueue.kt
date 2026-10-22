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
 *
 * Nothing waits forever: an item older than [maxAgeMs] when it reaches the front (or when
 * [purgeExpired] runs, with no consumer) goes to [onExpired] instead of being sent, so a "Ring
 * desk" tapped while disconnected cannot fire hours later.
 */
class OutboundQueue<T>(
    private val interactiveCapacity: Int = INTERACTIVE_CAPACITY,
    private val bulkCapacity: Int = BULK_CAPACITY,
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onExpired: (T) -> Unit = {},
) {
    private class Entry<T>(val item: T, val at: Long)

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val interactive = ArrayDeque<Entry<T>>()
    private val bulk = ArrayDeque<Entry<T>>()
    private var closed = false

    /** False when the lane is full or the queue is closed: the caller must report it. */
    fun offer(item: T, isBulk: Boolean): Boolean = lock.withLock {
        if (closed) return false
        val lane = if (isBulk) bulk else interactive
        if (lane.size >= (if (isBulk) bulkCapacity else interactiveCapacity)) return false
        lane.addLast(Entry(item, clock()))
        notEmpty.signal()
        true
    }

    /** Blocks for the next fresh item, interactive first; null once closed and empty. */
    fun take(): T? {
        while (true) {
            val entry: Entry<T> = lock.withLock {
                while (interactive.isEmpty() && bulk.isEmpty()) {
                    if (closed) return null
                    notEmpty.await()
                }
                if (interactive.isNotEmpty()) interactive.removeFirst() else bulk.removeFirst()
            }
            if (clock() - entry.at <= maxAgeMs) return entry.item
            onExpired(entry.item)
        }
    }

    /** Removes and reports every item older than the age limit; for the time nobody is taking. */
    fun purgeExpired(): Int {
        val expired = lock.withLock {
            val now = clock()
            (interactive + bulk).filter { now - it.at > maxAgeMs }.also { old ->
                interactive.removeAll(old.toSet())
                bulk.removeAll(old.toSet())
            }
        }
        expired.forEach { onExpired(it.item) }
        return expired.size
    }

    /** Stops [take] and refuses new items; what is still queued is returned so it can be reported. */
    fun close(): List<T> = lock.withLock {
        closed = true
        notEmpty.signalAll()
        (interactive + bulk).map { it.item }.also { interactive.clear(); bulk.clear() }
    }

    fun reopen() = lock.withLock { closed = false }

    fun size(): Int = lock.withLock { interactive.size + bulk.size }

    companion object {
        const val INTERACTIVE_CAPACITY = 64
        const val BULK_CAPACITY = 256

        /** Matches the offline feature queue and the desk. */
        const val MAX_AGE_MS = 30_000L
    }
}
