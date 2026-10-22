/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

class FileProtocolV2Test {
    private val id = UUID.fromString("1111aaaa-2222-4333-8444-5555bbbb5555")
    private val wire = WebRtcWireBinding("phone", "desk", 42, 7)
    private val hex64 = "ab".repeat(32)

    private fun frame(offset: Long, length: Int, last: Boolean, pass: Int = 0, index: Int = (offset / FileV2.BLOCK_BYTES).toInt()) =
        FileFrame.encode(id, pass, index, offset, ByteArray(length) { it.toByte() }, 0, length, if (last) ByteArray(32) { 7 } else null)

    // ------------------------------------------------------------ constants

    @Test
    fun theConstantsAreTheContractedOnes() {
        assertEquals(1_048_576, FileV2.BLOCK_BYTES)
        assertEquals(61_440, FileV2.FRAME_BYTES_DEFAULT)
        assertEquals(196_608, FileV2.FRAME_BYTES_MAX)
        assertEquals(61_440, FileV2.FRAME_BYTES_PROVEN)
        assertEquals(40, FileV2.FRAME_HEADER_BYTES)
        assertEquals(32, FileV2.BLOCK_TRAILER_BYTES)
        assertEquals(4L shl 20, FileV2.IN_FLIGHT_TARGET)
        assertEquals(1L shl 20, FileV2.IN_FLIGHT_LOW)
        assertEquals(8L shl 20, FileV2.UNACKED_MAX)
        assertEquals(8, FileV2.CHECKPOINT_BLOCKS)
        assertEquals(2_000L, FileV2.CHECKPOINT_MS)
        assertEquals(60_000L, FileV2.NO_PROGRESS_MS)
        assertEquals(2, FileV2.ACTIVE_PER_DIRECTION)
        assertEquals(3, FileV2.MAX_REWINDS)
        assertEquals(2, FileV2.MAX_RESTARTS)
        assertEquals(61_368, FileV2.payloadCapacity(FileV2.FRAME_BYTES_DEFAULT))
        assertTrue(FileV2.FRAME_BYTES_PROVEN <= FileV2.FRAME_BYTES_MAX)
    }

    @Test
    fun blockCountsForTheSizesThatMatter() {
        assertEquals(0, FileV2.blockCount(0))
        assertEquals(1, FileV2.blockCount(1))
        assertEquals(1, FileV2.blockCount(FileV2.BLOCK_BYTES.toLong()))
        assertEquals(2, FileV2.blockCount(FileV2.BLOCK_BYTES + 1L))
        assertEquals(1024, FileV2.blockCount(1L shl 30))
        assertEquals(4096, FileV2.blockCount(FileV2.MAX_TRANSFER_BYTES))
        assertEquals(WebRtcFileControl.MAX_BLOCKS, FileV2.blockCount(FileV2.MAX_TRANSFER_BYTES))
    }

    // --------------------------------------------------------------- frames

    @Test
    fun aFrameRoundTripsWithItsHeaderPayloadAndTrailer() {
        val payload = ByteArray(1000) { (it * 3).toByte() }
        val trailer = ByteArray(32) { (it + 1).toByte() }
        val bytes = FileFrame.encode(id, 5, 2, 2L * FileV2.BLOCK_BYTES, payload, 0, 1000, trailer)
        assertEquals(40 + 1000 + 32, bytes.size)
        assertEquals("DLF2", String(bytes, 0, 4))
        val decoded = FileFrame.decode(bytes)
        assertEquals(id, decoded.transferId)
        assertEquals(5, decoded.pass)
        assertEquals(2, decoded.blockIndex)
        assertEquals(2L * FileV2.BLOCK_BYTES, decoded.offset)
        assertEquals(1000, decoded.length)
        assertTrue(decoded.lastOfBlock)
        assertArrayEquals(payload, decoded.payloadCopy())
        assertArrayEquals(trailer, decoded.trailer())
    }

    @Test
    fun aMiddleFrameHasNoTrailer() {
        val decoded = FileFrame.decode(frame(0, 500, last = false))
        assertFalse(decoded.lastOfBlock)
        assertNull(decoded.trailer())
        assertEquals(40 + 500, frame(0, 500, last = false).size)
    }

