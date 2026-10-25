/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.util.ArrayDeque
import java.util.UUID

/**
 * The sender must not be the bottleneck: 256 MiB read in place, hashed per block and framed, through an
 * in-memory pipe whose receiver answers instantly. Prints MB/s and the CPU cost of a block; the bar is
 * deliberately low (a loaded CI machine) while the real figure is typically several hundred MB/s.
 */
class FileSenderBenchmarkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class GeneratedSource(val size: Long) : FileSource {
        override fun stat() = FileSource.Stat(size, 1L)
        override fun open(offset: Long): InputStream = object : InputStream() {
            var position = offset
            override fun read(): Int = if (position >= size) -1 else (position++ and 0xff).toInt()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position >= size) return -1
                val n = minOf(len.toLong(), size - position).toInt()
                java.util.Arrays.fill(b, off, off + n, (position and 0xff).toByte())
                position += n
                return n
            }
        }
    }

    private val wire = WebRtcWireBinding("phone", "desk", 1, 1)

    private fun control(action: WebRtcFileAction, id: String) = WebRtcFileControl(
        action = action, transferId = id, deviceId = "desk", sessionId = 1, connectionGeneration = 1, transferToken = "t".repeat(32),
    )

    @Test
    fun theSenderReadsHashesAndFramesAtSeveralHundredMegabytesASecond() {
        val size = 256L * 1024 * 1024
        val id = UUID.randomUUID().toString()
        val acks = ArrayDeque<WebRtcFileControl>()
        var bytes = 0L
        var frames = 0
        var blocks = 0
        var done = false
        val link = object : FileLink {
            override fun sendControl(control: WebRtcFileControl) {
                if (control.action == WebRtcFileAction.DONE) {
                    done = true
                    acks.add(control.copy(action = WebRtcFileAction.COMPLETE, fileHash = control.fileHash))
                }
            }

            override fun sendFrame(message: ByteArray) {
                val frame = FileFrame.decode(message)
                bytes += frame.length
                frames++
                if (frame.lastOfBlock) {
                    blocks++
                    acks.add(control(WebRtcFileAction.BLOCK_ACK, id).copy(blockIndex = frame.blockIndex, sha256 = frame.trailer()!!.toHex()))
                }
            }

            override fun bufferedAmount() = 0L
        }
        val outcomes = mutableListOf<FileSender.Outcome>()
        val sender = FileSender(
            wire, FileTransferStore(tmp.newFolder()), link, { System.nanoTime() / 1_000_000 },
            object : FileSender.Listener {
                override fun progress(sender: FileSender, bytesDone: Long) = Unit
                override fun ended(sender: FileSender, outcome: FileSender.Outcome, message: String?) { outcomes += outcome }
            },
            id, "t".repeat(32), GeneratedSource(size), "big.bin", size, 1L, emptyMap(), spooled = false,
        )
        sender.start()
        val started = System.nanoTime()
        sender.onControl(
            control(WebRtcFileAction.ACCEPT, id).copy(resumeBlock = 0, prefixSha256 = FileHashes.fileHash(emptyList()), maxFrameBytes = FileV2.FRAME_BYTES_PROVEN),
        )
        while (acks.isNotEmpty()) sender.onControl(acks.poll())
        val seconds = (System.nanoTime() - started) / 1e9
        val mbPerSecond = size / 1048576.0 / seconds
        println("SENDER BENCHMARK: 256 MiB in %.2f s = %.0f MB/s, %.1f ms per 1 MiB block, %d frames".format(seconds, mbPerSecond, seconds * 1000 / blocks, frames))
        File(tmp.root.parentFile, "sender-benchmark.txt").writeText("%.0f MB/s, %.2f ms per block".format(mbPerSecond, seconds * 1000 / blocks))
        assertEquals(256, blocks)
        assertEquals(size, bytes)
        assertTrue(done)
        assertEquals(listOf(FileSender.Outcome.COMPLETED), outcomes)
        assertTrue("the sender managed only %.0f MB/s".format(mbPerSecond), mbPerSecond > 0.15 * BenchBaseline.sha256MBps)
        assertTrue("a block costs %.1f ms of sender CPU".format(seconds * 1000 / blocks), seconds * 1000 / blocks < 1000.0 / (0.15 * BenchBaseline.sha256MBps) * 2 + 15)
    }
}
