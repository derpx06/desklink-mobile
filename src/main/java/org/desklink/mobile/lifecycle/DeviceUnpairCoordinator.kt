/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.lifecycle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes device lifecycle work that must never run from an Android UI callback. */
internal class DeviceUnpairCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onFailure: (String, Throwable) -> Unit = { operation, error ->
        android.util.Log.e("DeskLink/DeviceLifecycle", operation, error)
    },
) {
    private val serial = Mutex()
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
    fun enqueue(operation: String, work: suspend () -> Unit): Job = scope.launch {
        serial.withLock {
            try {
                work()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onFailure(operation, error)
            }
        }
    }

    fun close() {
        scope.cancel()
    }
}