    @Test
    fun theLargestFrameBothBuildsCarryFitsItsLimit() {
        val capacity = FileV2.payloadCapacity(FileV2.FRAME_BYTES_PROVEN)
        val last = frame(0, capacity, last = false)
        assertTrue(last.size <= FileV2.FRAME_BYTES_PROVEN)
        val withTrailer = FileFrame.encode(id, 0, 0, 0, ByteArray(capacity), 0, capacity, ByteArray(32))
        assertEquals(FileV2.FRAME_BYTES_PROVEN, withTrailer.size)
        FileFrame.decode(withTrailer, FileV2.FRAME_BYTES_PROVEN)
    }

    @Test
    fun malformedFramesAreRejectedBeforeAnythingIsTouched() {
        fun bad(bytes: ByteArray, max: Int = FileV2.FRAME_BYTES_MAX) = assertThrows(IllegalArgumentException::class.java) { FileFrame.decode(bytes, max) }
        val ok = frame(0, 100, last = false)
        bad(ByteArray(0))
        bad(ByteArray(40)) // header only, no payload
        bad(ok.copyOf().also { it[0] = 'X'.code.toByte() }) // magic
        bad(ok.copyOf().also { it[4] = 0x02 }) // an unknown flag
        bad(ok.copyOf().also { it[6] = 1 }) // reserved bytes
        bad(ok.copyOfRange(0, ok.size - 1)) // truncated payload
        bad(ok + byteArrayOf(0)) // trailing bytes
        bad(frame(0, 100, last = true).copyOfRange(0, 40 + 100 + 31)) // short trailer
        bad(ok, max = 100) // over the negotiated frame size
    }

    @Test
    fun aFramesBlockMustMatchItsOffsetAndNeverCrossABlock() {
        assertThrows(IllegalArgumentException::class.java) { FileFrame.decode(frame(0, 100, last = false, index = 1)) }
        assertThrows(IllegalArgumentException::class.java) { FileFrame.decode(frame(FileV2.BLOCK_BYTES - 50L, 100, last = false)) }
        // A frame that ends exactly at the block end must close the block.
        assertThrows(IllegalArgumentException::class.java) { FileFrame.decode(frame(FileV2.BLOCK_BYTES - 100L, 100, last = false)) }
        FileFrame.decode(frame(FileV2.BLOCK_BYTES - 100L, 100, last = true))
    }

    @Test
    fun theHeaderIsBigEndianAtTheContractedOffsets() {
        val bytes = FileFrame.encode(id, 3, 7, 7L * FileV2.BLOCK_BYTES + 5, ByteArray(9), 0, 9, null)
        val b = java.nio.ByteBuffer.wrap(bytes)
        b.position(4)
        assertEquals(0, b.get().toInt()) // flags
        assertEquals(3, b.get().toInt()) // pass
        assertEquals(0, b.short.toInt())
        assertEquals(id.mostSignificantBits, b.long)
        assertEquals(id.leastSignificantBits, b.long)
        assertEquals(7, b.int)
        assertEquals(7L * FileV2.BLOCK_BYTES + 5, b.long)
        assertEquals(9, b.int)
        assertEquals(40, b.position())
    }

    // -------------------------------------------------------------- hashes

    @Test
    fun theFileHashIsTheHashOfTheConcatenatedBlockHashes() {
        val sha = MessageDigest.getInstance("SHA-256")
        val a = sha.digest("block a".toByteArray())
        val b = sha.digest("block b".toByteArray())
        val expected = sha.digest(a + b).toHex()
        assertEquals(expected, FileHashes.fileHash(listOf(a, b)))
        assertEquals(sha.digest(a).toHex(), FileHashes.prefixHash(listOf(a, b), 1))
        // A 0-byte file and a 0-block prefix are the hash of nothing.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", FileHashes.fileHash(emptyList()))
        assertEquals(FileHashes.fileHash(emptyList()), FileHashes.prefixHash(listOf(a), 0))
        assertEquals(a.toHex(), a.toHex().hexToBytes().toHex())
    }

    // ------------------------------------------------------------- controls

    private fun control(action: WebRtcFileAction, block: WebRtcFileControl.() -> WebRtcFileControl = { this }) = WebRtcFileControl(
        action = action, transferId = id.toString(), deviceId = "desk", sessionId = 42, connectionGeneration = 7, transferToken = "t".repeat(32),
    ).block()

