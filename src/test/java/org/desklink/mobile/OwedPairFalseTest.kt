/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.desklink.mobile.helpers.OwedPairFalse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** An unpair that could not be told is owed to the computer and goes out with its next link, once. */
@RunWith(RobolectricTestRunner::class)
class OwedPairFalseTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val noop = mockk<PairingHandler.PairingCallback>(relaxed = true)

    private fun device(id: String, link: Boolean) = mockk<Device>(relaxed = true).also {
        every { it.context } returns app
        every { it.deviceId } returns id
        every { it.hasBootstrapLink } returns link
    }

    @Test
    fun theDebtIsKeptAndClearedPerComputer() {
        OwedPairFalse.owe(app, "a")
        OwedPairFalse.owe(app, "b")
        assertTrue(OwedPairFalse.owes(app, "a"))
        OwedPairFalse.clear(app, "a")
        assertFalse(OwedPairFalse.owes(app, "a"))
        assertTrue(OwedPairFalse.owes(app, "b"))
        OwedPairFalse.clear(app, "b")
    }

    @Test
    fun unpairingWithoutALinkOwesThePairFalse() {
        val d = device("desk1", link = false)
        PairingHandler(d, noop, PairingHandler.PairState.Paired).unpair()
        assertTrue(OwedPairFalse.owes(app, "desk1"))
        OwedPairFalse.clear(app, "desk1")
    }

    @Test
    fun unpairingOverALinkOwesNothing() {
        val d = device("desk2", link = true)
        PairingHandler(d, noop, PairingHandler.PairState.Paired).unpair()
        assertFalse(OwedPairFalse.owes(app, "desk2"))
    }

    @Test
    fun theNextLinkCarriesItAndTheDebtIsClearedWhenSent() {
        OwedPairFalse.owe(app, "desk3")
        val d = device("desk3", link = true)
        val handler = PairingHandler(d, noop, PairingHandler.PairState.NotPaired)
        val callback = slot<Device.SendPacketStatusCallback>()
        val packet = slot<NetworkPacket>()
        every { d.sendPacket(capture(packet), capture(callback)) } returns Unit
        handler.sendOwedPairFalse()
        assertTrue(packet.captured.type == NetworkPacket.PACKET_TYPE_PAIR && !packet.captured.getBoolean("pair"))
        assertTrue("owed until the packet is on its way", OwedPairFalse.owes(app, "desk3"))
        callback.captured.onSuccess()
        assertFalse(OwedPairFalse.owes(app, "desk3"))
    }

    @Test
    fun aFailedSendKeepsTheDebtForTheNextLink() {
        OwedPairFalse.owe(app, "desk4")
        val d = device("desk4", link = true)
        val callback = slot<Device.SendPacketStatusCallback>()
        every { d.sendPacket(any(), capture(callback)) } returns Unit
        PairingHandler(d, noop, PairingHandler.PairState.NotPaired).sendOwedPairFalse()
        callback.captured.onFailure(java.io.IOException("gone"))
        assertTrue(OwedPairFalse.owes(app, "desk4"))
        OwedPairFalse.clear(app, "desk4")
    }

    @Test
    fun nothingIsSentWithoutADebtWithoutALinkOrDuringANegotiation() {
        val none = device("desk5", link = true)
        PairingHandler(none, noop, PairingHandler.PairState.NotPaired).sendOwedPairFalse()
        OwedPairFalse.owe(app, "desk6")
        val noLink = device("desk6", link = false)
        PairingHandler(noLink, noop, PairingHandler.PairState.NotPaired).sendOwedPairFalse()
        val negotiating = device("desk6", link = true)
        PairingHandler(negotiating, noop, PairingHandler.PairState.RequestedByPeer).sendOwedPairFalse()
        verify(exactly = 0) { none.sendPacket(any(), any()) }
        verify(exactly = 0) { noLink.sendPacket(any(), any()) }
        verify(exactly = 0) { negotiating.sendPacket(any(), any()) }
        OwedPairFalse.clear(app, "desk6")
    }
}
