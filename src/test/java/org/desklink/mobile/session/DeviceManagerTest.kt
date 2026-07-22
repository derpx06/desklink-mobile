/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.session

import org.desklink.mobile.transport.DisconnectReason
import org.desklink.mobile.transport.LogicalChannel
import org.desklink.mobile.transport.SessionTransport
import org.desklink.mobile.transport.TransportCallback
import org.desklink.mobile.transport.TransportState
import org.desklink.mobile.transport.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceManagerTest {
    private class RecordingTransport(
        override val transportId: String,
    ) : SessionTransport {
        override val transportType = TransportType.LEGACY_LAN
        override var state: TransportState = TransportState.CONNECTED
        var closeReason: DisconnectReason? = null
        var sendCount = 0

        override fun send(channel: LogicalChannel, payload: ByteArray, callback: TransportCallback) {
            sendCount++
            callback.onSuccess()
        }

        override fun close(reason: DisconnectReason) {
            closeReason = reason
            state = TransportState.CLOSED
        }
    }

    @Test
    fun firstRegistrationCreatesOneSessionAndGenerationOne() {
        val manager = DeviceManager()
        val transport = RecordingTransport("one")

        val registration = manager.register("phone", transport, PairingState.PAIRED)
        val snapshot = manager.sessionsSnapshot().single()

        assertEquals(1L, registration.binding.sessionId)
        assertEquals(1L, registration.binding.connectionGeneration)
        assertEquals(SessionState.ACTIVE, snapshot.state)
        assertEquals(PairingState.PAIRED, snapshot.pairingState)
        assertSame(transport, manager.currentBinding("phone")?.transport)
    }

    @Test
    fun replacementKeepsLogicalSessionAndClosesOlderTransport() {
        val manager = DeviceManager()
        val first = RecordingTransport("first")
        val second = RecordingTransport("second")

        val original = manager.register("phone", first, PairingState.PAIRED)
        val replacement = manager.register("phone", second, PairingState.NOT_PAIRED)

        assertEquals(original.binding.sessionId, replacement.binding.sessionId)
        assertEquals(2L, replacement.binding.connectionGeneration)
        assertEquals(1L, replacement.replacedGeneration)
        assertEquals(DisconnectReason.REPLACED, first.closeReason)
        assertTrue(manager.isCurrent(replacement.binding))
        assertFalse(manager.isCurrent(original.binding))
        assertEquals(PairingState.PAIRED, manager.sessionsSnapshot().single().pairingState)
    }

    @Test
    fun staleDisconnectCannotClearReplacement() {
        val manager = DeviceManager()
        val first = RecordingTransport("first")
        val second = RecordingTransport("second")
        val original = manager.register("phone", first, PairingState.PAIRED)
        val replacement = manager.register("phone", second, PairingState.PAIRED)

        assertFalse(manager.disconnectIfCurrent(original.binding, DisconnectReason.NETWORK_LOST))
        assertTrue(manager.isCurrent(replacement.binding))
        assertNull(manager.sessionsSnapshot().single().lastDisconnectReason)

        assertTrue(manager.disconnectIfCurrent(replacement.binding, DisconnectReason.NETWORK_LOST))
        val snapshot = manager.sessionsSnapshot().single()
        assertEquals(SessionState.DISCONNECTED, snapshot.state)
        assertEquals(DisconnectReason.NETWORK_LOST, snapshot.lastDisconnectReason)
        assertNull(manager.currentBinding("phone"))
        assertEquals(DisconnectReason.NETWORK_LOST, second.closeReason)
    }

    @Test
    fun reconnectAttemptOnlyAppliesToPairedSession() {
        val manager = DeviceManager()
        manager.register("unpaired", RecordingTransport("unpaired"), PairingState.NOT_PAIRED)
        manager.register("paired", RecordingTransport("paired"), PairingState.PAIRED)

        assertFalse(manager.markReconnectAttempt("unpaired", 1))
        assertTrue(manager.markReconnectAttempt("paired", 2))
        assertEquals(2, manager.sessionsSnapshot().first { it.deviceId == "paired" }.reconnectAttempt)
    }

    @Test
    fun terminateAllMakesBindingsStaleAndClosesTransports() {
        val manager = DeviceManager()
        val first = RecordingTransport("first")
        val second = RecordingTransport("second")
        val firstBinding = manager.register("one", first, PairingState.PAIRED).binding
        manager.register("two", second, PairingState.PAIRED)

        val closed = manager.terminateAll()

        assertEquals(2, closed.size)
        assertFalse(manager.isCurrent(firstBinding))
        assertEquals(SessionState.TERMINATED, manager.sessionsSnapshot().first { it.deviceId == "one" }.state)
        assertEquals(SessionState.TERMINATED, manager.sessionsSnapshot().first { it.deviceId == "two" }.state)
        assertEquals(DisconnectReason.SERVICE_STOPPED, first.closeReason)
        assertEquals(DisconnectReason.SERVICE_STOPPED, second.closeReason)
    }

    @Test
    fun unknownDeviceHasNoBinding() {
        val manager = DeviceManager()
        assertNull(manager.currentBinding("missing"))
        assertTrue(manager.sessionsSnapshot().isEmpty())
        assertFalse(manager.markReconnectAttempt("missing", 1))
    }
}