    private fun offer(block: WebRtcFileControl.() -> WebRtcFileControl = { this }) = control(WebRtcFileAction.OFFER) {
        copy(filename = "report.pdf", totalSize = 5, mtime = 1_700_000_000_000, blockSize = FileV2.BLOCK_BYTES, maxFrameBytes = FileV2.FRAME_BYTES_DEFAULT).block()
    }

    private val deskWire = WebRtcWireBinding("phone", "desk", 42, 7)

    @Test
    fun everyActionRoundTripsThroughJsonAndValidates() {
        val messages = listOf(
            offer(),
            control(WebRtcFileAction.ACCEPT) { copy(resumeBlock = 3, prefixSha256 = hex64, maxFrameBytes = 61_440) },
            control(WebRtcFileAction.BLOCK_ACK) { copy(blockIndex = 2, sha256 = hex64) },
            control(WebRtcFileAction.BLOCK_NACK) { copy(blockIndex = 2, pass = 1, reason = FileCodes.HASH_MISMATCH) },
            control(WebRtcFileAction.PAUSE) { copy(reason = FileCodes.STORAGE_FULL) },
            control(WebRtcFileAction.RESUME),
            control(WebRtcFileAction.RESUME) { copy(resumeBlock = 4, prefixSha256 = hex64, pass = 2) },
            control(WebRtcFileAction.DONE) { copy(fileHash = hex64, blocks = 5, totalSize = 5L * FileV2.BLOCK_BYTES) },
            control(WebRtcFileAction.COMPLETE) { copy(fileHash = hex64) },
            control(WebRtcFileAction.CANCEL) { copy(code = FileCodes.SOURCE_CHANGED, message = "It changed.") },
            control(WebRtcFileAction.ERROR) { copy(code = FileCodes.BUSY, message = "Busy.") },
        )
        for (message in messages) {
            val back = WebRtcFileControl.fromJson(JSONObject(message.toJson().toString()))
            assertEquals(message, back)
            back.validate(deskWire)
        }
    }

    @Test
    fun theOfferCarriesTheBucketAndShareExtras() {
        val bucket = offer { copy(folder = "bucket", relpath = "Photos/a.jpg", contentSha256 = hex64, filename = "a.jpg") }
        bucket.validate(deskWire)
        assertEquals(bucket, WebRtcFileControl.fromJson(JSONObject(bucket.toJson().toString())))
        assertThrows(IllegalArgumentException::class.java) { offer { copy(folder = "bucket", relpath = "Photos/a.jpg", filename = "a.jpg") }.validate(deskWire) } // no contentSha256
        assertThrows(IllegalArgumentException::class.java) { offer { copy(folder = "bucket", relpath = "../x", contentSha256 = hex64, filename = "x") }.validate(deskWire) }
        assertThrows(IllegalArgumentException::class.java) { offer { copy(folder = "bucket", relpath = "Photos/a.jpg", contentSha256 = hex64, filename = "other.jpg") }.validate(deskWire) }
        offer { copy(clipboard = true, mimeType = "image/png") }.validate(deskWire)
        offer { copy(captureId = "c1", mimeType = "application/pdf") }.validate(deskWire)
    }

    @Test
    fun aMessageForAnotherSessionOrWithBadFieldsIsRefused() {
        fun bad(m: WebRtcFileControl, w: WebRtcWireBinding = deskWire) = assertThrows(IllegalArgumentException::class.java) { m.validate(w) }
        bad(offer(), WebRtcWireBinding("phone", "someone-else", 42, 7))
        bad(offer(), WebRtcWireBinding("phone", "desk", 43, 7))
        bad(offer(), WebRtcWireBinding("phone", "desk", 42, 8))
        bad(offer { copy(protocolVersion = 1) })
        bad(offer { copy(transferId = "not-a-uuid") })
        bad(offer { copy(transferId = id.toString().uppercase()) })
        bad(offer { copy(transferToken = "") })
        bad(offer { copy(blockSize = 2048) })
        bad(offer { copy(totalSize = FileV2.MAX_TRANSFER_BYTES + 1) })
        bad(offer { copy(totalSize = -1) })
        bad(offer { copy(maxFrameBytes = FileV2.FRAME_BYTES_MAX + 1) })
        bad(offer { copy(maxFrameBytes = 10) })
        bad(offer { copy(filename = "a/b") })
        bad(offer { copy(filename = "..") })
        bad(offer { copy(filename = "x".repeat(256)) })
        bad(offer { copy(mtime = null) })
        bad(control(WebRtcFileAction.ACCEPT)) // missing fields
        bad(control(WebRtcFileAction.ACCEPT) { copy(resumeBlock = WebRtcFileControl.MAX_BLOCKS + 1, prefixSha256 = hex64, maxFrameBytes = 61_440) })
        bad(control(WebRtcFileAction.BLOCK_ACK) { copy(blockIndex = 0, sha256 = "zz") })
        bad(control(WebRtcFileAction.BLOCK_ACK) { copy(blockIndex = -1, sha256 = hex64) })
        bad(control(WebRtcFileAction.BLOCK_NACK) { copy(blockIndex = 0, pass = 256) })
        bad(control(WebRtcFileAction.RESUME) { copy(resumeBlock = 1) }) // resume fields come together
        bad(control(WebRtcFileAction.ERROR) { copy(code = FileCodes.INTERNAL, message = "x".repeat(5000)) })
        bad(control(WebRtcFileAction.DONE) { copy(fileHash = hex64, blocks = -1, totalSize = 0) })
    }

