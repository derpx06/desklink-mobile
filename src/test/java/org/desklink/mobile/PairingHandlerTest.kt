/*
 * SPDX-FileCopyrightText: 2025 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile

import org.junit.Assert
import org.junit.Test
import org.desklink.mobile.helpers.security.SslHelper
import java.util.Base64

class PairingHandlerTest {
    private val certA = SslHelper.parseCertificate(Base64.getMimeDecoder().decode(
        "MIIBkzCCATmgAwIBAgIBATAKBggqhkjOPQQDBDBTMS0wKwYDVQQDDCRlZTA2MWE3NV9lNDAzXzRlY2NfOTI2" +
            "MV81ZmZlMjcyMmY2OTgxFDASBgNVBAsMC0tERSBDb25uZWN0MQwwCgYDVQQKDANLREUwHhcNMjMwOTE1MjIw" +
            "MDAwWhcNMzQwOTE1MjIwMDAwWjBTMS0wKwYDVQQDDCRlZTA2MWE3NV9lNDAzXzRlY2NfOTI2MV81ZmZlMjcy" +
            "MmY2OTgxFDASBgNVBAsMC0tERSBDb25uZWN0MQwwCgYDVQQKDANLREUwWTATBgcqhkjOPQIBBggqhkjOPQMB" +
            "BwNCAASqOIKTm5j6x8DKgYSkItLmjCgIXP0gkOW6bmVvloDGsYnvqYLMFGe7YW8g8lT/qPBTEfDOM4UpQ8X6" +
            "jidE+XrnMAoGCCqGSM49BAMEA0gAMEUCIEpk6VNpbt3tfbWDf0TmoJftRq3wAs3Dke7d5vMZlivyAiEA/ZXt" +
            "SRqPjs/2RN9SynKhSUA9/z0PNq6LYoAaC6TdomM="
    ))
    private val certB = SslHelper.parseCertificate(Base64.getMimeDecoder().decode(
        "MIIBkzCCATmgAwIBAgIBATAKBggqhkjOPQQDBDBTMS0wKwYDVQQDDCQxNTdiYmMyOF82ZjJiXzRiMTZfYmQw" +
            "Ml8xMzM0NWMwMjU0M2MxFDASBgNVBAsMC0tERSBDb25uZWN0MQwwCgYDVQQKDANLREUwHhcNMjQwMTE3MjMw" +
            "MDAwWhcNMzUwMTE3MjMwMDAwWjBTMS0wKwYDVQQDDCQxNTdiYmMyOF82ZjJiXzRiMTZfYmQwMl8xMzM0NWMw" +
            "MjU0M2MxFDASBgNVBAsMC0tERSBDb25uZWN0MQwwCgYDVQQKDANLREUwWTATBgcqhkjOPQIBBggqhkjOPQMB" +
            "BwNCAAQ5W53rrDJps9v/sszQf0eLtvoGiRbfsY+snO6IJJfi1pFeHDQj2nAE+aTyUYelrcx1eIuqxFHnJTFt" +
            "/HqXwuAvMAoGCCqGSM49BAMEA0gAMEUCIBIk3zKPz/M0c82nvCGFDXGGmfdojHsx3G5DbYNNKqFVAiEAzhBG" +
            "e960/4NDiaVcOplBaeg5xNJKs3Kq+22J6JOii4Y="))

    @Test
    fun getVerificationKey() {
        val timestampA = 1737228658L
        val timestampB = 2737228658L
        Assert.assertEquals("741294", PairingHandler.getVerificationKey(certA, certB, timestampA))
        Assert.assertEquals("741294", PairingHandler.getVerificationKey(certB, certA, timestampA))
        Assert.assertEquals("274426", PairingHandler.getVerificationKey(certA, certB, timestampB))
    }

    @Test
    fun verificationCodeMatchesTheDesktopVector() {
        // Same vector as pairing.rs `verification_key_is_order_independent`.
        Assert.assertEquals("087037", PairingHandler.verificationCode("aaa".toByteArray(), "bbb".toByteArray(), 42))
        Assert.assertEquals("087037", PairingHandler.verificationCode("bbb".toByteArray(), "aaa".toByteArray(), 42))
    }

    @Test
    fun getVerificationKeyV7() {
        Assert.assertEquals("304181", PairingHandler.getVerificationKeyV7(certA, certB))
        Assert.assertEquals("304181", PairingHandler.getVerificationKeyV7(certB, certA))
        Assert.assertEquals("326935", PairingHandler.getVerificationKeyV7(certA, certA))
    }

}

/** Clock skew and crossing requests, against a handler with a mocked device. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class PairingHandlerClockTest {
    private val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()

    private class Recorder : PairingHandler.PairingCallback {
        val failures = mutableListOf<String>()
        var prompts = 0
        override fun incomingPairRequest() { prompts++ }
        override fun pairingSuccessful() = Unit
        override fun pairingFailed(error: String) { failures += error }
        override fun unpaired(device: Device) = Unit
    }

    private fun request(secondsFromNow: Long) = NetworkPacket(NetworkPacket.PACKET_TYPE_PAIR).apply {
        this["pair"] = true
        this["timestamp"] = System.currentTimeMillis() / 1000L + secondsFromNow
    }

    private fun handler(state: PairingHandler.PairState, recorder: Recorder, otherId: String): PairingHandler {
        org.desklink.mobile.helpers.DeviceHelper.initializeDeviceId(app)
        val device = io.mockk.mockk<Device>(relaxed = true)
        io.mockk.every { device.context } returns app
        io.mockk.every { device.deviceId } returns otherId
        io.mockk.every { device.protocolVersion } returns 9
        io.mockk.every { device.isReachable } returns true
        return PairingHandler(device, recorder, state)
    }

    @Test
    fun aRequestFromASkewedClockSaysSoAndNeverPrompts() {
        for (skew in listOf(-7_200L, 7_200L, -86_400L)) {
            val recorder = Recorder()
            val handler = handler(PairingHandler.PairState.NotPaired, recorder, "0")
            handler.packetReceived(request(skew))
            Assert.assertEquals(PairingHandler.PairState.NotPaired, handler.state)
            Assert.assertEquals(0, recorder.prompts)
            Assert.assertEquals(listOf(app.getString(R.string.error_clocks_not_match)), recorder.failures)
        }
        val recorder = Recorder()
        val handler = handler(PairingHandler.PairState.NotPaired, recorder, "0")
        handler.packetReceived(request(-120))
        Assert.assertEquals(PairingHandler.PairState.RequestedByPeer, handler.state)
        Assert.assertEquals(1, recorder.prompts)
    }

    private fun timerJob(handler: PairingHandler): kotlinx.coroutines.Job? {
        val field = PairingHandler::class.java.getDeclaredField("pairingJob").apply { isAccessible = true }
        return (field.get(handler) as kotlinx.coroutines.Job).children.firstOrNull()
    }

    /**
     * The follower of two crossing requests adopts the leader's only if its stamp is plausible, as the desktop does, and an
     * implausible one leaves the timer of its own request exactly as it was (the same job, not re-armed).
     */
    @Test
    fun aCrossingRequestWithAnImplausibleStampIsNotAdoptedAndDoesNotRestartTheTimer() {
        // A device id that sorts below any hex id makes this phone the follower.
        val recorder = Recorder()
        val handler = handler(PairingHandler.PairState.NotPaired, recorder, "")
        handler.requestPairing()
        Assert.assertEquals(PairingHandler.PairState.Requested, handler.state)
        val timer = timerJob(handler)
        Assert.assertNotNull("requesting arms the timer", timer)

        handler.packetReceived(request(-7_200))
        Assert.assertEquals(PairingHandler.PairState.Requested, handler.state)
        Assert.assertEquals(0, recorder.prompts)
        Assert.assertSame("the timer was not touched", timer, timerJob(handler))

        handler.packetReceived(request(-5))
        Assert.assertEquals(PairingHandler.PairState.RequestedByPeer, handler.state)
        Assert.assertEquals(1, recorder.prompts)
        handler.cancelPairing()
    }

    /** The window is the signaling's five minutes, on both sides. */
    @Test
    fun theStampWindowIsFiveMinutes() {
        val handler = handler(PairingHandler.PairState.NotPaired, Recorder(), "0")
        val now = System.currentTimeMillis() / 1000L
        Assert.assertTrue(handler.timestampIsPlausible(now - 240))
        Assert.assertTrue(handler.timestampIsPlausible(now + 240))
        Assert.assertFalse(handler.timestampIsPlausible(now - 360))
        Assert.assertFalse(handler.timestampIsPlausible(now + 360))
    }
}
