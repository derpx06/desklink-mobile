/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.backends.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No relay address means no relay connection, whatever the switch says. */
class RelayGateTest {
    @Test
    fun runsOnlyWithAnAddressTheSwitchOnAndSomeonePaired() {
        assertTrue(RelaySettings.shouldRun(enabled = true, url = "wss://relay.example.org/v1", hasPairedDevices = true))
        assertFalse(RelaySettings.shouldRun(enabled = true, url = "", hasPairedDevices = true))
        assertFalse(RelaySettings.shouldRun(enabled = true, url = "   ", hasPairedDevices = true))
        assertFalse(RelaySettings.shouldRun(enabled = false, url = "wss://relay.example.org/v1", hasPairedDevices = true))
        assertFalse(RelaySettings.shouldRun(enabled = true, url = "wss://relay.example.org/v1", hasPairedDevices = false))
    }
}
