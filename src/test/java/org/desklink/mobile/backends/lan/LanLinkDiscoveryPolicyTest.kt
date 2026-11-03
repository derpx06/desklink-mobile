/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.backends.lan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanLinkDiscoveryPolicyTest {
    @Test
    fun periodicDiscoveryDoesNotReplaceAnActiveBootstrapLink() {
        assertFalse(LanLinkProvider.shouldOpenDiscoveredBootstrap(true))
        assertTrue(LanLinkProvider.shouldOpenDiscoveredBootstrap(false))
    }

    @Test
    fun anAdvertisedServiceIsAnsweredOnlyOnATrustedNetwork() {
        assertTrue(MdnsDiscovery.mayAnswerService(true))
        assertFalse(MdnsDiscovery.mayAnswerService(false))
    }

    /** Custom hosts and the QR hint go through sendUdpIdentityPacket on any network: only the mDNS reply is gated. */
    @Test
    fun theTrustGateIsOnTheMdnsReplyNotOnEveryIdentitySend() {
        val send = java.io.File("src/main/java/org/desklink/mobile/backends/lan/LanLinkProvider.java").readText()
            .substringAfter("public void sendUdpIdentityPacket(").substringBefore("for (InetAddress ip : ipList)")
        assertFalse("sendUdpIdentityPacket must not refuse custom hosts or the QR hint", send.contains("isTrustedNetwork"))
        val mdns = java.io.File("src/main/java/org/desklink/mobile/backends/lan/MdnsDiscovery.kt").readText()
        assertTrue(mdns.substringAfter("override fun onServiceResolved").contains("mayAnswerService"))
    }
}
