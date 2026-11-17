/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.backends.lan

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every port of the range taken: the provider starts without throwing (it used to throw out of the foreground service's
 * start), does not announce a port of 0 over mDNS, and tries again, announcing, on the next network change.
 */
@RunWith(AndroidJUnit4::class)
class ListenerFailureTest {
    /** Takes every port of the range that is free; a port another process holds counts as taken too. */
    private fun takeTheWholeRange(): List<ServerSocket> =
        (LanLinkProvider.MIN_PORT..LanLinkProvider.MAX_PORT).mapNotNull {
            try { ServerSocket(it) } catch (_: IOException) { null }
        }

    @Test
    fun aRangeWithNoFreePortLeavesTheProviderUpWithoutAnnouncingAndARetryAnnounces() {
        val provider = LanLinkProvider(ApplicationProvider.getApplicationContext<Application>())
        val mdns = provider.mdnsDiscoveryForTest()
        val held = takeTheWholeRange()
        try {
            provider.onStart() // must not throw
            assertFalse("no listener", provider.isTcpListenerUp)
            assertEquals("a port of 0 is never offered to NsdManager", 0, mdns.announceAttempts)
        } finally {
            held.forEach { it.close() }
        }
        provider.onNetworkChange(null) // the retry
        try {
            assertTrue("the retry found a port", provider.isTcpListenerUp)
            assertTrue("and announced it", mdns.announceAttempts >= 1 || !org.desklink.mobile.helpers.TrustedNetworkHelper.isTrustedNetwork(ApplicationProvider.getApplicationContext<Application>()))
        } finally {
            provider.onStop()
        }
    }
}
