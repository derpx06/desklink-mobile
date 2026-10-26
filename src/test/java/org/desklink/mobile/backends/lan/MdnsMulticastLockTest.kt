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

    @Test
    fun theLockStaysHeldWhileAnyPairedComputerHasNoLink() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val discovery = MdnsDiscovery(app, mockk(relaxed = true))
        discovery.startDiscovering()
        discovery.updateLinkState { false } // one of two computers is linked, the other is not
        assertTrue("held: the second computer's discovery needs it", discovery.lockHeld)
        discovery.updateLinkState { true }
        assertFalse(discovery.lockHeld)
        discovery.updateLinkState { false } // the last link dropped
        assertTrue(discovery.lockHeld)
        discovery.stopDiscovering()
    }

    @Test
    fun aComputerNotSeenForTenMinutesDoesNotHoldTheLock() {
        val now = 100 * 60_000L
        val seen = mapOf("near" to now - 60_000, "gone" to now - 3 * 60 * 60_000L)
        // "near" is expected and unlinked: held. "gone" alone (not seen for hours) is not expected: the lock may go.
        assertFalse(MulticastPolicy.allExpectedLinked(listOf("a", "near"), setOf("a"), { seen[it] ?: 0L }, now))
        assertTrue(MulticastPolicy.allExpectedLinked(listOf("a", "gone"), setOf("a"), { seen[it] ?: 0L }, now))
        assertTrue(MulticastPolicy.allExpectedLinked(listOf("a", "near"), setOf("a", "near"), { seen[it] ?: 0L }, now))
        assertFalse("nothing linked at all: looking", MulticastPolicy.allExpectedLinked(listOf("gone"), emptySet(), { 0L }, now))
    }
}
