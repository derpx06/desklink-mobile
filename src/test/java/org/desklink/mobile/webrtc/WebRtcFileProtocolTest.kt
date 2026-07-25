/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebRtcFileProtocolTest {
    @Test
    fun chunkEncodingMatchesDesktopHeaderAndDetectsCorruption() {
        val chunk = WebRtcFileChunk("transfer-1", "token-1", 16, "DeskLink".toByteArray())
        val encoded = chunk.encode()
        assertEquals('D'.code.toByte(), encoded[0])
        assertEquals(chunk, WebRtcFileChunk.decode(encoded))
        assertArrayEquals(chunk.data, WebRtcFileChunk.decode(encoded).data)

        encoded[encoded.lastIndex] = (encoded.last() + 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            WebRtcFileChunk.decode(encoded)
        }
    }

    @Test
    fun offerRejectsUnsafeFilenameAndWrongDevice() {
        val wire = WebRtcWireBinding.fromAttempt(
            "phone", "desktop", "01234567-89ab-cdef-0123-456789abcdef",
        )
        val offer = WebRtcFileControl(
            protocolVersion = 1,
            action = WebRtcFileAction.OFFER,
            transferId = "transfer-1",
            deviceId = "desktop",
            sessionId = wire.sessionId,
            connectionGeneration = wire.generation,
            transferToken = "token",
            filename = "../escape",
            totalSize = 4,
            sha256 = "0".repeat(64),
            offset = 0,
            chunkSize = WebRtcFileControl.MAX_CHUNK_BYTES,
        )
        assertThrows(IllegalArgumentException::class.java) { offer.validate(wire) }
        assertThrows(IllegalArgumentException::class.java) {
            offer.copy(filename = "safe.txt", deviceId = "other").validate(wire)
        }
    }
}
