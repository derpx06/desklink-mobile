/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.transport

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.backends.lan.LanLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportBoundaryTest {
    private class RecordingCallback : TransportCallback {
        var success = 0
        var error: TransportError? = null

        override fun onSuccess() {
            success++
        }

        override fun onFailure(error: TransportError) {
            this.error = error
        }
    }

    private class BoundaryTransport : SessionTransport {
        override val transportId = "test"
        override val transportType = TransportType.LEGACY_LAN
        override val state = TransportState.CONNECTED
        var calls = 0

        override fun send(channel: LogicalChannel, payload: ByteArray, callback: TransportCallback) {
            calls++
            if (channel == LogicalChannel.CONTROL) callback.onSuccess()
            else callback.onFailure(TransportError(TransportErrorCode.UNSUPPORTED_CHANNEL, "unsupported"))
        }

        override fun close(reason: DisconnectReason) = Unit
    }

    @Test
    fun controlChannelIsJavaCompatibleAndCallbackBased() {
        val transport: SessionTransport = BoundaryTransport()
        val callback = RecordingCallback()

        transport.send(LogicalChannel.CONTROL, "desklink.ping".toByteArray(), callback)

        assertEquals(1, callback.success)
        assertEquals(null, callback.error)
    }

    @Test
    fun unsupportedFutureChannelIsReportedWithoutThrowing() {
        val transport = BoundaryTransport()
        val callback = RecordingCallback()

        transport.send(LogicalChannel.PAYLOAD, byteArrayOf(1), callback)

        assertEquals(1, transport.calls)
        assertEquals(TransportErrorCode.UNSUPPORTED_CHANNEL, callback.error?.code)
        assertTrue(callback.error?.message?.isNotBlank() == true)
    }

    @Test
    fun legacyLanAdapterRejectsPairedFeaturePackets() {
        val link = mockk<LanLink>()
        every { link.deviceId } returns "phone"
        val transport = LegacyLanTransport(link)
        val callback = RecordingCallback()
        val packet = NetworkPacket("desklink.ping")

        transport.send(LogicalChannel.CONTROL, packet.serialize().toByteArray(), callback)

        verify(exactly = 0) { link.sendPacket(any(), any(), false) }
        assertEquals(TransportErrorCode.UNSUPPORTED_CHANNEL, callback.error?.code)
    }

    @Test
    fun legacyLanAdapterAllowsBootstrapPackets() {
        val link = mockk<LanLink>()
        every { link.deviceId } returns "phone"
        every { link.sendPacket(any(), any(), false) } returns true
        val transport = LegacyLanTransport(link)
        val callback = RecordingCallback()
        val packet = NetworkPacket(NetworkPacket.PACKET_TYPE_PAIR)

        transport.send(LogicalChannel.CONTROL, packet.serialize().toByteArray(), callback)

        verify(exactly = 1) { link.sendPacket(any(), any(), false) }
        assertEquals(null, callback.error)
    }
}
