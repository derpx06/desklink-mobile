/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * The receive path of one transfer, frames handed in as fast as they can be made: 256 MiB written to a real file,
 * hashed per block, acknowledged and checkpointed. It prints the stage split (the same line a debug build logs
 * at the end of every receive) and requires a bar a loaded machine still clears. If this is far above what the phone
 * measures, the phone's limit is the network or the sender, not this code.
 */
class FileReceiverBenchmarkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun theReceiverWritesHashesAndCheckpointsAtSeveralHundredMegabytesASecond() {
        val size = 256L * 1024 * 1024
        val id = UUID.randomUUID().toString()
        val wire = WebRtcWireBinding("phone", "desk", 1, 1)
        val link = object : FileLink {
            override fun sendControl(control: WebRtcFileControl) = Unit
            override fun sendFrame(message: ByteArray) = Unit
            override fun bufferedAmount() = 0L
        }
        var delivered = false
        val receiver = FileReceiver(
            wire, FileTransferStore(tmp.newFolder()), link, { System.nanoTime() / 1_000_000 }, { Long.MAX_VALUE },
            object : FileReceiver.Listener {
                override fun progress(receiver: FileReceiver, bytesDone: Long) = Unit
                override fun deliver(receiver: FileReceiver, partial: File, fileHash: String) { delivered = true }
                override fun ended(receiver: FileReceiver, outcome: FileReceiver.Outcome, message: String?) = Unit
            },
            WebRtcFileControl(
                action = WebRtcFileAction.OFFER, transferId = id, deviceId = "desk", sessionId = 1, connectionGeneration = 1, transferToken = "t".repeat(32),
                filename = "bench.bin", totalSize = size, mtime = 1, blockSize = FileV2.BLOCK_BYTES, maxFrameBytes = FileV2.FRAME_BYTES_PROVEN,
            ),
        )
        receiver.start(null)
        val uuid = UUID.fromString(id)
        val capacity = FileV2.payloadCapacity(FileV2.FRAME_BYTES_PROVEN)
        val block = ByteArray(FileV2.BLOCK_BYTES) { (it * 31).toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(block)
        val hashes = ArrayList<ByteArray>()
        var fed = 0L
        var feedNs = 0L
        for (b in 0 until (size / FileV2.BLOCK_BYTES).toInt()) {
            // Frames are made outside the clock: only what the receiver does with them is measured.
            val frames = ArrayList<FileFrame>()
            var at = 0
            while (at < block.size) {
                val n = minOf(capacity, block.size - at)
                val last = at + n == block.size
                frames += FileFrame.decode(FileFrame.encode(uuid, 0, b, b.toLong() * FileV2.BLOCK_BYTES + at, block, at, n, if (last) hash else null))
                at += n
            }
            hashes += hash
            val t = System.nanoTime()
            frames.forEach(receiver::onFrame)
            feedNs += System.nanoTime() - t
            fed += block.size
        }
        receiver.onDone(
            WebRtcFileControl(
                action = WebRtcFileAction.DONE, transferId = id, deviceId = "desk", sessionId = 1, connectionGeneration = 1, transferToken = "t".repeat(32),
                fileHash = FileHashes.fileHash(hashes), blocks = hashes.size, totalSize = size,
            ),
        )
        val mbps = fed / 1e6 / (feedNs / 1e9)
        println("receiver benchmark: ${"%.0f".format(mbps)} MB/s; ${receiver.summary()}")
        assertTrue(delivered)
        assertEquals(hashes.size, receiver.verifiedBlocks)
        assertTrue("the receive path must not be the bottleneck: $mbps MB/s", mbps > 60)
    }
}
