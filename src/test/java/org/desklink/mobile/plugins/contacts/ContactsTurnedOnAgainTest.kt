/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.contacts

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/** Sharing off then on again on one link: the desk deleted its copy, so the book is sent again whatever revision it remembers. */
@RunWith(RobolectricTestRunner::class)
class ContactsTurnedOnAgainTest {
    @Test
    fun turningSharingOnWhileConnectedAlwaysSendsTheSnapshotNotThroughTheDigest() {
        val sent = CopyOnWriteArrayList<NetworkPacket>()
        val device = mockk<Device>(relaxed = true)
        every { device.deviceId } returns "desk-1"
        every { device.hasActiveWebRtcFeatureTransport } returns true
        // The digest says the desk already holds this book: it would be skipped.
        every { device.sendSnapshot(any(), any(), any(), any()) } returns false
        every { device.sendPacket(any()) } answers { sent += firstArg<NetworkPacket>(); true }
        val plugin = ContactsPlugin().also { it.setContext(ApplicationProvider.getApplicationContext<Application>(), device) }
        plugin.onCreate()
        val deadline = System.currentTimeMillis() + 5_000
        while (sent.none { it.type == DeskLinkProtocol.PACKET_TYPE_CONTACTS_RESPONSE_UIDS_TIMESTAMPS } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("a snapshot went out", sent.any { it.type == DeskLinkProtocol.PACKET_TYPE_CONTACTS_RESPONSE_UIDS_TIMESTAMPS })
        plugin.onDestroy()
    }
}
