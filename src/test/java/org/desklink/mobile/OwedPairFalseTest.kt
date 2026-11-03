/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.desklink.mobile.helpers.OwedPairFalse
import org.desklink.mobile.helpers.security.SslHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.cert.Certificate
import java.util.Base64

/** An unpair that may not have been heard is owed to that computer (its certificate) and goes out with its next link, once. */
@RunWith(RobolectricTestRunner::class)
class OwedPairFalseTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val noop = mockk<PairingHandler.PairingCallback>(relaxed = true)
    private val deskCert: Certificate = cert(
        "MIIBkzCCATmgAwIBAgIBATAKBggqhkjOPQQDBDBTMS0wKwYDVQQDDCRlZTA2MWE3NV9lNDAzXzRlY2NfOTI2MV81ZmZlMjcyMmY2OTgxFDASBgNVBAsMC0tERSBDb25uZWN0MQwwCgYDVQQKDANLREUwHhcNMjMwOTE1MjIwMDAwWhcNMzQwOTE1MjIwMDAwWjBTMS0wKwYDVQQDDCRlZTA2MWE3NV9lNDAzXzRlY2NfOTI2MV81ZmZlMjcyMmY2OTgxFDASBgNVBAsMC0tERSBDb25uZWN0MQwwCgYDVQQKDANLREUwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASqOIKTm5j6x8DKgYSkItLmjCgIXP0gkOW6bmVvloDGsYnvqYLMFGe7YW8g8lT/qPBTEfDOM4UpQ8X6jidE+XrnMAoGCCqGSM49BAMEA0gAMEUCIEpk6VNpbt3tfbWDf0TmoJftRq3wAs3Dke7d5vMZlivyAiEA/ZXtSRqPjs/2RN9SynKhSUA9/z0PNq6LYoAaC6TdomM=",
    )
    private val otherCert: Certificate = cert(
        "MIIBkzCCATmgAwIBAgIBATAKBggqhkjOPQQDBDBTMS0wKwYDVQQDDCQxNTdiYmMyOF82ZjJiXzRiMTZfYmQwMl8xMzM0NWMwMjU0M2MxFDASBgNVBAsMC0tERSBDb25uZWN0MQwwCgYDVQQKDANLREUwHhcNMjQwMTE3MjMwMDAwWhcNMzUwMTE3MjMwMDAwWjBTMS0wKwYDVQQDDCQxNTdiYmMyOF82ZjJiXzRiMTZfYmQwMl8xMzM0NWMwMjU0M2MxFDASBgNVBAsMC0tERSBDb25uZWN0MQwwCgYDVQQKDANLREUwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAQ5W53rrDJps9v/sszQf0eLtvoGiRbfsY+snO6IJJfi1pFeHDQj2nAE+aTyUYelrcx1eIuqxFHnJTFt/HqXwuAvMAoGCCqGSM49BAMEA0gAMEUCIBIk3zKPz/M0c82nvCGFDXGGmfdojHsx3G5DbYNNKqFVAiEAzhBGe960/4NDiaVcOplBaeg5xNJKs3Kq+22J6JOii4Y=",
    )

    private fun cert(base64: String) = SslHelper.parseCertificate(Base64.getMimeDecoder().decode(base64))

    private fun device(id: String, link: Boolean, certificate: Certificate = deskCert) = mockk<Device>(relaxed = true).also {
        every { it.context } returns app
        every { it.deviceId } returns id
        every { it.hasBootstrapLink } returns link
        every { it.certificate } returns certificate
    }

    private fun handler(d: Device, state: PairingHandler.PairState) = PairingHandler(d, noop, state)

    @Test
    fun theDebtIsKeptReplacedAndClearedPerComputer() {
        OwedPairFalse.owe(app, "a", "aa")
        OwedPairFalse.owe(app, "b", "bb")
        OwedPairFalse.owe(app, "a", "cc")
        assertTrue(OwedPairFalse.owes(app, "a"))
        assertTrue(OwedPairFalse.mayTell(app, "a", "CC"))
        assertFalse("the earlier certificate was replaced", OwedPairFalse.mayTell(app, "a", "aa"))
        OwedPairFalse.clear(app, "a")
        assertFalse(OwedPairFalse.owes(app, "a"))
        assertTrue(OwedPairFalse.owes(app, "b"))
        OwedPairFalse.clear(app, "b")
    }

    @Test
    fun aDebtWithNoKnownCertificateIsNeverPaid() {
        OwedPairFalse.owe(app, "x", null)
        assertTrue(OwedPairFalse.owes(app, "x"))
        assertFalse(OwedPairFalse.mayTell(app, "x", OwedPairFalse.fingerprint(deskCert)))
        OwedPairFalse.clear(app, "x")
    }

    @Test
    fun unpairingWithoutALinkOwesThePairFalseForThatCertificate() {
        val d = device("desk1", link = false)
        handler(d, PairingHandler.PairState.Paired).unpair()
        assertTrue(OwedPairFalse.mayTell(app, "desk1", OwedPairFalse.fingerprint(deskCert)))
        OwedPairFalse.clear(app, "desk1")
    }

    @Test
    fun unpairingOverALinkOwesUntilThePacketIsOnItsWay() {
        val d = device("desk2", link = true)
        val callback = slot<Device.SendPacketStatusCallback>()
        every { d.sendPacket(any(), capture(callback)) } returns Unit
        handler(d, PairingHandler.PairState.Paired).unpair()
        assertTrue("a packet lost in the queue is not forgotten", OwedPairFalse.owes(app, "desk2"))
        callback.captured.onSuccess()
        assertFalse(OwedPairFalse.owes(app, "desk2"))
    }

    @Test
    fun theNextLinkCarriesItAndTheDebtIsClearedWhenSent() {
        OwedPairFalse.owe(app, "desk3", OwedPairFalse.fingerprint(deskCert))
        val d = device("desk3", link = true)
        val callback = slot<Device.SendPacketStatusCallback>()
        val packet = slot<NetworkPacket>()
        every { d.sendPacket(capture(packet), capture(callback)) } returns Unit
        handler(d, PairingHandler.PairState.NotPaired).sendOwedPairFalse()
        assertTrue(packet.captured.type == NetworkPacket.PACKET_TYPE_PAIR && !packet.captured.getBoolean("pair"))
        assertTrue("owed until the packet is on its way", OwedPairFalse.owes(app, "desk3"))
        callback.captured.onSuccess()
        assertFalse(OwedPairFalse.owes(app, "desk3"))
    }

    @Test
    fun aFailedSendKeepsTheDebtForTheNextLink() {
        OwedPairFalse.owe(app, "desk4", OwedPairFalse.fingerprint(deskCert))
        val d = device("desk4", link = true)
        val callback = slot<Device.SendPacketStatusCallback>()
        every { d.sendPacket(any(), capture(callback)) } returns Unit
        handler(d, PairingHandler.PairState.NotPaired).sendOwedPairFalse()
        callback.captured.onFailure(java.io.IOException("gone"))
        assertTrue(OwedPairFalse.owes(app, "desk4"))
        OwedPairFalse.clear(app, "desk4")
    }

    @Test
    fun anImpostorUnderTheSameIdIsNeverToldAndDoesNotSwallowTheDebt() {
        OwedPairFalse.owe(app, "desk5", OwedPairFalse.fingerprint(deskCert))
        val impostor = device("desk5", link = true, certificate = otherCert)
        handler(impostor, PairingHandler.PairState.NotPaired).sendOwedPairFalse()
        verify(exactly = 0) { impostor.sendPacket(any(), any()) }
        assertTrue(OwedPairFalse.owes(app, "desk5"))
        OwedPairFalse.clear(app, "desk5")
    }

    @Test
    fun nothingIsSentWithoutADebtWithoutALinkOrDuringANegotiation() {
        val none = device("desk6", link = true)
        handler(none, PairingHandler.PairState.NotPaired).sendOwedPairFalse()
        OwedPairFalse.owe(app, "desk7", OwedPairFalse.fingerprint(deskCert))
        val noLink = device("desk7", link = false)
        handler(noLink, PairingHandler.PairState.NotPaired).sendOwedPairFalse()
        val negotiating = device("desk7", link = true)
        handler(negotiating, PairingHandler.PairState.RequestedByPeer).sendOwedPairFalse()
        verify(exactly = 0) { none.sendPacket(any(), any()) }
        verify(exactly = 0) { noLink.sendPacket(any(), any()) }
        verify(exactly = 0) { negotiating.sendPacket(any(), any()) }
        OwedPairFalse.clear(app, "desk7")
    }

    @Test
    fun theCheckAndTheEnqueueHappenUnderTheStateLock() {
        OwedPairFalse.owe(app, "desk8", OwedPairFalse.fingerprint(deskCert))
        val d = device("desk8", link = true)
        val handler = handler(d, PairingHandler.PairState.NotPaired)
        val lock = PairingHandler::class.java.getDeclaredField("stateLock").apply { isAccessible = true }.get(handler)
        var held = false
        every { d.sendPacket(any(), any()) } answers { held = Thread.holdsLock(lock) }
        handler.sendOwedPairFalse()
        assertTrue("a pairing that starts meanwhile cannot interleave", held)
        OwedPairFalse.clear(app, "desk8")
    }
}
