/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.lifecycle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive

/** Serializes device lifecycle work that must never run from an Android UI callback. */
internal class DeviceUnpairCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + org.desklink.mobile.helpers.Guarded.handler("lifecycle")),
    private val onFailure: (String, Throwable) -> Unit = { operation, error ->
        android.util.Log.e("DeskLink/DeviceLifecycle", operation, error)
    },
) {
    /** One FIFO channel and one consumer: work runs in exactly the order it was queued (launching a coroutine per item did not). */
    private class Item(val operation: String, val work: suspend () -> Unit, val done: CompletableJob)

    private val queue = Channel<Item>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (item in queue) {
                if (item.done.isCancelled) continue
                try {
                    item.work()
                } catch (error: CancellationException) {
                    item.done.cancel()
                    if (!scope.isActive) throw error
                    continue
                } catch (error: Throwable) {
                    onFailure(item.operation, error)
                }
                item.done.complete()
            }
        }
    }
    private val stateLock = Any()
    private var lastUnpairGeneration: Long? = null

    /** Schedules one cleanup for a pairing generation; duplicates are ignored. */
    fun beginUnpair(generation: Long, cleanup: suspend () -> Unit): Boolean {
        synchronized(stateLock) {
            if (lastUnpairGeneration == generation) return false
            lastUnpairGeneration = generation
        }
        enqueue("unpair cleanup generation $generation", cleanup)
        return true
    }

    /** Queues lifecycle work behind any active unpair cleanup. */
    fun enqueue(operation: String, work: suspend () -> Unit): Job {
        val done = Job()
        if (queue.trySend(Item(operation, work, done)).isFailure) done.cancel()
        return done
    }

    fun close() {
        queue.close()
        scope.cancel()
    }
}
