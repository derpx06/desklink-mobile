/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.backends.lan

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class NsdResolveQueueTest {
    private fun service(name: String) = mockk<NsdServiceInfo>(relaxed = true).also { every { it.serviceName } returns name }
    private val quiet = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) = Unit
    }

    @Test
    fun hostsAdvertisingManyNamesCannotGrowTheLineWithoutBound() {
        val asked = mutableListOf<NsdManager.ResolveListener>()
        val queue = NsdResolveQueue({ _, listener -> asked += listener })
        repeat(1000) { queue.resolveOrEnqueue(service("n$it"), quiet) }
        assertEquals(NsdResolveQueue.MAX_PENDING, queue.pending())
        assertEquals("only the head is being resolved", 1, asked.size)
    }

    @Test
    fun anAnswerMovesTheLineOnAndAResolveWithNoAnswerDoesNotWedgeItForGood() {
        val asked = mutableListOf<Pair<NsdServiceInfo, NsdManager.ResolveListener>>()
        var clock = 0L
        val queue = NsdResolveQueue({ info, listener -> asked += info to listener }, { clock })
        val (a, b, c) = listOf(service("a"), service("b"), service("c"))
        queue.resolveOrEnqueue(a, quiet)
        queue.resolveOrEnqueue(b, quiet)
        asked[0].second.onServiceResolved(a)
        assertEquals("b is next", "b", asked[1].first.serviceName)
        // b never answers. Half a minute on, the next service to turn up moves the line past it.
        clock += 31_000_000_000L
        queue.resolveOrEnqueue(c, quiet)
        assertEquals("c", asked[2].first.serviceName)
        // b's very late answer removes nothing that is not b.
        asked[1].second.onServiceResolved(b)
        assertEquals(1, queue.pending())
        asked[2].second.onResolveFailed(c, 0)
        assertEquals(0, queue.pending())
    }

    @Test
    fun theSameServiceIsNotQueuedTwice() {
        val queue = NsdResolveQueue({ _, _ -> })
        queue.resolveOrEnqueue(service("a"), quiet)
        queue.resolveOrEnqueue(service("a"), quiet)
        assertEquals(1, queue.pending())
    }
}
