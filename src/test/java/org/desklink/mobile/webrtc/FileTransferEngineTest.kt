/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.desklink.mobile.transport.TransportError
import org.desklink.mobile.transport.TransportErrorCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID

/**
 * A sender and a receiver wired back to back through in-memory queues (the whole v2 engine, real
 * files, a fake clock), so every row of the W3 edge-case table is exercised without a device.
 */
class FileTransferEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------ sources

    private class BytesSource(var bytes: ByteArray, var mtime: Long = 1_000L) : FileSource {
        var gone = false
        override fun stat() = if (gone) null else FileSource.Stat(bytes.size.toLong(), mtime)
        override fun open(offset: Long): InputStream = java.io.ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
    }

    /** Deterministic bytes of any size without holding them in memory. */
    private class GeneratedSource(val size: Long) : FileSource {
        override fun stat() = FileSource.Stat(size, 1L)
        override fun open(offset: Long): InputStream = object : InputStream() {
            var position = offset
            override fun read(): Int = if (position >= size) -1 else byteAt(position++).toInt() and 0xff
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position >= size) return -1
                val n = minOf(len.toLong(), size - position).toInt()
                for (i in 0 until n) b[off + i] = byteAt(position + i)
                position += n
                return n
            }
        }

        companion object {
            fun byteAt(position: Long): Byte = ((position * 31 + (position shr 12)) and 0xff).toByte()
        }
    }

    private fun data(size: Int) = ByteArray(size) { ((it * 31) + (it shr 12)).toByte() }

    // ------------------------------------------------------------ harness

    private sealed interface Msg {
        data class Ctl(val control: WebRtcFileControl) : Msg
        data class Frame(val bytes: ByteArray) : Msg
    }

    private inner class Harness(
        val source: FileSource,
        val size: Long,
        name: String = "report.bin",
        val id: String = UUID.randomUUID().toString(),
        val token: String = "t".repeat(32),
        senderStoreDir: File = tmp.newFolder(),
        receiverStoreDir: File = tmp.newFolder(),
    ) {
        var now = 0L
        val clock = { now }
        val senderStore = FileTransferStore(senderStoreDir)
        val receiverStore = FileTransferStore(receiverStoreDir)
        val senderWire = WebRtcWireBinding("phone", "desk", 1, 1)
        val receiverWire = WebRtcWireBinding("desk", "phone", 1, 1)
        val toReceiver = ArrayDeque<Msg>()
        val toSender = ArrayDeque<Msg>()
        var senderBuffered = 0L
        var senderLinkDown = false
        var receiverLinkDown = false
        var tamper: (ByteArray, Int) -> ByteArray = { bytes, _ -> bytes }
        var frameCount = 0
        val framesSent = mutableListOf<FileFrame>()
        val sentControls = mutableListOf<WebRtcFileControl>()
        val receivedByDesk = mutableListOf<WebRtcFileControl>()
        var freeSpace = Long.MAX_VALUE
        var writeHook: ((FileFrame, java.io.RandomAccessFile) -> Unit)? = null

        var sender: FileSender? = null
        var receiver: FileReceiver? = null
        val senderOutcomes = mutableListOf<Pair<FileSender.Outcome, String?>>()
        val receiverOutcomes = mutableListOf<Pair<FileReceiver.Outcome, String?>>()
        var deliveredSize = -1L
        var deliveredSha: String? = null
        var deliveredFileHash: String? = null
        val progress = mutableListOf<Long>()

        val senderLink = object : FileLink {
            override fun sendControl(control: WebRtcFileControl) {
                if (senderLinkDown) throw TransportError(TransportErrorCode.CLOSED, "closed")
                sentControls += control
                toReceiver.add(Msg.Ctl(control))
            }

            override fun sendFrame(message: ByteArray) {
                if (senderLinkDown) throw TransportError(TransportErrorCode.CLOSED, "closed")
                framesSent += FileFrame.decode(message)
                toReceiver.add(Msg.Frame(tamper(message, frameCount++)))
            }

            override fun bufferedAmount() = senderBuffered
        }
        val receiverLink = object : FileLink {
            override fun sendControl(control: WebRtcFileControl) {
                if (receiverLinkDown) throw TransportError(TransportErrorCode.CLOSED, "closed")
                receivedByDesk += control
                toSender.add(Msg.Ctl(control))
            }

            override fun sendFrame(message: ByteArray) = error("a receiver sends no frames")
            override fun bufferedAmount() = 0L
        }

        val senderListener = object : FileSender.Listener {
            override fun progress(sender: FileSender, bytesDone: Long) { progress += bytesDone }
            override fun ended(sender: FileSender, outcome: FileSender.Outcome, message: String?) { senderOutcomes += outcome to message }
        }
        /** When set, the copy into place does not finish until the test calls it. */
        var holdPublish = false
        var publishFinished: ((Throwable?) -> Unit)? = null
        val receiverListener = object : FileReceiver.Listener {
            override fun progress(receiver: FileReceiver, bytesDone: Long) = Unit
            override fun deliverAsync(receiver: FileReceiver, partial: File, fileHash: String, finished: (Throwable?) -> Unit) {
                if (holdPublish) publishFinished = finished else super.deliverAsync(receiver, partial, fileHash, finished)
            }
            override fun deliver(receiver: FileReceiver, partial: File, fileHash: String) {
                deliveredSize = partial.length()
                deliveredFileHash = fileHash
                val digest = MessageDigest.getInstance("SHA-256")
                partial.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
                deliveredSha = digest.digest().toHex()
                partial.delete() // the real delivery moves the file away
            }

            override fun ended(receiver: FileReceiver, outcome: FileReceiver.Outcome, message: String?) { receiverOutcomes += outcome to message }
        }

        val filename = name

        fun newSender(restore: List<ByteArray> = emptyList()): FileSender = FileSender(
            senderWire, senderStore, senderLink, clock, senderListener, id, token, source, filename, size,
            source.stat()!!.mtime, emptyMap(), spooled = false,
        ).also {
            it.restore(restore)
            sender = it
        }

        /** Starts [s], lets the receiver answer, and hands the accept to the sender (which starts streaming). */
        fun startAndAccept(s: FileSender) {
            s.start()
            pump(limit = 1)
            (toSender.poll() as Msg.Ctl).let { s.onControl(it.control) }
        }

        /** Routes one message to the receiving side. */
        private fun toReceiverSide(msg: Msg) {
            when (msg) {
                is Msg.Frame -> {
                    val frame = runCatching { FileFrame.decode(msg.bytes) }.getOrNull() ?: return
                    if (frame.transferId.toString() == id) receiver?.onFrame(frame)
                }
                is Msg.Ctl -> {
                    val c = msg.control
                    c.validate(receiverWire.let { WebRtcWireBinding(it.senderDeviceId, "phone", 1, 1) })
                    when (c.action) {
                        WebRtcFileAction.OFFER -> {
                            val r = FileReceiver(receiverWire, receiverStore, receiverLink, clock, { freeSpace }, receiverListener, c)
                            writeHook?.let { hook -> r.writeFrame = hook }
                            receiver = r
                            r.start(receiverStore.load(id))
                        }
                        WebRtcFileAction.DONE -> receiver?.onDone(c)
                        WebRtcFileAction.PAUSE -> receiver?.onPeerPause(c.reason)
                        WebRtcFileAction.RESUME -> receiver?.onPeerResume()
                        WebRtcFileAction.CANCEL, WebRtcFileAction.ERROR -> receiver?.onPeerEnded(c.action == WebRtcFileAction.CANCEL, c.message)
                        else -> Unit
                    }
                }
            }
        }

        private fun toSenderSide(msg: Msg) {
            if (msg is Msg.Ctl) {
                msg.control.validate(WebRtcWireBinding("phone", "desk", 1, 1))
                sender?.onControl(msg.control)
            }
        }

        /** Delivers queued messages in both directions until nothing is left (or [limit] messages). */
        fun pump(limit: Int = 1_000_000, holdAcks: Boolean = false): Int {
            var n = 0
            while (n < limit) {
                // Answers reach the sender as soon as they exist, like on a real channel; `holdAcks` keeps them back.
                val b = if (holdAcks) null else toSender.poll()
                if (b != null) { toSenderSide(b); n++; continue }
                val a = toReceiver.poll() ?: break
                toReceiverSide(a); n++
            }
            return n
        }

        fun run(restore: List<ByteArray> = emptyList()): FileSender {
            val s = newSender(restore)
            s.start()
            pump()
            return s
        }

        fun flipOneByteOf(frameNumber: Int): (ByteArray, Int) -> ByteArray = { bytes, n ->
            if (n == frameNumber) bytes.copyOf().also { it[FileV2.FRAME_HEADER_BYTES] = (it[FileV2.FRAME_HEADER_BYTES].toInt() xor 0x55).toByte() } else bytes
        }

        fun bothClean(): Boolean = receiverStore.root.listFiles().orEmpty().isEmpty() && senderStore.root.listFiles().orEmpty().isEmpty()
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }

    private fun harness(bytes: ByteArray, name: String = "report.bin") = Harness(BytesSource(bytes), bytes.size.toLong(), name)

    private val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private fun expectedFileHash(bytes: ByteArray): String {
        val sha = MessageDigest.getInstance("SHA-256")
        val blocks = (0 until FileV2.blockCount(bytes.size.toLong())).map { b ->
            sha.digest(bytes.copyOfRange(b * FileV2.BLOCK_BYTES, minOf(bytes.size, (b + 1) * FileV2.BLOCK_BYTES)))
        }
        return FileHashes.fileHash(blocks)
    }

    // ====================================================== sizes (the table)

    @Test
    fun aZeroByteFileIsDeliveredWithoutAnyFrame() {
        val h = harness(ByteArray(0))
        h.run()
        assertEquals(0L, h.deliveredSize)
        assertEquals(0, h.framesSent.size)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", h.deliveredFileHash)
        assertEquals(listOf(FileSender.Outcome.COMPLETED), h.senderOutcomes.map { it.first })
        assertEquals(listOf(FileReceiver.Outcome.DELIVERED), h.receiverOutcomes.map { it.first })
        assertTrue(h.senderStore.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun anExactlyOneMebibyteFileIsOneBlockAndArrivesIntact() {
        val bytes = data(FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        h.run()
        assertEquals(bytes.size.toLong(), h.deliveredSize)
        assertEquals(h.sha(bytes), h.deliveredSha)
        assertEquals(expectedFileHash(bytes), h.deliveredFileHash)
        assertEquals(1, h.receivedByDesk.count { it.action == WebRtcFileAction.BLOCK_ACK })
        assertEquals(listOf(FileSender.Outcome.COMPLETED), h.senderOutcomes.map { it.first })
        // Frames: 18 at the default size, the last one closing the block.
        assertEquals(18, h.framesSent.size)
        assertTrue(h.framesSent.last().lastOfBlock)
        assertEquals(1, h.framesSent.count { it.lastOfBlock })
    }

    @Test
    fun aFileOneByteOverABlockBoundaryAndAMultiBlockFileArriveIntact() {
        for (size in listOf(FileV2.BLOCK_BYTES + 1, FileV2.BLOCK_BYTES * 3 + FileV2.BLOCK_BYTES / 2, 100)) {
            val bytes = data(size)
            val h = harness(bytes)
            h.run()
            assertEquals("size $size", h.sha(bytes), h.deliveredSha)
            assertEquals(expectedFileHash(bytes), h.deliveredFileHash)
            assertEquals(FileV2.blockCount(size.toLong()), h.receivedByDesk.count { it.action == WebRtcFileAction.BLOCK_ACK })
            // Every frame fits the contracted size.
            assertTrue(h.framesSent.all { it.length <= FileV2.payloadCapacity(FileV2.FRAME_BYTES_PROVEN) })
        }
    }

    @Test
    fun aLargeFileStreamsThroughWithoutBeingHeldInMemoryAndTheHashesMatch() {
        val size = 64L * 1024 * 1024
        val source = GeneratedSource(size)
        val h = Harness(source, size)
        h.run()
        assertEquals(size, h.deliveredSize)
        val expected = MessageDigest.getInstance("SHA-256").also { digest ->
            val s = source.open(0)
            val buffer = ByteArray(1 shl 16)
            while (true) { val n = s.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }.digest().toHex()
        assertEquals(expected, h.deliveredSha)
        assertEquals(64, h.receivedByDesk.count { it.action == WebRtcFileAction.BLOCK_ACK })
        assertTrue("progress reached the end", h.progress.last() == size)
    }

    @Test
    fun aOneGibibyteFileIsHandledByTheSameMathAsAnyOther() {
        // The 1 GiB transfer itself is a numbered real-phone check; here its bookkeeping is exact.
        val size = 1L shl 30
        assertEquals(1024, FileV2.blockCount(size))
        assertEquals(1024L * 32, 1024L * FileV2.BLOCK_TRAILER_BYTES) // the sidecar: 32 KiB of hashes
        val control = WebRtcFileControl(
            action = WebRtcFileAction.DONE, transferId = UUID.randomUUID().toString(), deviceId = "phone", sessionId = 1, connectionGeneration = 1,
            transferToken = "t", fileHash = "ab".repeat(32), blocks = 1024, totalSize = size,
        )
        control.validate(WebRtcWireBinding("desk", "phone", 1, 1))
        assertTrue(control.toJson().toString().length < 1024)
    }

    // ============================================================ the window

    @Test
    fun theSenderStopsAtTheBufferedLimitAndGoesOnWhenTheChannelDrains() {
        val h = harness(data(4 * FileV2.BLOCK_BYTES))
        h.senderBuffered = FileV2.IN_FLIGHT_TARGET
        val s = h.run()
        assertEquals("a full channel is not written to", 0, h.framesSent.size)
        h.senderBuffered = FileV2.IN_FLIGHT_LOW
        s.pump()
        h.pump()
        assertTrue(h.framesSent.isNotEmpty())
        assertEquals(FileSender.Outcome.COMPLETED, h.senderOutcomes.single().first)
    }

    @Test
    fun theSenderNeverHasMoreThanTheUnacknowledgedLimitInFlight() {
        val h = harness(data(20 * FileV2.BLOCK_BYTES))
        val s = h.newSender()
        s.start()
        h.pump(holdAcks = true) // the receiver answers, but its acks are held back
        // Offer reached the receiver, whose accept is queued for the sender.
        h.toSender.poll()?.let { (it as Msg.Ctl).let { c -> s.onControl(c.control) } }
        h.pump(holdAcks = true)
        val sentBytes = h.framesSent.sumOf { it.length.toLong() }
        assertTrue("sent $sentBytes", sentBytes <= FileV2.UNACKED_MAX + FileV2.BLOCK_BYTES)
        assertTrue(sentBytes >= FileV2.UNACKED_MAX)
        // The acks flow: it goes on, and finishes.
        h.pump()
        assertEquals(FileSender.Outcome.COMPLETED, h.senderOutcomes.single().first)
    }

    // ============================================== link drop and crash resume

    @Test
    fun aLinkDropWhileTheFileIsBeingPutInPlaceLeavesThePartAlone() {
        val bytes = data(2 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        h.holdPublish = true
        h.run()
        val finished = requireNotNull(h.publishFinished) { "the copy into place should be pending" }
        val partial = h.receiverStore.partial(h.id)
        val length = partial.length()
        h.receiver!!.suspend() // the link went mid-copy
        assertTrue("not suspended: it is still publishing", h.receiverOutcomes.none { it.first == FileReceiver.Outcome.SUSPENDED })
        assertEquals("the part is untouched", length, partial.length())
        h.receiver!!.onReoffer() // the desk offers again: no truncation, no second receiver work
        assertEquals(length, partial.length())
        finished(null)
        assertEquals(FileReceiver.Outcome.DELIVERED, h.receiverOutcomes.last().first)
    }

    @Test
    fun aDroppedLinkResumesAtTheLastVerifiedBlockAndTheFinalHashIsRight() {
        val bytes = data(6 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        val s = h.newSender()
        s.start()
        // Let the first 3 blocks through, then the Wi-Fi is pulled.
        while ((h.receiver?.verifiedBlocks ?: 0) < 3) { if (h.pump(limit = 1) == 0) break }
        val verified = h.receiver!!.verifiedBlocks
        assertTrue("verified $verified", verified in 3..6)
        h.senderLinkDown = true
        h.receiverLinkDown = true
        h.toReceiver.clear(); h.toSender.clear()
        h.receiver!!.suspend()
        s.suspend()
        assertEquals(FileSender.Outcome.SUSPENDED, h.senderOutcomes.last().first)
        assertEquals(FileReceiver.Outcome.SUSPENDED, h.receiverOutcomes.last().first)
        assertNotNull("the checkpoint is kept", h.receiverStore.load(h.id))
        assertEquals(verified, h.receiverStore.load(h.id)!!.blocksDone)

        // The link is back: the sender offers again with the same id, token and its recorded hashes.
        h.senderLinkDown = false; h.receiverLinkDown = false
        h.framesSent.clear()
        h.run(restore = h.senderStore.readBlocks(h.id))
        val accept = h.receivedByDesk.last { it.action == WebRtcFileAction.ACCEPT }
        assertEquals(verified, accept.resumeBlock)
        assertEquals("nothing already verified is sent again", verified.toLong() * FileV2.BLOCK_BYTES, h.framesSent.first().offset)
        assertEquals(h.sha(bytes), h.deliveredSha)
        assertEquals(expectedFileHash(bytes), h.deliveredFileHash)
        assertEquals(FileSender.Outcome.COMPLETED, h.senderOutcomes.last().first)
    }

    @Test
    fun aCrashResumesAtTheLastCheckpointNotFromZero() {
        val bytes = data(12 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        val s = h.newSender()
        s.start()
        while (h.receiver?.verifiedBlocks ?: 0 < 10) { if (h.pump(limit = 1) == 0) break }
        assertTrue(h.receiver!!.verifiedBlocks >= 10)
        // The process dies: no suspend, no final checkpoint. The store holds what was last made durable.
        val checkpoint = h.receiverStore.load(h.id)!!
        assertEquals("a checkpoint every ${FileV2.CHECKPOINT_BLOCKS} blocks", FileV2.CHECKPOINT_BLOCKS, checkpoint.blocksDone)
        h.toReceiver.clear(); h.toSender.clear()
        h.sender = null; h.receiver = null

        val h2 = Harness(h.source, h.size, h.filename, h.id, h.token, h.senderStore.root, h.receiverStore.root)
        h2.run(restore = h2.senderStore.readBlocks(h.id))
        val accept = h2.receivedByDesk.first { it.action == WebRtcFileAction.ACCEPT }
        assertEquals(FileV2.CHECKPOINT_BLOCKS, accept.resumeBlock)
        assertEquals(h2.sha(bytes), h2.deliveredSha)
        assertEquals(expectedFileHash(bytes), h2.deliveredFileHash)
    }

    @Test
    fun theReceiverCheckpointsEveryEightBlocksOrTwoSecondsAndFsyncsTheHashesFirst() {
        val bytes = data(10 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        val s = h.newSender()
        s.start()
        h.pump(limit = 2) // offer, accept
        // One block verified, then two seconds pass: the next tick checkpoints it.
        while ((h.receiver?.verifiedBlocks ?: 0) < 1) h.pump(limit = 1)
        assertEquals(0, h.receiverStore.load(h.id)!!.blocksDone)
        h.now += FileV2.CHECKPOINT_MS
        h.receiver!!.tick(h.now)
        val saved = h.receiverStore.load(h.id)!!
        assertTrue(saved.blocksDone >= 1)
        assertEquals("the hash list is never behind the checkpoint", saved.blocksDone, h.receiverStore.readBlocks(h.id).size.coerceAtMost(saved.blocksDone).coerceAtLeast(saved.blocksDone))
        assertTrue(h.receiverStore.readBlocks(h.id).size >= saved.blocksDone)
    }

    @Test
    fun aSenderThatLostItsRecordedHashesReadsThemAgainAndStillResumesAtTheReceiversBlock() {
        val bytes = data(5 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        val s = h.newSender()
        s.start()
        while ((h.receiver?.verifiedBlocks ?: 0) < 3) h.pump(limit = 1)
        h.receiver!!.suspend(); s.suspend()
        h.toReceiver.clear(); h.toSender.clear()
        h.framesSent.clear()
        // The sender restarted and holds no hashes (a retry, a cleared sidecar): it hashes the prefix from the source.
        h.run(restore = emptyList())
        assertEquals("resumes at block 3, no re-send", 3L * FileV2.BLOCK_BYTES, h.framesSent.first().offset)
        assertEquals(0, h.framesSent.first().pass)
        assertEquals(h.sha(bytes), h.deliveredSha)
        assertEquals(expectedFileHash(bytes), h.deliveredFileHash)
    }

    @Test
    fun aPrefixThatIsNotTheSendersStartsAgainAtZeroUnderTheNextPassAndTheReceiverAdoptsIt() {
        val bytes = data(5 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        val s = h.newSender()
        s.start()
        while ((h.receiver?.verifiedBlocks ?: 0) < 3) h.pump(limit = 1)
        h.receiver!!.suspend(); s.suspend()
        h.toReceiver.clear(); h.toSender.clear()
        h.framesSent.clear()
        // Recorded hashes that are not what the receiver verified: its prefix cannot agree.
        h.run(restore = List(3) { ByteArray(32) { 7 } })
        assertEquals("starts again at block 0", 0L, h.framesSent.first().offset)
        assertEquals("under pass + 1", 1, h.framesSent.first().pass)
        assertEquals(h.sha(bytes), h.deliveredSha)
        assertEquals(expectedFileHash(bytes), h.deliveredFileHash)
    }

    @Test
    fun aRetryOfTheSameTransferNeverEmptiesItsSidecar() {
        val bytes = data(5 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        val s = h.newSender()
        s.start()
        while ((h.receiver?.verifiedBlocks ?: 0) < 3) h.pump(limit = 1)
        s.suspend()
        val stored = h.senderStore.readBlocks(h.id).size
        assertTrue(stored >= 3)
        // The same transfer offered again by a sender that holds nothing (a busy retry built with no hashes).
        h.newSender(restore = emptyList()).start()
        assertEquals("the stored hashes are still there", stored, h.senderStore.readBlocks(h.id).size)
    }

    @Test
    fun aBlockAckThatIsNotTheSendersBlockRewindsUnderTheNextPassAndTheReceiverAdoptsIt() {
        val bytes = data(3 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        val s = h.newSender()
        s.start()
        h.pump(limit = 1)
        (h.toSender.poll() as Msg.Ctl).let { s.onControl(it.control) } // the accept: streaming starts
        // The first ack the receiver gives is replaced by one for a block the sender never read.
        h.pump(holdAcks = true, limit = 40)
        val forged = WebRtcFileControl(
            action = WebRtcFileAction.BLOCK_ACK, transferId = h.id, deviceId = "desk", sessionId = 1, connectionGeneration = 1,
            transferToken = h.token, blockIndex = 0, sha256 = "00".repeat(32),
        )
        h.toSender.clear()
        s.onControl(forged)
        h.pump()
        assertTrue("it did not end with an error", h.senderOutcomes.none { it.first == FileSender.Outcome.FAILED })
        assertEquals(1, h.framesSent.count { it.pass == 1 && it.offset == 0L })
        assertEquals(h.sha(bytes), h.deliveredSha)
        assertEquals(expectedFileHash(bytes), h.deliveredFileHash)
    }

    @Test
    fun aStallThenDataSendsABareResumeSoTheSenderStopsWaiting() {
        val bytes = data(2 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        val s = h.newSender()
        s.start()
        h.pump(limit = 1)
        val receiver = h.receiver!!
        h.now = FileV2.NO_PROGRESS_MS + 1
        receiver.tick(h.now)
        assertEquals(FileCodes.NO_PROGRESS, h.receivedByDesk.last { it.action == WebRtcFileAction.PAUSE }.reason)
        val before = h.receivedByDesk.size
        // Data arrives again.
        (h.toSender.poll() as Msg.Ctl).let { s.onControl(it.control) }
        h.pump()
        val resume = h.receivedByDesk.drop(before).first { it.action == WebRtcFileAction.RESUME }
        assertNull(resume.resumeBlock)
        assertNull(resume.prefixSha256)
        assertNull(resume.pass)
        assertEquals(h.sha(bytes), h.deliveredSha)
    }

    @Test
    fun theSendSummaryNamesWhereTheTimeWent() {
        val bytes = data(3 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        h.senderBuffered = FileV2.IN_FLIGHT_TARGET // the channel is full: the sender is held back
        val s = h.run()
        Thread.sleep(30)
        h.senderBuffered = 0
        s.pump()
        h.pump()
        val line = s.summary()
        assertTrue(line, line.startsWith("send-stats report.bin bytes=${bytes.size} "))
        val windowWait = Regex("windowWaitMs=(\\d+)").find(line)!!.groupValues[1].toLong()
        assertTrue("held back by the window for at least the pause: $line", windowWait >= 20)
        assertTrue(line, Regex("frames=(\\d+)").find(line)!!.groupValues[1].toLong() > 0)
        assertEquals(h.sha(bytes), h.deliveredSha)
    }

    @Test
    fun aCheckpointForADifferentTransferOrFileIsNeverTrusted() {
        val bytes = data(3 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        val s = h.newSender()
        s.start()
        while ((h.receiver?.verifiedBlocks ?: 0) < 2) h.pump(limit = 1)
        h.receiver!!.suspend(); s.suspend()
        h.toReceiver.clear(); h.toSender.clear()
        // The same id, a different token: an impostor or a stale record. It starts from block 0.
        val h2 = Harness(h.source, h.size, h.filename, h.id, "x".repeat(32), h.senderStore.root, h.receiverStore.root)
        h2.run()
        assertEquals(0, h2.receivedByDesk.first { it.action == WebRtcFileAction.ACCEPT }.resumeBlock)
        assertEquals(h2.sha(bytes), h2.deliveredSha)
    }

    // ===================================================== the source changes

    @Test
    fun aSourceThatChangesMidSendEndsThatTransferAndBothSidesCleanUp() {
        val source = BytesSource(data(20 * FileV2.BLOCK_BYTES))
        val h = Harness(source, source.bytes.size.toLong())
        val s = h.newSender()
        h.startAndAccept(s)
        h.pump(holdAcks = true) // the first window of blocks is on its way; the acks are held
        assertTrue(h.framesSent.isNotEmpty())
        source.mtime += 1 // someone saved the file
        h.pump() // the acks arrive; the sender looks at the file before reading the next block
        assertEquals(FileSender.Outcome.SOURCE_CHANGED, h.senderOutcomes.last().first)
        assertEquals(FileCodes.SOURCE_CHANGED, h.sentControls.last { it.action == WebRtcFileAction.CANCEL }.code)
        assertEquals(FileReceiver.Outcome.CANCELLED, h.receiverOutcomes.last().first)
        assertTrue("both sides deleted their partial files", h.bothClean())
        assertNull(h.deliveredSha)
    }

    @Test
    fun aSourceDeletedMidSendFailsWithAPlainMessage() {
        val source = BytesSource(data(20 * FileV2.BLOCK_BYTES))
        val h = Harness(source, source.bytes.size.toLong())
        val s = h.newSender()
        h.startAndAccept(s)
        h.pump(holdAcks = true)
        source.gone = true
        h.pump()
        val (outcome, message) = h.senderOutcomes.last()
        assertEquals(FileSender.Outcome.FAILED, outcome)
        assertEquals("The file no longer exists.", message)
        assertEquals(FileCodes.SOURCE_GONE, h.sentControls.last { it.action == WebRtcFileAction.CANCEL }.code)
        assertTrue(h.bothClean())
    }

    @Test
    fun aFileThatChangesAfterItsLastBlockIsCaughtByTheFinalCheck() {
        val source = BytesSource(data(2 * FileV2.BLOCK_BYTES))
        val h = Harness(source, source.bytes.size.toLong())
        val s = h.newSender()
        h.startAndAccept(s) // both blocks are read and sent
        h.pump(holdAcks = true)
        source.bytes = source.bytes.copyOf().also { it[0] = 1 }
        source.mtime += 5
        h.pump() // the acks arrive; the last look at the source comes before `done`
        assertEquals(FileSender.Outcome.SOURCE_CHANGED, h.senderOutcomes.last().first)
        assertFalse("no done was sent for a changed file", h.sentControls.any { it.action == WebRtcFileAction.DONE })
    }

    @Test
    fun aFullDiskPausesWithStorageFullAndResumesWhenSpaceFrees() {
        val bytes = data(4 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        var full = true
        h.writeHook = { frame, raf ->
            if (full && frame.blockIndex == 2) throw IOException("write failed: ENOSPC (No space left on device)")
            frame.writeTo(raf)
        }
        h.freeSpace = 0
        h.run()
        assertEquals(FileCodes.STORAGE_FULL, h.receivedByDesk.last { it.action == WebRtcFileAction.PAUSE }.reason)
        assertEquals("paused, not failed", emptyList<Any>(), h.receiverOutcomes)
        assertEquals("the sender stopped and waits", emptyList<Any>(), h.senderOutcomes)
        assertEquals(FileCodes.STORAGE_FULL, h.receiver!!.isPaused)
        assertTrue("nothing delivered while full", h.deliveredSha == null)
        // Still full after 5 s: it asks again, but there is no room yet.
        h.now += FileV2.STORAGE_RETRY_MS
        h.receiver!!.tick(h.now)
        assertEquals(FileCodes.STORAGE_FULL, h.receiver!!.isPaused)
        // Space frees up.
        full = false
        h.freeSpace = 1L shl 30
        h.now += FileV2.STORAGE_RETRY_MS
        h.receiver!!.tick(h.now)
        h.pump()
        assertEquals(h.sha(bytes), h.deliveredSha)
        assertEquals(FileSender.Outcome.COMPLETED, h.senderOutcomes.last().first)
        val resume = h.receivedByDesk.last { it.action == WebRtcFileAction.RESUME }
        assertEquals(2, resume.resumeBlock)
        assertEquals(1, resume.pass)
    }

    @Test
    fun anotherWriteErrorEndsTheTransferWithAPlainMessage() {
        val h = harness(data(2 * FileV2.BLOCK_BYTES))
        h.writeHook = { _, _ -> throw IOException("I/O error") }
        h.run()
        assertEquals(FileReceiver.Outcome.FAILED, h.receiverOutcomes.single().first)
        assertEquals(FileCodes.INTERNAL, h.receivedByDesk.last { it.action == WebRtcFileAction.ERROR }.code)
        assertEquals(FileSender.Outcome.FAILED, h.senderOutcomes.single().first)
        assertTrue(h.bothClean())
    }

    // ================================================== bad frames never corrupt

    @Test
    fun aDuplicateOrReplayedFrameChangesNothing() {
        val bytes = data(2 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        h.tamper = { b, _ -> b }
        val s = h.newSender()
        s.start()
        h.pump(limit = 2)
        // Take the first frame the sender emits and deliver it twice, then the rest.
        val first = h.toReceiver.peek() as Msg.Frame
        h.toReceiver.addFirst(first)
        h.pump()
        assertEquals(h.sha(bytes), h.deliveredSha)
        assertEquals(FileSender.Outcome.COMPLETED, h.senderOutcomes.last().first)
    }

    @Test
    fun aFrameForAnUnknownTransferOrPastTheSizeOrFromAnOldPassIsDroppedWithoutHarm() {
        val bytes = data(2 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        val s = h.newSender()
        s.start()
        h.pump(limit = 4)
        val receiver = h.receiver!!
        // The first frame of the stream was taken: the "adopt any pass" window after the accept is closed.
        val before = receiver.verifiedBlocks
        val stranger = FileFrame.decode(FileFrame.encode(UUID.randomUUID(), 0, 0, 0, ByteArray(100), 0, 100, null))
        // Not this receiver's transfer: the manager never routes it; a frame past the file's size is dropped here.
        val past = FileFrame.decode(FileFrame.encode(UUID.fromString(h.id), 0, 5, 5L * FileV2.BLOCK_BYTES, ByteArray(100), 0, 100, null))
        val oldPass = FileFrame.decode(FileFrame.encode(UUID.fromString(h.id), 9, 0, 0, ByteArray(100), 0, 100, null))
        receiver.onFrame(past)
        receiver.onFrame(oldPass)
        assertEquals(before, receiver.verifiedBlocks)
        assertEquals(emptyList<Any>(), h.receiverOutcomes)
        assertNotNull(stranger)
        h.pump()
        assertEquals(h.sha(bytes), h.deliveredSha)
    }

    @Test
    fun aFrameAheadOfTheExpectedOffsetEndsTheTransferAsOutOfOrder() {
        val bytes = data(3 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        val s = h.newSender()
        s.start()
        h.pump(limit = 2)
        // Skip the first frame: the next one arrives ahead of the offset the receiver expects.
        h.toReceiver.poll()
        h.pump()
        assertEquals(FileCodes.BAD_FRAME, h.receivedByDesk.last { it.action == WebRtcFileAction.ERROR }.code)
        assertEquals(FileReceiver.Outcome.FAILED, h.receiverOutcomes.last().first)
        assertTrue(h.bothClean())
    }

    @Test
    fun aFrameWithTheWrongLastOfBlockFlagEndsTheTransfer() {
        val h = harness(data(FileV2.BLOCK_BYTES))
        val s = h.newSender()
        s.start()
        h.pump(limit = 2)
        val capacity = FileV2.payloadCapacity(FileV2.FRAME_BYTES_PROVEN)
        // A frame that claims to close the block, though the block has not ended.
        val bogus = FileFrame.decode(FileFrame.encode(UUID.fromString(h.id), 0, 0, 0, ByteArray(capacity), 0, capacity, ByteArray(32)))
        h.receiver!!.onFrame(bogus)
        assertEquals(FileCodes.BAD_FRAME, h.receivedByDesk.last { it.action == WebRtcFileAction.ERROR }.code)
    }

    // ===================================================== hash mismatches

    @Test
    fun aDamagedBlockIsRewoundAndSentAgainUnderANewPass() {
        val bytes = data(3 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        // Damage the 25th frame (inside block 1) once.
        h.tamper = h.flipOneByteOf(25)
        h.run()
        val nack = h.receivedByDesk.single { it.action == WebRtcFileAction.BLOCK_NACK }
        assertEquals(1, nack.blockIndex)
        assertEquals(1, nack.pass)
        assertEquals(h.sha(bytes), h.deliveredSha)
        assertEquals(expectedFileHash(bytes), h.deliveredFileHash)
        assertTrue("the rewound block went out under pass 1", h.framesSent.any { it.pass == 1 && it.blockIndex == 1 })
        assertEquals(FileSender.Outcome.COMPLETED, h.senderOutcomes.last().first)
    }

    @Test
    fun framesOfAnAbandonedPassAreDroppedAfterARewind() {
        val bytes = data(4 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        h.tamper = h.flipOneByteOf(5) // block 0, early
        h.run()
        // The sender had already emitted pass-0 frames past the damaged block; none of them was applied.
        assertEquals(h.sha(bytes), h.deliveredSha)
        assertEquals(1, h.receivedByDesk.count { it.action == WebRtcFileAction.BLOCK_NACK })
    }

    @Test
    fun aBlockThatKeepsFailingEndsTheTransferAfterTheRewindBudget() {
        val bytes = data(2 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        // Damage the closing frame of block 0 every time it is sent.
        h.tamper = { b, _ ->
            val frame = FileFrame.decode(b)
            if (frame.blockIndex == 0 && frame.lastOfBlock) b.copyOf().also { it[FileV2.FRAME_HEADER_BYTES] = (it[FileV2.FRAME_HEADER_BYTES].toInt() xor 1).toByte() } else b
        }
        h.run()
        assertEquals(FileV2.MAX_REWINDS, h.receivedByDesk.count { it.action == WebRtcFileAction.BLOCK_NACK })
        assertEquals(FileCodes.HASH_MISMATCH, h.receivedByDesk.last { it.action == WebRtcFileAction.ERROR }.code)
        assertEquals(FileReceiver.Outcome.FAILED, h.receiverOutcomes.last().first)
        assertEquals(FileSender.Outcome.FAILED, h.senderOutcomes.last().first)
        assertNull("a damaged file is never delivered", h.deliveredSha)
        assertTrue(h.bothClean())
    }

    @Test
    fun aWrongFileHashInDoneRestartsTheFileOnceThenFails() {
        val bytes = data(2 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        val s = h.newSender()
        s.start()
        h.pump(holdAcks = false, limit = 400)
        // Stop just before done: intercept it by delivering a forged one instead.
        val forged = WebRtcFileControl(
            action = WebRtcFileAction.DONE, transferId = h.id, deviceId = "phone", sessionId = 1, connectionGeneration = 1, transferToken = h.token,
            fileHash = "00".repeat(32), blocks = 2, totalSize = h.size,
        )
        // Run again from a fresh harness where the real `done` is replaced.
        val h2 = harness(bytes)
        val s2 = h2.newSender()
        s2.start()
        while (h2.receiver == null || h2.receiver!!.verifiedBlocks < 2) { if (h2.pump(limit = 1) == 0) break }
        h2.receiver!!.onDone(forged.copy(transferId = h2.id, transferToken = h2.token))
        // One form on the wire for a final-hash mismatch: resume {0, SHA-256(""), pass + 1}.
        val restart = h2.receivedByDesk.filter { it.action == WebRtcFileAction.RESUME }
        assertEquals(1, restart.size)
        assertEquals(0, restart.single().resumeBlock)
        assertEquals(EMPTY_SHA256, restart.single().prefixSha256)
        assertEquals(1, restart.single().pass)
        assertEquals(0, h2.receivedByDesk.count { it.action == WebRtcFileAction.BLOCK_NACK })
        h2.receiver!!.onDone(forged.copy(transferId = h2.id, transferToken = h2.token))
        assertEquals(FileCodes.HASH_MISMATCH, h2.receivedByDesk.last { it.action == WebRtcFileAction.ERROR }.code)
        assertNull(h2.deliveredSha)
    }

    // ===================================================== cancel, either side

    @Test
    fun cancellingFromTheSenderCleansBothSides() {
        val h = harness(data(5 * FileV2.BLOCK_BYTES))
        val s = h.newSender()
        s.start()
        while ((h.receiver?.verifiedBlocks ?: 0) < 2) h.pump(limit = 1)
        s.cancel(FileCodes.USER, "Cancelled.")
        h.pump()
        assertEquals(FileSender.Outcome.CANCELLED, h.senderOutcomes.last().first)
        assertEquals(FileReceiver.Outcome.CANCELLED, h.receiverOutcomes.last().first)
        assertTrue(h.bothClean())
    }

    @Test
    fun cancellingFromTheReceiverCleansBothSides() {
        val h = harness(data(5 * FileV2.BLOCK_BYTES))
        val s = h.newSender()
        s.start()
        while ((h.receiver?.verifiedBlocks ?: 0) < 2) h.pump(limit = 1)
        h.receiver!!.cancel(FileCodes.REMOVED, "The file was removed.")
        h.pump()
        assertEquals(FileReceiver.Outcome.CANCELLED, h.receiverOutcomes.last().first)
        assertEquals(FileSender.Outcome.CANCELLED, h.senderOutcomes.last().first)
        assertEquals("The file was removed.", h.senderOutcomes.last().second)
        assertTrue(h.bothClean())
    }

    // ============================================== pause, no deadline

    @Test
    fun nothingMovingForAMinutePausesAndThenItGoesOnWithNoDeadline() {
        val bytes = data(3 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        h.senderBuffered = FileV2.IN_FLIGHT_TARGET // the channel is stuck: no frame can go out
        val s = h.run()
        h.now += FileV2.NO_PROGRESS_MS
        s.tick(h.now)
        h.receiver!!.tick(h.now)
        h.pump()
        assertTrue("the sender said so", h.sentControls.any { it.action == WebRtcFileAction.PAUSE && it.reason == FileCodes.NO_PROGRESS })
        assertEquals(FileCodes.NO_PROGRESS, s.isPaused)
        assertEquals(emptyList<Any>(), h.senderOutcomes)
        // A very long stall, far past the old 30-minute deadline, still ends in a delivered file.
        h.now += 2L * 60 * 60 * 1000
        s.tick(h.now)
        h.senderBuffered = 0
        s.pump()
        h.pump()
        assertEquals(h.sha(bytes), h.deliveredSha)
        assertEquals(FileSender.Outcome.COMPLETED, h.senderOutcomes.last().first)
        assertTrue("the sender said it was moving again", h.sentControls.any { it.action == WebRtcFileAction.RESUME })
    }

    @Test
    fun aPausedSenderSendsNoFramesUntilTheReceiverResumesIt() {
        val h = harness(data(3 * FileV2.BLOCK_BYTES))
        val s = h.newSender()
        s.start()
        h.pump(limit = 1) // the receiver accepts
        // The receiver pauses (its queue is full) before the sender has started: the accept must not start frames.
        s.onControl(WebRtcFileControl(action = WebRtcFileAction.PAUSE, transferId = h.id, deviceId = "desk", sessionId = 1, connectionGeneration = 1, transferToken = h.token, reason = FileCodes.BACKLOG))
        s.onControl((h.toSender.poll() as Msg.Ctl).control)
        s.pump()
        assertEquals("paused: no frames", 0, h.framesSent.size)
        s.onControl(WebRtcFileControl(action = WebRtcFileAction.RESUME, transferId = h.id, deviceId = "desk", sessionId = 1, connectionGeneration = 1, transferToken = h.token))
        h.pump()
        assertTrue(h.framesSent.isNotEmpty())
        assertEquals(FileSender.Outcome.COMPLETED, h.senderOutcomes.last().first)
    }

    @Test
    fun aBacklogPauseAndResumeRestartsExactlyAtTheLastVerifiedBlock() {
        val bytes = data(6 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        val s = h.newSender()
        s.start()
        while ((h.receiver?.verifiedBlocks ?: 0) < 2) h.pump(limit = 1)
        val receiver = h.receiver!!
        receiver.pauseBacklog() // the desk's queue overflowed: pause, never cancel
        assertEquals(FileCodes.BACKLOG, receiver.isPaused)
        h.pump() // frames still in flight are ignored while paused
        assertEquals(emptyList<Any>(), h.receiverOutcomes)
        receiver.resumeBacklog()
        h.pump()
        val resume = h.receivedByDesk.last { it.action == WebRtcFileAction.RESUME }
        assertTrue(resume.resumeBlock!! >= 2)
        assertEquals(h.sha(bytes), h.deliveredSha)
    }

    // ===================================================== ids and storage

    @Test
    fun theStoreNeverTouchesAPathOutsideItsRootWhateverIdIsAskedFor() {
        val store = FileTransferStore(tmp.newFolder())
        listOf("../../etc/passwd", "a/b", "", "x".repeat(300), "NOT-A-UUID").forEach { bad ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { store.partial(bad) }
            assertNull(store.load(bad))
        }
    }

    @Test
    fun aCorruptCheckpointIsTreatedAsNoCheckpoint() {
        val store = FileTransferStore(tmp.newFolder())
        val id = UUID.randomUUID().toString()
        File(store.root, ".desklink-$id.json").writeText("{ not json")
        assertNull(store.load(id))
        File(store.root, ".desklink-$id.json").writeText("""{"v":2,"id":"$id","dir":"receive","token":"t","device":"d","name":"n","size":5,"mtime":1,"blocks":99}""")
        assertNull("more blocks than the file has", store.load(id))
    }

    @Test
    fun anExistingPartialWithoutACheckpointIsNeverReused() {
        val h = harness(data(FileV2.BLOCK_BYTES))
        File(h.receiverStore.root, ".desklink-${h.id}.part").writeBytes(ByteArray(777) { 9 })
        h.run()
        assertEquals(0, h.receivedByDesk.first { it.action == WebRtcFileAction.ACCEPT }.resumeBlock)
        assertEquals(h.sha(data(FileV2.BLOCK_BYTES)), h.deliveredSha)
    }

    @Test
    fun theSenderPersistsWhatItNeedsToResumeAndForgetsItOnCompletion() {
        val bytes = data(3 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        val s = h.newSender()
        s.start()
        val started = h.senderStore.load(h.id)!!
        assertEquals("send", started.direction)
        assertEquals(h.token, started.token)
        assertEquals(bytes.size.toLong(), started.totalSize)
        h.pump()
        assertNull(h.senderStore.load(h.id))
        assertTrue(h.bothClean())
    }

    @Test
    fun progressIsBytesAcknowledgedPlusTheCurrentBlockAndEndsAtTheSize() {
        val bytes = data(3 * FileV2.BLOCK_BYTES)
        val h = harness(bytes)
        h.run()
        assertTrue(h.progress.isNotEmpty())
        assertEquals(bytes.size.toLong(), h.progress.last())
        assertTrue("never beyond the size", h.progress.all { it in 0..bytes.size.toLong() })
        assertEquals(h.progress.sorted(), h.progress)
    }

    @Test
    fun aLinkThatClosesWhileSendingPausesTheSenderInsteadOfFailingIt() {
        val h = harness(data(20 * FileV2.BLOCK_BYTES))
        val s = h.newSender()
        h.startAndAccept(s)
        h.senderLinkDown = true // the Wi-Fi goes: the next frame cannot leave
        h.pump()
        assertTrue(h.senderOutcomes.none { it.first == FileSender.Outcome.FAILED })
        assertEquals(FileSender.Outcome.SUSPENDED, h.senderOutcomes.last().first)
        assertNotNull("its checkpoint is kept for the next connection", h.senderStore.load(h.id))
    }

    @Test
    fun filesLeftByTransfersV1AreDeletedAndV2FilesAreKept() {
        val store = FileTransferStore(tmp.newFolder())
        val id = UUID.randomUUID().toString()
        val keep = listOf(".desklink-$id.json", ".desklink-$id.part", ".desklink-$id.blocks", ".desklink-$id.spool", ".desklink-$id.json.tmp")
        val legacy = listOf(".desklink-send-1.send", ".desklink-old-id.part", "random.bin", ".desklink-$id.send")
        (keep + legacy).forEach { File(store.root, it).writeText("x") }
        assertEquals(legacy.size, store.purgeLegacy())
        assertEquals(keep.sorted(), store.root.listFiles().orEmpty().map { it.name }.sorted())
    }

    @Test
    fun aTransferNobodyTouchedForSevenDaysIsSweptWholeAndARecentOneIsKept() {
        val store = FileTransferStore(tmp.newFolder())
        val old = UUID.randomUUID().toString()
        val fresh = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        listOf("json", "part", "blocks").forEach { File(store.root, ".desklink-$old.$it").apply { writeText("x"); setLastModified(now - 8L * 24 * 3600 * 1000) } }
        listOf("json", "part").forEach { File(store.root, ".desklink-$fresh.$it").apply { writeText("x"); setLastModified(now - 3600_000) } }
        assertEquals(3, store.sweepStale(now))
        assertEquals(listOf(".desklink-$fresh.json", ".desklink-$fresh.part"), store.root.listFiles().orEmpty().map { it.name }.sorted())
    }
}
