/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class WebRtcFileTransferManagerTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("desklink.transfer.checkpoints", 0).edit().clear().commit()
        context.filesDir.resolve("webrtc-transfers").deleteRecursively()
    }

    @After
    fun tearDown() {
        context.filesDir.resolve("webrtc-transfers").deleteRecursively()
    }

    @Test
    fun incomingChunkIsVerifiedBeforeDispatchAndCompletion() {
        val device = mockk<Device>(relaxed = true)
        val outgoing = mutableListOf<OutboundWebRtcFileMessage>()
        val manager = WebRtcFileTransferManager(context, device, outgoing::add)
        val wire = WebRtcWireBinding(
            senderDeviceId = "phone",
            peerDeviceId = "desktop",
            sessionId = 42,
            generation = 7,
        )
        val bytes = "DeskLink WebRTC file".toByteArray()
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        manager.handleControl(
            wire,
            WebRtcFileControl(
                protocolVersion = 1,
                action = WebRtcFileAction.OFFER,
                transferId = "transfer-1",
                deviceId = "desktop",
                sessionId = 42,
                connectionGeneration = 7,
                transferToken = "token-1",
                filename = "report.txt",
                totalSize = bytes.size.toLong(),
                sha256 = checksum,
                offset = 0,
                chunkSize = WebRtcFileControl.MAX_CHUNK_BYTES,
            ),
        )
        assertEquals(WebRtcFileAction.ACCEPT, (outgoing.single() as OutboundWebRtcFileMessage.Control).value.action)

        manager.handleChunk(
            wire,
            WebRtcFileChunk("transfer-1", "token-1", 0, bytes).encode(),
        )

        val packetSlot = slot<NetworkPacket>()
        verify(exactly = 1) { device.onWebRtcPacketReceived(capture(packetSlot)) }
        val packet = packetSlot.captured
        assertEquals("report.txt", packet.getString("filename"))
        assertArrayEquals(bytes, packet.payload!!.inputStream!!.readBytes())
        packet.payload!!.close()
        assertEquals(
            listOf(WebRtcFileAction.ACCEPT, WebRtcFileAction.ACKNOWLEDGE, WebRtcFileAction.COMPLETE),
            outgoing.filterIsInstance<OutboundWebRtcFileMessage.Control>().map { it.value.action },
        )
    }
}