    @Test
    fun theWireNamesAreTheContractedOnes() {
        assertEquals(
            listOf("offer", "accept", "block-ack", "block-nack", "pause", "resume", "done", "complete", "cancel", "error"),
            WebRtcFileAction.entries.map { it.wireName },
        )
        assertEquals("desklink.file.control.v2", WebRtcFileControl.CONTROL_MESSAGE_TYPE)
        assertEquals(2, WebRtcFileControl.VERSION)
        assertEquals("desklink-file-data-v2", WebRtcChannel.FILE_DATA.label)
        assertTrue(WebRtcChannel.FILE_DATA.ordered)
        assertNull(WebRtcChannel.FILE_DATA.maxRetransmits)
        assertThrows(IllegalStateException::class.java) { WebRtcFileAction.fromWireName("chunk") }
    }

    @Test
    fun aControlMessageStaysSmallEvenWithEveryOfferField() {
        val big = offer { copy(folder = "bucket", relpath = "a/".repeat(100) + "x.bin", filename = "x.bin", contentSha256 = hex64, mimeType = "image/png", captureId = "c".repeat(64), clipboard = true) }
        val size = big.toJson().toString().toByteArray().size
        assertTrue("$size bytes", size < 2048)
        org.desklink.mobile.webrtc.assertAllCross(emptyList())
    }

    // ======================================================= the golden vectors (~/.cache/w3-golden-vectors.md)

    private val goldenId = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
    private val sha256Abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private fun hexOf(bytes: ByteArray) = bytes.toHex()

    @Test
    fun f1_aWholeThreeByteFileIsOneLastFrameOf75Bytes() {
        val abc = "abc".toByteArray()
        val frame = FileFrame.encode(goldenId, 0, 0, 0, abc, 0, 3, MessageDigest.getInstance("SHA-256").digest(abc))
        assertEquals(75, frame.size)
        assertEquals(
            "444c4632" + "01" + "00" + "0000" + "0f8fad5bd9cb469fa16570867728950e" + "00000000" + "0000000000000000" + "00000003" + "616263" + sha256Abc,
            hexOf(frame),
        )
        val back = FileFrame.decode(frame, FileV2.FRAME_BYTES_PROVEN)
        assertTrue(back.lastOfBlock)
        assertEquals(sha256Abc, hexOf(back.trailer()!!))
    }

    @Test
    fun f2_aMiddleFrameOfPass7InBlock1HasNoTrailer() {
        val frame = FileFrame.encode(goldenId, 7, 1, 0x100000, "hello".toByteArray(), 0, 5, null)
        assertEquals(45, frame.size)
        assertEquals(
            "444c4632" + "00" + "07" + "0000" + "0f8fad5bd9cb469fa16570867728950e" + "00000001" + "0000000000100000" + "00000005" + "68656c6c6f",
            hexOf(frame),
        )
        val back = FileFrame.decode(frame, FileV2.FRAME_BYTES_PROVEN)
        assertEquals(7, back.pass)
        assertEquals(1, back.blockIndex)
        assertNull(back.trailer())
    }

