/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A QR code that rotated is refused by the computer; the phone says that in a sentence a person can act on. */
@RunWith(RobolectricTestRunner::class)
class QrRefusedProofTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private class Recorder : PairingHandler.PairingCallback {
        val failures = mutableListOf<String>()
        override fun incomingPairRequest() = Unit
        override fun pairingSuccessful() = Unit
        override fun pairingFailed(error: String) { failures += error }
        override fun unpaired(device: Device) = Unit
    }

    private fun refusal() = NetworkPacket(NetworkPacket.PACKET_TYPE_PAIR).apply { this["pair"] = false }

    @Test
    fun aRefusedProofSaysTheCodeHasExpired() {
        val device = mockk<Device>(relaxed = true)
        every { device.context } returns app
        val callback = Recorder()
        val handler = PairingHandler(device, callback, PairingHandler.PairState.NotPaired)
        handler.requestPairingWithProof("mac", mockk(relaxed = true))
        handler.packetReceived(refusal())
        assertEquals(listOf("This code has expired. Ask the computer for a new one."), callback.failures)
    }

    @Test
    fun aCancelWithoutAProofStillSaysTheComputerCancelled() {
        val device = mockk<Device>(relaxed = true)
        every { device.context } returns app
        val callback = Recorder()
        val handler = PairingHandler(device, callback, PairingHandler.PairState.Requested)
        handler.packetReceived(refusal())
        assertEquals(listOf(app.getString(R.string.error_canceled_by_other_peer)), callback.failures)
    }
}
