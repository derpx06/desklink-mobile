/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.backends.lan

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MdnsMulticastLockTest {
    @Test
    fun theMulticastLockIsHeldToFindAComputerAndLetGoWhileLinked() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val discovery = MdnsDiscovery(app, mockk(relaxed = true))
        discovery.startDiscovering()
        assertTrue("held while looking", discovery.lockHeld)
        discovery.setLinkUp(true)
        assertFalse("not held while a link is up", discovery.lockHeld)
        discovery.setLinkUp(false)
        assertTrue("held again when the link is gone", discovery.lockHeld)
        discovery.stopDiscovering()
        assertFalse(discovery.lockHeld)
    }
}