    @Test
    fun f3_theSizeRules() {
        val cap = FileV2.payloadCapacity(FileV2.FRAME_BYTES_PROVEN)
        assertEquals(61_368, cap)
        // The largest frame is 61,440 bytes: 40 header + 61,368 payload + 32 trailer.
        val largest = FileFrame.encode(goldenId, 0, 0, 0, ByteArray(cap), 0, cap, ByteArray(32))
        assertEquals(61_440, largest.size)
        FileFrame.decode(largest, FileV2.FRAME_BYTES_PROVEN)
        // Anything over 61,440 is rejected.
        val over = FileFrame.encode(goldenId, 0, 0, 0, ByteArray(cap + 1), 0, cap + 1, ByteArray(32))
        assertEquals(61_441, over.size)
        assertThrows(IllegalArgumentException::class.java) { FileFrame.decode(over, FileV2.FRAME_BYTES_PROVEN) }
        // A LAST frame with a missing trailer: the length no longer adds up.
        val last = FileFrame.encode(goldenId, 0, 0, 0, "abc".toByteArray(), 0, 3, ByteArray(32))
        assertThrows(IllegalArgumentException::class.java) { FileFrame.decode(last.copyOf(last.size - 32), FileV2.FRAME_BYTES_PROVEN) }
        // A non-LAST frame that ends exactly at a block end.
        val noLastAtEnd = FileFrame.encode(goldenId, 0, 0, FileV2.BLOCK_BYTES - 5L, "hello".toByteArray(), 0, 5, null)
        assertThrows(IllegalArgumentException::class.java) { FileFrame.decode(noLastAtEnd, FileV2.FRAME_BYTES_PROVEN) }
        // Nonzero reserved bytes; unknown flag bits.
        val reserved = FileFrame.encode(goldenId, 0, 0, 0, "abc".toByteArray(), 0, 3, ByteArray(32)).also { it[6] = 1 }
        assertThrows(IllegalArgumentException::class.java) { FileFrame.decode(reserved, FileV2.FRAME_BYTES_PROVEN) }
        val flags = FileFrame.encode(goldenId, 0, 0, 0, "abc".toByteArray(), 0, 3, ByteArray(32)).also { it[4] = 0x03 }
        assertThrows(IllegalArgumentException::class.java) { FileFrame.decode(flags, FileV2.FRAME_BYTES_PROVEN) }
    }

    @Test
    fun h_theHashes() {
        val sha = MessageDigest.getInstance("SHA-256")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", FileHashes.fileHash(emptyList()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hexOf(sha.digest(ByteArray(0))))
        val abcBlock = sha.digest("abc".toByteArray())
        assertEquals(sha256Abc, hexOf(abcBlock))
        // The file hash of "abc" is SHA-256 of the block hash's 32 raw bytes.
        assertEquals("4f8b42c22dd3729b519ba6f68d2da7cc5b2d606d05daed5ad5128cc03e6c6358", FileHashes.fileHash(listOf(abcBlock)))
        assertEquals(1, FileV2.blockCount(FileV2.BLOCK_BYTES.toLong()))
        assertEquals(2, FileV2.blockCount(FileV2.BLOCK_BYTES + 1L))
    }

