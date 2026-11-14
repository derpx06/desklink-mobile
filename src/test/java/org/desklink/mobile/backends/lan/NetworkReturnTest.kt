/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.backends.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException

/** F20: back on Wi-Fi after a short drop, the phone dials its desks at once. */
class NetworkReturnTest {
    private val noRoute = ConnectException("failed to connect to /192.168.1.13 (port 1716): connect failed: ENETUNREACH (Network is unreachable)")

    @Test
    fun aDialWithNoRouteYetIsRetriedUntilTheRouteWorks() {
        val sleeps = mutableListOf<Long>()
        var tries = 0
        val result = NetworkReturn.connect({ sleeps += it }) {
            if (++tries < 3) throw noRoute
            "linked"
        }
        assertEquals("linked", result)
        assertEquals(listOf(250L, 250L), sleeps)
    }

    @Test
    fun anyOtherFailureIsNotRetried() {
        val sleeps = mutableListOf<Long>()
        try {
            NetworkReturn.connect({ sleeps += it }) { throw SocketTimeoutException("connect timed out") }
            fail("a timeout is the desk's answer, not a missing route")
        } catch (expected: SocketTimeoutException) {
        }
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun aRouteThatNeverComesGivesUpAfterAboutTwoSeconds() {
        val sleeps = mutableListOf<Long>()
        var tries = 0
        try {
            NetworkReturn.connect({ sleeps += it }) { tries++; throw noRoute }
            fail("gave up")
        } catch (expected: ConnectException) {
        }
        assertEquals(NetworkReturn.NO_ROUTE_RETRY_MS.size + 1, tries)
        assertEquals(2_000L, sleeps.sum())
    }

    @Test
    fun aNoRouteCauseIsRecognisedThroughItsWrapper() {
        assertTrue(NetworkReturn.isNoRoute(java.io.IOException("sendto failed", noRoute)))
        assertFalse(NetworkReturn.isNoRoute(ConnectException("Connection refused")))
    }

    @Test
    fun aNetworkComingBackIsNeverThrottled() {
        assertFalse(NetworkReturn.burstAllowed(now = 100, last = 50, force = false))
        assertTrue(NetworkReturn.burstAllowed(now = 100, last = 50, force = true))
        assertTrue(NetworkReturn.burstAllowed(now = 300, last = 50, force = false))
    }

    /** The wiring has no JVM seam (sockets, NSD, ConnectivityManager): pinned by source. */
    @Test
    fun aNetworkComingBackDialsTheDesksOnThatNetwork() {
        val service = File("src/main/java/org/desklink/mobile/BackgroundService.kt").readText()
        assertTrue(
            "onAvailable must dial, not only re-announce",
            service.substringAfter("override fun onAvailable(network: Network) {").substringBefore("override fun onLost")
                .contains("networkAvailable(network)"),
        )
        val lan = File("src/main/java/org/desklink/mobile/backends/lan/LanLinkProvider.java").readText()
        val available = lan.substringAfter("public void onNetworkAvailable(").substringBefore("\n    }\n")
        assertTrue("backoff from the outage is forgotten", available.contains("dialBackoff.reset()"))
        assertTrue("the burst is not throttled", available.contains("announceBurst(network, false, true)"))
        val refresh = lan.substringAfter("public void refreshDiscovery(").substringBefore("\n    }\n")
        assertTrue("a re-announce does not replace the network burst", refresh.contains("networkBurstUntil"))
        val dial = lan.substringAfter("private void dialKnownAddresses(").substringBefore("/** What the QR code says")
        assertTrue("dials on the network that came back", dial.contains("network.getSocketFactory()"))
        assertTrue("a missing route is retried", dial.contains("NetworkReturn.connect("))
    }
}
