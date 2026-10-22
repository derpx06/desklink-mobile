/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.backends.lan

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.helpers.TrustedDevices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
class LanAddressPolicyTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun nothingIsAnnouncedToOrDialedOnAnUntrustedNetwork() {
        val id = "0123456789abcdef0123456789abcdef"
        TrustedDevices.addTrustedDevice(context, id)
        LastLanAddress.remember(context, id, InetAddress.getByName("192.168.1.20"), 1716)
        val backoff = DialBackoff()
        assertEquals(1, LastLanAddress.targets(context, emptySet(), trustedNetwork = true).size)
        assertEquals(1, LastLanAddress.dialTargets(context, emptySet(), backoff, 0, trustedNetwork = true).size)
        assertTrue(LastLanAddress.targets(context, emptySet(), trustedNetwork = false).isEmpty())
        assertTrue(LastLanAddress.dialTargets(context, emptySet(), backoff, 0, trustedNetwork = false).isEmpty())
    }

    @Test
    fun anAddressOfADeviceThatIsNotTrustedIsNeverRemembered() {
        val stranger = "fedcba9876543210fedcba9876543210"
        LastLanAddress.remember(context, stranger, InetAddress.getByName("192.168.1.66"), 1716)
        assertTrue(LastLanAddress.hostsOf(context, stranger).isEmpty())
    }
}