    @Test
    fun c_theControlVectors() {
        val phoneWire = WebRtcWireBinding("desk-device", "phone-device", 9, 2)
        fun message(action: WebRtcFileAction, block: WebRtcFileControl.() -> WebRtcFileControl = { this }) = WebRtcFileControl(
            action = action, transferId = goldenId.toString(), deviceId = "phone-device", sessionId = 9, connectionGeneration = 2,
            transferToken = "00112233445566778899aabbccddeeff",
        ).block()
        val cases = listOf(
            message(WebRtcFileAction.OFFER) { copy(filename = "big.iso", totalSize = 4_294_967_296, mtime = 1_700_000_000_000, blockSize = 1_048_576, maxFrameBytes = 61_440) },
            message(WebRtcFileAction.OFFER) { copy(filename = "a.jpg", totalSize = 10, mtime = 1_700_000_000_000, blockSize = 1_048_576, maxFrameBytes = 61_440, folder = "bucket", relpath = "Photos/a.jpg", contentSha256 = hex64) },
            message(WebRtcFileAction.OFFER) { copy(filename = "clip.png", totalSize = 10, mtime = 1_700_000_000_000, blockSize = 1_048_576, maxFrameBytes = 61_440, clipboard = true, mimeType = "image/png") },
            message(WebRtcFileAction.ACCEPT) { copy(resumeBlock = 3, prefixSha256 = hex64, maxFrameBytes = 61_440) },
            message(WebRtcFileAction.BLOCK_ACK) { copy(blockIndex = 2, sha256 = hex64) },
            message(WebRtcFileAction.BLOCK_NACK) { copy(blockIndex = 4, pass = 1, reason = "hash-mismatch") },
            message(WebRtcFileAction.PAUSE) { copy(reason = "backlog") },
            message(WebRtcFileAction.RESUME) { copy(resumeBlock = 4, prefixSha256 = hex64, pass = 2) },
            message(WebRtcFileAction.RESUME),
            message(WebRtcFileAction.DONE) { copy(fileHash = hex64, blocks = 5, totalSize = 5L * FileV2.BLOCK_BYTES) },
            message(WebRtcFileAction.COMPLETE) { copy(fileHash = hex64) },
        ) + FileCodes.CANCEL_CODES.map { code -> message(WebRtcFileAction.CANCEL) { copy(code = code) } } +
            FileCodes.ERROR_CODES.map { code -> message(WebRtcFileAction.ERROR) { copy(code = code) } }
        for (m in cases) {
            val json = m.toJson()
            // Compared as parsed values, never as strings.
            assertEquals(9L, json.getLong("sessionId"))
            assertEquals(2L, json.getLong("connectionGeneration"))
            assertEquals("phone-device", json.getString("deviceId"))
            assertEquals("00112233445566778899aabbccddeeff", json.getString("transferToken"))
            assertEquals(m.action.wireName, json.getString("action"))
            val back = WebRtcFileControl.fromJson(JSONObject(json.toString()))
            assertEquals(m, back)
            back.validate(phoneWire)
        }
        val offer = cases[0].toJson()
        assertEquals(4_294_967_296L, offer.getLong("totalSize"))
        assertEquals(1_700_000_000_000L, offer.getLong("mtime"))
        assertEquals(1_048_576, offer.getInt("blockSize"))
        assertEquals(61_440, offer.getInt("maxFrameBytes"))
        // A bare resume carries none of the restart fields.
        assertFalse(cases.single { it.action == WebRtcFileAction.RESUME && it.resumeBlock == null }.toJson().let { it.has("resumeBlock") || it.has("prefixSha256") || it.has("pass") })
        // Every code on the wire has one case; the nack is {blockIndex 4, pass 1, hash-mismatch}.
        assertEquals(4, FileCodes.CANCEL_CODES.size)
        assertEquals(6, FileCodes.ERROR_CODES.size)
    }

    @Test
    fun n_negativeCases() {
        val phoneWire = WebRtcWireBinding("desk-device", "phone-device", 9, 2)
        fun message(action: WebRtcFileAction, block: WebRtcFileControl.() -> WebRtcFileControl = { this }) = WebRtcFileControl(
            action = action, transferId = goldenId.toString(), deviceId = "phone-device", sessionId = 9, connectionGeneration = 2,
            transferToken = "00112233445566778899aabbccddeeff",
        ).block()
        // A resume with one or two of its three fields is rejected.
        for (partial in listOf<WebRtcFileControl.() -> WebRtcFileControl>(
            { copy(resumeBlock = 1) }, { copy(prefixSha256 = hex64) }, { copy(pass = 1) },
            { copy(resumeBlock = 1, prefixSha256 = hex64) }, { copy(resumeBlock = 1, pass = 1) }, { copy(prefixSha256 = hex64, pass = 1) },
        )) assertThrows(IllegalArgumentException::class.java) { message(WebRtcFileAction.RESUME, partial).validate(phoneWire) }
        // Unknown cancel/error codes and unknown pause reasons are accepted, never thrown on.
        message(WebRtcFileAction.CANCEL) { copy(code = "from-the-future") }.validate(phoneWire)
        message(WebRtcFileAction.ERROR) { copy(code = "internal-v3") }.validate(phoneWire)
        message(WebRtcFileAction.CANCEL).validate(phoneWire) // none at all
        message(WebRtcFileAction.PAUSE) { copy(reason = "because") }.validate(phoneWire)
        // ...and an unknown pause reason is a pause by the person.
        assertEquals(FileCodes.USER, FileCodes.pauseReason("because"))
        assertEquals(FileCodes.USER, FileCodes.pauseReason(null))
        assertEquals(FileCodes.BACKLOG, FileCodes.pauseReason(FileCodes.BACKLOG))
    }
}
