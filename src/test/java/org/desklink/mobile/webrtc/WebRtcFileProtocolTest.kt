/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

class WebRtcFileProtocolTest {
    private val wire = WebRtcWireBinding("phone", "desktop", 9, 2)

    private fun offer() = WebRtcFileControl(
        protocolVersion = 1,
        action = WebRtcFileAction.OFFER,
        transferId = "transfer_1",
        deviceId = "desktop",
        sessionId = 9,
        connectionGeneration = 2,
        transferToken = "token",
        filename = "report.pdf",
        totalSize = 3,
        sha256 = "a".repeat(64),
        offset = 0,
        chunkSize = WebRtcFileControl.LEGACY_CHUNK_BYTES,
    )

    private fun assertRejected(control: WebRtcFileControl) {
        assertThrows(IllegalArgumentException::class.java) { control.validate(wire) }
    }

    /** Independent re-implementation of the desktop `FileChunk::encode` frame. */
    private fun frame(
        id: String = "transfer_1",
        token: String = "token",
        offset: Long = 0,
        data: ByteArray = "abc".toByteArray(),
        declaredLength: Int = data.size,
        digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(data),
        idLength: Int = id.length,
    ): ByteArray = ByteBuffer.allocate(52 + id.length + token.length + data.size)
        .put("DLF1".toByteArray())
        .putShort(idLength.toShort())
        .putShort(token.length.toShort())
        .putLong(offset)
        .putInt(declaredLength)
        .put(digest)
        .put(id.toByteArray())
        .put(token.toByteArray())
        .put(data)
        .array()

