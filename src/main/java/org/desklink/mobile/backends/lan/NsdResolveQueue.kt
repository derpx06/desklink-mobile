/*
 * SPDX-FileCopyrightText: 2023 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.backends.lan

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.LinkedList

/**
 * Resolves advertised services one at a time (NsdManager allows one resolve at once). Any host on the network can advertise
 * as many names as it likes, so the line is bounded, and a resolve that never calls back does not wedge it for good.
 */
class NsdResolveQueue(
    private val resolver: (NsdServiceInfo, NsdManager.ResolveListener) -> Unit,
    private val now: () -> Long = System::nanoTime,
) {
    constructor(nsdManager: NsdManager) : this({ info, listener -> nsdManager.resolveService(info, listener) })

    val LOG_TAG: String = "NsdResolveQueue"

    private val lock = Any()

    private data class PendingResolve(val serviceInfo: NsdServiceInfo, val listener: NsdManager.ResolveListener)
    private val resolveRequests = LinkedList<PendingResolve>()
    private var headStartedAt = 0L

    fun resolveOrEnqueue(serviceInfo: NsdServiceInfo, listener: NsdManager.ResolveListener) {
        synchronized(lock) {
            // The head has had its chance: the system sometimes never answers a resolve, and everything behind it would wait for ever.
            if (resolveRequests.isNotEmpty() && now() - headStartedAt > RESOLVE_GIVES_UP_AFTER_NANOS) {
                Log.w(LOG_TAG, "A resolve got no answer; moving on")
                resolveRequests.removeFirst()
                resolveNextRequest()
            }
            if (resolveRequests.any { r -> serviceInfo.serviceName == r.serviceInfo.serviceName }) {
                Log.i(LOG_TAG, "Not enqueuing a new resolve request for the same service: " + serviceInfo.serviceName)
                return
            }
            if (resolveRequests.size >= MAX_PENDING) {
                // Dropped, not queued: a service that is still there is found again by the next discovery.
                Log.w(LOG_TAG, "Too many services waiting to be resolved; ignoring one")
                return
            }
            resolveRequests.addLast(PendingResolve(serviceInfo, ListenerWrapper(listener)))
            if (resolveRequests.size == 1) {
                resolveNextRequest()
            }
        }
    }

    private inner class ListenerWrapper(private val listener: NsdManager.ResolveListener) : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            listener.onResolveFailed(serviceInfo, errorCode)
            postResolve()
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            listener.onServiceResolved(serviceInfo)
            postResolve()
        }

        /** This request is done: out of the line by identity, so a late answer to one given up on removes nothing else. */
        private fun postResolve() {
            synchronized(lock) {
                val wasHead = resolveRequests.firstOrNull()?.listener === this
                resolveRequests.removeAll { it.listener === this }
                if (wasHead) resolveNextRequest()
            }
        }
    }

    private fun resolveNextRequest() {
        if (resolveRequests.isNotEmpty()) {
            val request = resolveRequests.first()
            headStartedAt = now()
            resolver(request.serviceInfo, request.listener)
        }
    }

    /** How many are waiting (the one being resolved included). */
    internal fun pending(): Int = synchronized(lock) { resolveRequests.size }

    companion object {
        const val MAX_PENDING = 32
        private const val RESOLVE_GIVES_UP_AFTER_NANOS = 30_000_000_000L
    }
}
