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
}