    private fun assertChunkRejected(encoded: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) { WebRtcFileChunk.decode(encoded) }
    }

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
            chunkSize = WebRtcFileControl.LEGACY_CHUNK_BYTES,
        )
        assertThrows(IllegalArgumentException::class.java) { offer.validate(wire) }
        assertThrows(IllegalArgumentException::class.java) {
            offer.copy(filename = "safe.txt", deviceId = "other").validate(wire)
        }
    }

    // ---- control records: the JSON is the wire contract with the desktop ----

    @Test
    fun aClipboardImageOfferSaysSoAndRoundTrips() {
        val clip = offer().copy(filename = "clipboard.png", clipboard = true, mimeType = "image/png")
        val json = clip.toJson()
        assertEquals(true, json.getBoolean("clipboard"))
        assertEquals("image/png", json.getString("mimeType"))
        assertEquals(clip, WebRtcFileControl.fromJson(JSONObject(json.toString())))
        // An ordinary offer carries neither key, so older desks see nothing new.
        assertTrue(!offer().toJson().has("clipboard") && !offer().toJson().has("mimeType"))
    }

    @Test
    fun controlJsonUsesTheDesktopCamelCaseKeysAndOmitsAbsentOptionals() {
        assertEquals(
            setOf(
                "protocolVersion", "action", "transferId", "deviceId", "sessionId",
                "connectionGeneration", "transferToken", "filename", "totalSize", "sha256",
                "offset", "chunkSize",
            ),
            offer().toJson().keys().asSequence().toSet(),
        )
        val ack = offer().copy(
            action = WebRtcFileAction.ACKNOWLEDGE,
            filename = null,
            totalSize = null,
            sha256 = null,
            offset = 3,
        ).toJson()
        assertEquals(
            setOf(
                "protocolVersion", "action", "transferId", "deviceId", "sessionId",
                "connectionGeneration", "transferToken", "offset", "chunkSize",
            ),
            ack.keys().asSequence().toSet(),
        )
        assertEquals("acknowledge", ack.getString("action"))
    }

    @Test
    fun everyActionRoundTripsThroughItsLowercaseWireName() {
        val names = mapOf(
            WebRtcFileAction.OFFER to "offer",
            WebRtcFileAction.ACCEPT to "accept",
            WebRtcFileAction.ACKNOWLEDGE to "acknowledge",
            WebRtcFileAction.COMPLETE to "complete",
            WebRtcFileAction.CANCEL to "cancel",
            WebRtcFileAction.ERROR to "error",
        )
        assertEquals(WebRtcFileAction.entries.toSet(), names.keys)
        names.forEach { (action, name) ->
            assertEquals(name, action.wireName)
            assertEquals(action, WebRtcFileAction.fromWireName(name))
        }
        assertThrows(Exception::class.java) { WebRtcFileAction.fromWireName("Offer") }
        assertThrows(Exception::class.java) { WebRtcFileAction.fromWireName("delete") }
    }

    @Test
    fun parsesExactlyWhatTheDesktopSerializerEmits() {
        // Field order and shape of serde_json output for `FileControl`.
        val json = """{"protocolVersion":1,"action":"offer","transferId":"transfer_1",""" +
            """"deviceId":"desktop","sessionId":9,"connectionGeneration":2,"transferToken":"token",""" +
            """"filename":"report.pdf","totalSize":3,"sha256":"${"a".repeat(64)}","offset":0,""" +
            """"chunkSize":16384}"""
        val parsed = WebRtcFileControl.fromJson(JSONObject(json))
        assertEquals(offer(), parsed)
        parsed.validate(wire)
        assertEquals(offer(), WebRtcFileControl.fromJson(JSONObject(offer().toJson().toString())))
    }

    @Test
    fun parsesTheDesktopsChunkSizeUpgradeAndRoundTripsIt() {
        // serde_json output of a desktop Offer since the upgrade: the legacy
        // chunkSize stays for older peers, maxChunkSize is appended.
        val json = """{"protocolVersion":1,"action":"offer","transferId":"transfer_1",""" +
            """"deviceId":"desktop","sessionId":9,"connectionGeneration":2,"transferToken":"token",""" +
            """"filename":"report.pdf","totalSize":3,"sha256":"${"a".repeat(64)}","offset":0,""" +
            """"chunkSize":16384,"maxChunkSize":46080}"""
        val parsed = WebRtcFileControl.fromJson(JSONObject(json))
        assertEquals(offer().copy(maxChunkSize = 46080), parsed)
        parsed.validate(wire)
        assertEquals(parsed, WebRtcFileControl.fromJson(JSONObject(parsed.toJson().toString())))
        assertRejected(offer().copy(maxChunkSize = WebRtcFileControl.MAX_CHUNK_BYTES + 1))
    }

    @Test
    fun malformedControlJsonNeverProducesARecord() {
        val good = offer().toJson()
        for (key in listOf("protocolVersion", "action", "transferId", "deviceId", "sessionId",
            "connectionGeneration", "transferToken", "offset", "chunkSize")) {
            val missing = JSONObject(good.toString()).apply { remove(key) }
            assertThrows("missing $key", Exception::class.java) {
                WebRtcFileControl.fromJson(missing)
            }
        }
        assertThrows(Exception::class.java) {
            WebRtcFileControl.fromJson(JSONObject(good.toString()).put("action", "steal"))
        }
    }

    @Test
    fun validateAcceptsAnHonestOffer() {
        offer().validate(wire)
        offer().copy(totalSize = WebRtcFileControl.MAX_TRANSFER_BYTES).validate(wire)
        offer().copy(totalSize = 0).validate(wire)
        offer().copy(sha256 = "A".repeat(64)).validate(wire)
    }

    @Test
    fun validateRejectsWrongVersionAndBinding() {
        assertRejected(offer().copy(protocolVersion = 2))
        assertRejected(offer().copy(protocolVersion = 0))
        assertRejected(offer().copy(deviceId = "phone"))
        assertRejected(offer().copy(deviceId = "other"))
        assertRejected(offer().copy(sessionId = 10))
        assertRejected(offer().copy(connectionGeneration = 3))
    }

    @Test
    fun validateBoundsTransferIdAndToken() {
        offer().copy(transferId = "a".repeat(128)).validate(wire)
        offer().copy(transferToken = "t".repeat(256)).validate(wire)
        for (id in listOf("", "a".repeat(129), "a/b", "a b", "..", "../x", "a\u0000b", "id\n", "\u0442\u0435\u0441\u0442")) {
            assertRejected(offer().copy(transferId = id))
        }
        for (token in listOf("", "t".repeat(257), "t\u00e9")) {
            assertRejected(offer().copy(transferToken = token))
        }
    }

    @Test
    fun validateBoundsChunkSizeTotalSizeAndOffset() {
        offer().copy(chunkSize = 1).validate(wire)
        assertRejected(offer().copy(chunkSize = 0))
        assertRejected(offer().copy(chunkSize = -1))
        assertRejected(offer().copy(chunkSize = WebRtcFileControl.MAX_CHUNK_BYTES + 1))

        assertRejected(offer().copy(totalSize = WebRtcFileControl.MAX_TRANSFER_BYTES + 1))
        assertRejected(offer().copy(totalSize = -1))
        val ack = offer().copy(action = WebRtcFileAction.ACKNOWLEDGE, filename = null, sha256 = null)
        ack.copy(offset = 3).validate(wire)
        assertRejected(ack.copy(offset = 4))
        assertRejected(ack.copy(offset = -1))
        // No announced size: a negative offset must still never get through
        // (the desktop cannot even express one: `offset` is a u64).
        assertRejected(ack.copy(totalSize = null, offset = -1))
    }

    @Test
    fun validateRejectsMalformedChecksumsAndOversizedErrors() {
        for (checksum in listOf("a".repeat(63), "a".repeat(65), "g".repeat(64), "z" + "a".repeat(63), "")) {
            assertRejected(offer().copy(sha256 = checksum))
        }
        val error = offer().copy(action = WebRtcFileAction.ERROR, filename = null, totalSize = null, sha256 = null)
        error.copy(error = "e".repeat(4096)).validate(wire)
        assertRejected(error.copy(error = "e".repeat(4097)))
    }

    @Test
    fun validateRejectsMalformedOffers() {
        assertRejected(offer().copy(filename = null))
        assertRejected(offer().copy(totalSize = null))
        assertRejected(offer().copy(sha256 = null))
        assertRejected(offer().copy(totalSize = 10, offset = 1))
    }

    @Test
    fun offerFilenamesAreConfinedToASingleSafeComponent() {
        val hostile = listOf(
            "", ".", "..", "../x", "..\\x", "/etc/passwd", "a/b", "a\\b", "C:\\x\\y",
            "a\u0000b", "\u0000", "a".repeat(256),
            // 86 x 3-byte characters = 258 bytes; the desktop counts UTF-8 bytes.
            "\u65e5".repeat(86),
        )
        for (name in hostile) {
            assertRejected(offer().copy(filename = name))
        }
        val safe = listOf(
            "report.pdf", ".hidden", "..x", "a b.txt", "a".repeat(255), "\u65e5\u672c\u8a9e.txt",
            // 85 x 3-byte characters = exactly 255 bytes.
            "\u65e5".repeat(85),
        )
        for (name in safe) {
            offer().copy(filename = name).validate(wire)
        }
    }

    // ---- chunk frames ----

    @Test
    fun chunkFrameLayoutMatchesTheDesktopEncoder() {
        val data = "abc".toByteArray()
        val chunk = WebRtcFileChunk("transfer_1", "token", 0x0102030405060708L, data)
        assertArrayEquals(
            frame(offset = 0x0102030405060708L, data = data),
            chunk.encode(),
        )
        val decoded = WebRtcFileChunk.decode(frame(offset = 0x0102030405060708L, data = data))
        assertEquals(chunk, decoded)
    }

    @Test
    fun chunkRoundTripsAtTheMaximumSize() {
        val data = ByteArray(WebRtcFileControl.MAX_CHUNK_BYTES) { it.toByte() }
        val chunk = WebRtcFileChunk("t", "k", 4L * 1024 * 1024 * 1024, data)
        assertEquals(chunk, WebRtcFileChunk.decode(chunk.encode()))
    }

    @Test
    fun chunkEncodeRejectsEmptyOrOversizedDataAndBadIdentifiers() {
        assertThrows(IllegalArgumentException::class.java) {
            WebRtcFileChunk("t", "k", 0, ByteArray(0)).encode()
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebRtcFileChunk("t", "k", 0, ByteArray(WebRtcFileControl.MAX_CHUNK_BYTES + 1)).encode()
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebRtcFileChunk("../x", "k", 0, byteArrayOf(1)).encode()
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebRtcFileChunk("t", "", 0, byteArrayOf(1)).encode()
        }
    }

    @Test
    fun chunkDecodeRejectsMalformedFrames() {
        val good = frame()
        assertChunkRejected(ByteArray(0))
        assertChunkRejected(good.copyOf(51))
        assertChunkRejected(good.copyOf(good.size - 1))          // truncated body
        assertChunkRejected(good + byteArrayOf(0))                // trailing garbage
        assertChunkRejected(good.copyOf().also { it[0] = 'X'.code.toByte() }) // magic
        assertChunkRejected(good.copyOf().also { it[3] = '2'.code.toByte() }) // version
    }

    @Test
    fun chunkDecodeRejectsLyingOrOutOfRangeLengths() {
        val payload = ByteArray(WebRtcFileControl.MAX_CHUNK_BYTES + 1) { 1 }
        assertChunkRejected(frame(data = payload))                            // one byte over the bound
        assertChunkRejected(frame(data = ByteArray(0)))                       // empty
        assertChunkRejected(frame(declaredLength = 0))                        // claims empty, has bytes
        assertChunkRejected(frame(declaredLength = -1))                       // 0xFFFFFFFF
        assertChunkRejected(frame(declaredLength = Int.MIN_VALUE))
        assertChunkRejected(frame(declaredLength = 4))                        // claims more than it has
        assertChunkRejected(frame(declaredLength = 2))                        // claims less than it has
        assertChunkRejected(frame(idLength = 0xffff))                         // id length past the frame
        assertChunkRejected(frame(idLength = 9))                              // shifts id/token boundary
    }

    @Test
    fun chunkDecodeRejectsBadDigestsAndIdentifiers() {
        assertChunkRejected(frame(digest = ByteArray(32)))
        assertChunkRejected(frame(digest = MessageDigest.getInstance("SHA-256").digest("xyz".toByteArray())))
        assertChunkRejected(frame(id = "../x"))
        assertChunkRejected(frame(id = ""))
        assertChunkRejected(frame(token = ""))
        assertChunkRejected(frame(id = "a/b"))
        assertChunkRejected(frame(id = "a".repeat(129)))
        assertChunkRejected(frame(token = "t".repeat(257)))
    }

    @Test
    fun chunkDecodeIsIndependentOfTheBufferItWasGiven() {
        val encoded = frame()
        val decoded = WebRtcFileChunk.decode(encoded)
        encoded.fill(0)
        assertTrue(decoded.data.contentEquals("abc".toByteArray()))
    }
}
