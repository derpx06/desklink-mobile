/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.helpers.TransferCheckpoint
import org.desklink.mobile.helpers.TransferCheckpointStore
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class WebRtcFileTransferManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var executor: ExecutorService

    /** The phone. Session 42, generation 7. */
    private val wire = WebRtcWireBinding(
        senderDeviceId = "phone",
        peerDeviceId = "desktop",
        sessionId = 42,
        generation = 7,
    )

    private val root: File get() = context.filesDir.resolve("webrtc-transfers")
    private val prefs get() = context.getSharedPreferences(CHECKPOINT_PREFS, Context.MODE_PRIVATE)
    private val store get() = TransferCheckpointStore(context)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetState()
        executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        resetState()
    }

    private fun resetState() {
        prefs.edit().clear().commit()
        context.getSharedPreferences(PEER_CHECKPOINT_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
    }

    // ---------------------------------------------------------------- rigs

    /** One manager, the device it dispatches to, and everything it sent. */
    private inner class Rig(ctx: Context, var wire: WebRtcWireBinding) {
        val delivered = CopyOnWriteArrayList<NetworkPacket>()
        val device: Device = mockk<Device>(relaxed = true).also { mock ->
            every { mock.onWebRtcPacketReceived(any()) } answers {
                delivered += firstArg<NetworkPacket>()
            }
        }
        val log = CopyOnWriteArrayList<OutboundWebRtcFileMessage>()
        val outbox = LinkedBlockingQueue<OutboundWebRtcFileMessage>()

        @Volatile
        var failSends = false
        val manager = WebRtcFileTransferManager(ctx, device) {
            if (failSends) throw IllegalStateException("WebRTC file transport is not ready")
            outbox.add(it)
            log.add(it)
        }.apply {
            // Crash-recovery tests observe precise resume points, so every
            // chunk is made durable here (production batches every 8 MiB).
            durableEveryBytes = 1
        }
        val controls: List<WebRtcFileControl>
            get() = log.filterIsInstance<OutboundWebRtcFileMessage.Control>().map { it.value }
        val actions: List<WebRtcFileAction> get() = controls.map { it.action }
        val chunks: List<WebRtcFileChunk>
            get() = log.filterIsInstance<OutboundWebRtcFileMessage.Chunk>()
                .map { WebRtcFileChunk.decode(it.value) }
    }

    private fun rig(w: WebRtcWireBinding = wire) = Rig(context, w)

    /** A second device with its own private storage, for phone <-> desktop loopback tests. */
    private fun peerRig(w: WebRtcWireBinding): Rig {
        val dir = tmp.newFolder()
        val peerPrefs = context.getSharedPreferences(PEER_CHECKPOINT_PREFS, Context.MODE_PRIVATE)
        val peerContext = mockk<Context>()
        every { peerContext.applicationContext } returns peerContext
        every { peerContext.filesDir } returns dir
        every { peerContext.getSharedPreferences(any(), any()) } returns peerPrefs
        return Rig(peerContext, w)
    }

    private fun mirrored(w: WebRtcWireBinding) =
        WebRtcWireBinding(w.peerDeviceId, w.senderDeviceId, w.sessionId, w.generation)

    // ------------------------------------------------------------- builders

    private fun payload(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun ctl(
        action: WebRtcFileAction,
        id: String = ID,
        token: String = TOKEN,
        offset: Long = 0,
        filename: String? = null,
        totalSize: Long? = null,
        sha256: String? = null,
        error: String? = null,
        w: WebRtcWireBinding = wire,
    ) = WebRtcFileControl(
        protocolVersion = 1,
        action = action,
        transferId = id,
        deviceId = w.peerDeviceId,
        sessionId = w.sessionId,
        connectionGeneration = w.generation,
        transferToken = token,
        filename = filename,
        totalSize = totalSize,
        sha256 = sha256,
        offset = offset,
        chunkSize = WebRtcFileControl.MAX_CHUNK_BYTES,
        error = error,
    )

    private fun offer(
        bytes: ByteArray,
        id: String = ID,
        token: String = TOKEN,
        filename: String = "report.txt",
        size: Long = bytes.size.toLong(),
        sha256: String = sha256(bytes),
        w: WebRtcWireBinding = wire,
    ) = ctl(WebRtcFileAction.OFFER, id, token, 0, filename, size, sha256, null, w)

    private fun chunk(id: String, token: String, offset: Long, data: ByteArray) =
        WebRtcFileChunk(id, token, offset, data).encode()

    /** Feeds `bytes[from, to)` to the receiver in protocol-sized chunks. */
    private fun feed(
        rig: Rig,
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
        id: String = ID,
        token: String = TOKEN,
        w: WebRtcWireBinding = rig.wire,
    ) {
        var offset = from
        while (offset < to) {
            val end = minOf(offset + CHUNK, to)
            rig.manager.handleChunk(
                w,
                chunk(id, token, offset.toLong(), bytes.copyOfRange(offset, end)),
            )
            offset = end
        }
    }

    private fun drain(packet: NetworkPacket): ByteArray {
        val bytes = packet.payload!!.inputStream!!.readBytes()
        packet.payload!!.close()
        return bytes
    }

    private fun leftovers(): List<String> = root.listFiles().orEmpty().map { it.name }.sorted()

    private fun partial(id: String = ID) = root.resolve(".desklink-$id.part")

    private fun snapshot(): Map<String, Any?> = mapOf(
        "files" to context.filesDir.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(context.filesDir).path to it.readBytes().contentHashCode() },
        "prefs" to prefs.all.toMap(),
    )

    private fun assertRejected(block: () -> Unit) {
        assertThrows(RuntimeException::class.java) { block() }
    }

    /** The message must be refused and must leave no trace: files, checkpoints, replies, deliveries. */
    private fun assertRejectedWithoutMutation(rig: Rig, block: () -> Unit) {
        val before = snapshot()
        val sent = rig.log.size
        val delivered = rig.delivered.size
        assertRejected(block)
        assertEquals("storage changed", before, snapshot())
        assertEquals("a reply was sent", sent, rig.log.size)
        assertEquals("a file was delivered", delivered, rig.delivered.size)
    }

    // ------------------------------------------------------- sender helpers

    private class Recorder : Device.SendPacketStatusCallback() {
        val successes = AtomicInteger()
        val failures = CopyOnWriteArrayList<Throwable>()
        val progress = CopyOnWriteArrayList<Int>()
        override fun onSuccess() {
            successes.incrementAndGet()
        }

        override fun onFailure(e: Throwable) {
            failures.add(e)
        }

        override fun onPayloadProgressChanged(percent: Int) {
            progress.add(percent)
        }
    }

    private fun sharePacket(
        bytes: ByteArray,
        filename: String = "photo.bin",
        transferId: String? = "send-1",
        closed: AtomicBoolean = AtomicBoolean(),
    ) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
        this["filename"] = filename
        transferId?.let { this["transferId"] = it }
        payload = NetworkPacket.Payload(ByteArrayInputStream(bytes), bytes.size.toLong()) {
            closed.set(true)
        }
    }

    private fun startSend(rig: Rig, packet: NetworkPacket, callback: Recorder): Future<Boolean> =
        executor.submit(Callable { rig.manager.sendPayload(rig.wire, packet, callback) })

    private fun awaitOffer(rig: Rig): WebRtcFileControl {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            rig.controls.firstOrNull { it.action == WebRtcFileAction.OFFER }?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("the sender never offered the file")
    }

    private fun deliver(message: OutboundWebRtcFileMessage, to: Rig) {
        when (message) {
            is OutboundWebRtcFileMessage.Control -> to.manager.handleControl(
                to.wire,
                // Round-trip through JSON so the wire encoding is part of the loop.
                WebRtcFileControl.fromJson(JSONObject(message.value.toJson().toString())),
            )
            is OutboundWebRtcFileMessage.Chunk -> to.manager.handleChunk(to.wire, message.value)
        }
    }

    /** Delivers queued messages between two rigs, at most [limit] of them. */
    private fun pump(a: Rig, b: Rig, limit: Int = 10_000): Int {
        var delivered = 0
        while (delivered < limit) {
            val fromA = a.outbox.poll()
            if (fromA != null) {
                deliver(fromA, b)
                delivered++
                continue
            }
            val fromB = b.outbox.poll() ?: break
            deliver(fromB, a)
            delivered++
        }
        return delivered
    }

    // ================================================================ receive

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

    @Test
    fun multiChunkTransferIsAcknowledgedPerChunkPublishedOnceAndCleanedUp() {
        val rig = rig()
        val bytes = payload(40_000)
        rig.manager.handleControl(wire, offer(bytes))
        feed(rig, bytes)

        assertEquals(
            listOf(
                WebRtcFileAction.ACCEPT,
                WebRtcFileAction.ACKNOWLEDGE,
                WebRtcFileAction.ACKNOWLEDGE,
                WebRtcFileAction.ACKNOWLEDGE,
                WebRtcFileAction.COMPLETE,
            ),
            rig.actions,
        )
        assertEquals(listOf(0L, 16_384L, 32_768L, 40_000L, 40_000L), rig.controls.map { it.offset })
        assertEquals(sha256(bytes), rig.controls.last().sha256)
        // Everything we say must be valid for the desktop on the mirrored binding.
        rig.controls.forEach { it.validate(mirrored(wire)) }
        assertTrue(rig.controls.all { it.transferToken == TOKEN && it.transferId == ID })

        assertEquals(1, rig.delivered.size)
        assertEquals("report.txt", rig.delivered.single().getString("filename"))
        assertArrayEquals(bytes, drain(rig.delivered.single()))
        assertEquals("partial file must go away once the payload is consumed", emptyList<String>(), leftovers())
        assertEquals(emptyList<TransferCheckpoint>(), store.list())
    }

    @Test
    fun zeroByteFileIsPublishedWithoutAnyChunk() {
        val rig = rig()
        rig.manager.handleControl(wire, offer(ByteArray(0)))
        assertEquals(listOf(WebRtcFileAction.COMPLETE), rig.actions)
        assertEquals(1, rig.delivered.size)
        assertEquals(0, drain(rig.delivered.single()).size)
        assertRejected { rig.manager.handleChunk(wire, chunk(ID, TOKEN, 0, byteArrayOf(1))) }
    }

    // ------------------------------------------------------- (a) resume

    @Test
    fun partialReceiveResumesAtTheVerifiedOffsetInANewManagerAfterRestart() {
        val bytes = payload(40_000)
        val first = rig()
        first.manager.handleControl(wire, offer(bytes))
        feed(first, bytes, to = CHUNK)
        val checkpoint = store.load(ID)!!
        assertEquals(CHUNK.toLong(), checkpoint.offset)
        assertEquals("receive", checkpoint.direction)
        assertEquals(CHUNK.toLong(), partial().length())

        // Process death: `first` is never closed or used again. The phone comes
        // back on a brand-new WebRTC session and the desktop offers again.
        val reconnected = wire.copy(sessionId = 77, generation = 8)
        val second = rig(reconnected)
        second.manager.handleControl(reconnected, offer(bytes, w = reconnected))

        assertEquals(listOf(WebRtcFileAction.ACCEPT), second.actions)
        assertEquals("resume at the verified offset", CHUNK.toLong(), second.controls.single().offset)
        assertEquals("no second partial file", listOf(".desklink-$ID.part"), leftovers())

        // The old session's binding is dead; the new one carries the rest.
        assertRejected { feed(second, bytes, from = CHUNK, to = CHUNK + 1, w = wire) }
        feed(second, bytes, from = CHUNK)

        assertEquals(0, first.delivered.size)
        assertEquals(1, second.delivered.size)
        assertArrayEquals(bytes, drain(second.delivered.single()))
        assertEquals(emptyList<String>(), leftovers())
        assertNull(store.load(ID))
    }

    @Test
    fun resumeDropsBytesThatWereWrittenButNeverCheckpointed() {
        val bytes = payload(40_000)
        val first = rig()
        first.manager.handleControl(wire, offer(bytes))
        feed(first, bytes, to = CHUNK)
        // Crash between the chunk's fsync and the checkpoint commit.
        partial().appendBytes(ByteArray(5) { 9 })

        val second = rig()
        second.manager.handleControl(wire, offer(bytes))
        assertEquals(CHUNK.toLong(), second.controls.single().offset)
        assertEquals(CHUNK.toLong(), partial().length())
        feed(second, bytes, from = CHUNK)
        assertArrayEquals(bytes, drain(second.delivered.single()))
    }

    @Test
    fun resumeRestartsFromZeroWhenTheCheckpointedBytesAreGone() {
        val bytes = payload(40_000)
        val first = rig()
        first.manager.handleControl(wire, offer(bytes))
        feed(first, bytes, to = 2 * CHUNK)
        RandomAccessFile(partial(), "rw").use { it.setLength(100) }

        val second = rig()
        second.manager.handleControl(wire, offer(bytes))
        assertEquals(0L, second.controls.single().offset)
        assertEquals(0L, partial().length())
        assertEquals(0L, store.load(ID)!!.offset)
        feed(second, bytes)
        assertArrayEquals(bytes, drain(second.delivered.single()))
    }

    @Test
    fun resumeRestartsFromZeroWhenThePartialFileIsMissing() {
        val bytes = payload(20_000)
        val first = rig()
        first.manager.handleControl(wire, offer(bytes))
        feed(first, bytes, to = CHUNK)
        assertTrue(partial().delete())

        val second = rig()
        second.manager.handleControl(wire, offer(bytes))
        assertEquals(0L, second.controls.single().offset)
        feed(second, bytes)
        assertArrayEquals(bytes, drain(second.delivered.single()))
    }

    @Test
    fun aCheckpointAtFullSizeIsFinalizedOnTheNextOfferInsteadOfWaitingForChunksThatWillNeverCome() {
        // Crash after the last chunk was checkpointed but before it was published.
        val bytes = payload(20_000)
        val file = partial().also { root.mkdirs(); it.writeBytes(bytes) }
        assertTrue(
            store.save(
                TransferCheckpoint(
                    transferId = ID,
                    uri = file.toURI().toString(),
                    offset = bytes.size.toLong(),
                    totalSize = bytes.size.toLong(),
                    state = "transferring",
                    filename = "report.txt",
                    deviceId = "desktop",
                    sha256 = sha256(bytes),
                    transferToken = TOKEN,
                    sessionId = 1,
                    connectionGeneration = 1,
                    direction = "receive",
                ),
            ),
        )
        val rig = rig()
        rig.manager.handleControl(wire, offer(bytes))
        assertEquals(listOf(WebRtcFileAction.COMPLETE), rig.actions)
        assertEquals(1, rig.delivered.size)
        assertArrayEquals(bytes, drain(rig.delivered.single()))
        assertNull(store.load(ID))
    }

    @Test
    fun closingTheManagerKeepsTheCheckpointButRejectsLaterChunks() {
        val bytes = payload(40_000)
        val rig = rig()
        rig.manager.handleControl(wire, offer(bytes))
        feed(rig, bytes, to = CHUNK)
        rig.manager.close("WebRTC session closed")

        assertEquals("paused", store.load(ID)!!.state)
        assertEquals(CHUNK.toLong(), partial().length())
        assertRejected { feed(rig, bytes, from = CHUNK, to = CHUNK + 10) }
        // ... and the peer's re-offer resumes it.
        rig.manager.handleControl(wire, offer(bytes))
        assertEquals(CHUNK.toLong(), rig.controls.last().offset)
        feed(rig, bytes, from = CHUNK)
        assertArrayEquals(bytes, drain(rig.delivered.single()))
    }

    // ------------------------------- (b) wrong token / device / session / generation

    @Test
    fun chunksAndControlsForTheWrongTokenDeviceSessionOrGenerationChangeNothing() {
        val bytes = payload(40_000)
        val rig = rig()
        rig.manager.handleControl(wire, offer(bytes))
        feed(rig, bytes, to = CHUNK)
        val next = chunk(ID, TOKEN, CHUNK.toLong(), bytes.copyOfRange(CHUNK, 2 * CHUNK))

        assertRejectedWithoutMutation(rig) {
            rig.manager.handleChunk(wire, chunk(ID, "wrong-token", CHUNK.toLong(), bytes.copyOfRange(CHUNK, 2 * CHUNK)))
        }
        assertRejectedWithoutMutation(rig) { rig.manager.handleChunk(wire.copy(generation = 8), next) }
        assertRejectedWithoutMutation(rig) { rig.manager.handleChunk(wire.copy(sessionId = 43), next) }
        assertRejectedWithoutMutation(rig) {
            rig.manager.handleChunk(wire, chunk("other-transfer", TOKEN, CHUNK.toLong(), byteArrayOf(1)))
        }

        val hostile = listOf(
            "cancel, wrong token" to ctl(WebRtcFileAction.CANCEL, token = "wrong-token"),
            "error, wrong token" to ctl(WebRtcFileAction.ERROR, token = "wrong-token"),
            "cancel, wrong device" to ctl(WebRtcFileAction.CANCEL).copy(deviceId = "mallory"),
            "cancel, wrong session" to ctl(WebRtcFileAction.CANCEL).copy(sessionId = 43),
            "cancel, wrong generation" to ctl(WebRtcFileAction.CANCEL).copy(connectionGeneration = 8),
            "error, wrong device" to ctl(WebRtcFileAction.ERROR).copy(deviceId = "mallory"),
            "replayed offer, wrong token" to offer(bytes, token = "wrong-token"),
            "replayed offer, wrong device" to offer(bytes).copy(deviceId = "mallory"),
            "replayed offer, wrong session" to offer(bytes).copy(sessionId = 43),
            "replayed offer, wrong generation" to offer(bytes).copy(connectionGeneration = 8),
            "replayed offer, different size" to offer(bytes, size = 39_999),
            "replayed offer, different name" to offer(bytes, filename = "other.txt"),
            "replayed offer, different checksum" to offer(bytes, sha256 = sha256(payload(5))),
            "unsolicited accept" to ctl(WebRtcFileAction.ACCEPT, offset = 0),
            "unsolicited acknowledge" to ctl(WebRtcFileAction.ACKNOWLEDGE, offset = CHUNK.toLong()),
            "unsolicited complete" to ctl(WebRtcFileAction.COMPLETE, offset = 40_000, sha256 = sha256(bytes)),
        )
        for ((name, message) in hostile) {
            try {
                assertRejectedWithoutMutation(rig) { rig.manager.handleControl(wire, message) }
            } catch (error: AssertionError) {
                throw AssertionError("$name: ${error.message}", error)
            }
        }

        // The transfer is intact and still finishes.
        feed(rig, bytes, from = CHUNK)
        assertEquals(1, rig.delivered.size)
        assertArrayEquals(bytes, drain(rig.delivered.single()))
    }

    @Test
    fun anOfferBoundToTheWrongPeerCreatesNoTransfer() {
        val bytes = payload(100)
        val rig = rig()
        for (bad in listOf(
            offer(bytes).copy(deviceId = "mallory"),
            offer(bytes).copy(sessionId = 43),
            offer(bytes).copy(connectionGeneration = 8),
            offer(bytes).copy(protocolVersion = 2),
        )) {
            assertRejectedWithoutMutation(rig) { rig.manager.handleControl(wire, bad) }
        }
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(emptyList<TransferCheckpoint>(), store.list())
        assertRejected { rig.manager.handleChunk(wire, chunk(ID, TOKEN, 0, bytes)) }
    }

    // ---------------------------------------------- (c) checksum mismatch

    @Test
    fun finalChecksumMismatchNeverPublishesAndCleansPartialAndCheckpoint() {
        val claimed = payload(20_000)
        val actual = claimed.copyOf().also { it[19_999] = (it[19_999] + 1).toByte() }
        val rig = rig()
        rig.manager.handleControl(wire, offer(claimed))
        feed(rig, actual, to = CHUNK)

        assertRejected { feed(rig, actual, from = CHUNK) }

        assertEquals("nothing may be published", 0, rig.delivered.size)
        assertEquals(emptyList<String>(), leftovers())
        assertNull(store.load(ID))
        assertEquals(emptyList<TransferCheckpoint>(), store.list())
        assertEquals(WebRtcFileAction.ERROR, rig.actions.last())
        assertFalse(WebRtcFileAction.COMPLETE in rig.actions)
        rig.controls.last().validate(mirrored(wire))
        // The transfer is over; nothing still listens for its chunks.
        assertRejected { feed(rig, actual, from = CHUNK, to = CHUNK + 1) }

        // The same id can be retried from scratch with honest bytes.
        rig.manager.handleControl(wire, offer(actual))
        assertEquals(0L, rig.controls.last().offset)
        feed(rig, actual)
        assertArrayEquals(actual, drain(rig.delivered.single()))
    }

    // --------------------------------------------------- (d) hostile filenames

    @Test
    fun hostileFilenamesFromThePeerAreRefusedAndTouchNothing() {
        val bytes = payload(64)
        val rig = rig()
        val names = listOf(
            "../x", "../../etc/passwd", "..\\x", "/etc/passwd", "/", "a/b", "a\\b", "C:\\Windows\\x",
            "a\u0000b", "\u0000", ".", "..", "", "a".repeat(256), "\u65e5".repeat(86),
        )
        for (name in names) {
            try {
                assertRejectedWithoutMutation(rig) {
                    rig.manager.handleControl(wire, offer(bytes, filename = name))
                }
            } catch (error: AssertionError) {
                throw AssertionError("filename '${name.take(20)}': ${error.message}", error)
            }
        }
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(emptyList<TransferCheckpoint>(), store.list())
        assertFalse(context.filesDir.parentFile!!.resolve("x").exists())
    }

    @Test
    fun unusualButSafeFilenamesAreDeliveredVerbatimAndStayInsideTheTransferRoot() {
        val bytes = payload(64)
        val rig = rig()
        val names = listOf("..x", ".hidden", "a b.txt", "\u65e5\u672c\u8a9e.txt", "a".repeat(255), "report.txt.")
        names.forEachIndexed { index, name ->
            rig.manager.handleControl(wire, offer(bytes, id = "t$index", token = "k$index", filename = name))
            feed(rig, bytes, id = "t$index", token = "k$index")
        }
        assertEquals(names, rig.delivered.map { it.getString("filename") })
        rig.delivered.forEach { assertArrayEquals(bytes, drain(it)) }
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(
            "nothing but the transfer root is created",
            listOf("webrtc-transfers"),
            context.filesDir.listFiles().orEmpty().map { it.name }.filter { it == "webrtc-transfers" },
        )
    }

    @Test
    fun aPeerChosenFilenameNeverSelectsWhereBytesAreWrittenOrWhatTheyOverwrite() {
        val outside = context.filesDir.resolve("report.txt").apply { writeText("outside") }
        val bytesA = payload(500)
        val bytesB = payload(700)
        val rig = rig()
        val inside = root.resolve("report.txt").apply { writeText("inside") }

        // Two transfers that both claim to be "report.txt".
        rig.manager.handleControl(wire, offer(bytesA, id = "a", token = "ka"))
        rig.manager.handleControl(wire, offer(bytesB, id = "b", token = "kb"))
        assertEquals(
            listOf(".desklink-a.part", ".desklink-b.part", "report.txt"),
            leftovers(),
        )
        feed(rig, bytesA, id = "a", token = "ka")
        feed(rig, bytesB, id = "b", token = "kb")

        assertEquals(2, rig.delivered.size)
        assertArrayEquals(bytesA, drain(rig.delivered[0]))
        assertArrayEquals(bytesB, drain(rig.delivered[1]))
        assertEquals("outside", outside.readText())
        assertEquals("inside", inside.readText())
    }

    @Test
    fun anExistingFileAtThePartialPathIsNeverOverwritten() {
        val rig = rig()
        val planted = partial("planted").apply { writeText("SECRET") }
        assertRejected {
            rig.manager.handleControl(wire, offer(payload(10), id = "planted", token = "k"))
        }
        assertEquals("the sender is told, not left waiting", WebRtcFileAction.ERROR, rig.controls.single().action)
        assertEquals("SECRET", planted.readText())
        assertNull(store.load("planted"))
    }

    // ------------------------------------------ (e) cancel / peer error

    private fun assertPeerEndsTransferAndLaterChunksAreRejected(action: WebRtcFileAction) {
        val bytes = payload(40_000)
        val rig = rig()
        rig.manager.handleControl(wire, offer(bytes))
        feed(rig, bytes, to = CHUNK)
        assertTrue(partial().exists())

        rig.manager.handleControl(wire, ctl(action, offset = CHUNK.toLong(), error = "peer gave up"))

        assertEquals("partial data must be removed", emptyList<String>(), leftovers())
        assertNull("checkpoint must be removed", store.load(ID))
        assertRejectedWithoutMutation(rig) { feed(rig, bytes, from = CHUNK, to = CHUNK + 100) }
        assertEquals(0, rig.delivered.size)

        // A cancelled transfer is not resurrected by a later offer: it starts over.
        rig.manager.handleControl(wire, offer(bytes))
        assertEquals(0L, rig.controls.last().offset)
        feed(rig, bytes)
        assertArrayEquals(bytes, drain(rig.delivered.single()))
    }

    @Test
    fun peerCancelRemovesPartialDataAndRejectsLaterChunks() =
        assertPeerEndsTransferAndLaterChunksAreRejected(WebRtcFileAction.CANCEL)

    @Test
    fun peerErrorRemovesPartialDataAndRejectsLaterChunks() =
        assertPeerEndsTransferAndLaterChunksAreRejected(WebRtcFileAction.ERROR)

    @Test
    fun peerCancelAfterOurRestartCleansThePersistedTransfer() {
        val bytes = payload(40_000)
        val first = rig()
        first.manager.handleControl(wire, offer(bytes))
        feed(first, bytes, to = CHUNK)

        val second = rig()
        assertRejectedWithoutMutation(second) {
            second.manager.handleControl(wire, ctl(WebRtcFileAction.CANCEL, token = "wrong-token"))
        }
        assertTrue(partial().exists())
        second.manager.handleControl(wire, ctl(WebRtcFileAction.CANCEL))
        assertEquals(emptyList<String>(), leftovers())
        assertNull(store.load(ID))
    }

    @Test
    fun aCancelForAnUnknownTransferIsIgnored() {
        val rig = rig()
        rig.manager.handleControl(wire, ctl(WebRtcFileAction.CANCEL, id = "never-seen"))
        rig.manager.handleControl(wire, ctl(WebRtcFileAction.ERROR, id = "never-seen"))
        assertEquals(emptyList<TransferCheckpoint>(), store.list())
    }

    // --------------------------------------------------------- (f) size limits

    @Test
    fun anOfferAboveTheTransferLimitIsRefused() {
        val rig = rig()
        assertRejectedWithoutMutation(rig) {
            rig.manager.handleControl(
                wire,
                offer(payload(1), size = WebRtcFileControl.MAX_TRANSFER_BYTES + 1),
            )
        }
        assertRejectedWithoutMutation(rig) {
            rig.manager.handleControl(wire, offer(payload(1), size = -1))
        }
        assertRejectedWithoutMutation(rig) {
            rig.manager.handleControl(wire, offer(payload(1)).copy(chunkSize = WebRtcFileControl.MAX_CHUNK_BYTES + 1))
        }
    }

    /** The peer holds the transfer's token, so it is told: it is waiting for an acknowledgement. */
    private fun assertTransferEndedAndPeerTold(rig: Rig, id: String = ID, token: String = TOKEN) {
        val told = rig.controls.last()
        assertEquals(WebRtcFileAction.ERROR, told.action)
        assertEquals(id, told.transferId)
        assertEquals(token, told.transferToken)
        assertFalse(told.error.isNullOrBlank())
        assertEquals("partial data must be removed", emptyList<String>(), leftovers())
        assertNull("checkpoint must be removed", store.load(id))
    }

    @Test
    fun moreBytesThanDeclaredEndTheTransferAndTellTheSender() {
        val bytes = payload(10)
        val rig = rig()
        rig.manager.handleControl(wire, offer(bytes))
        assertRejected { rig.manager.handleChunk(wire, chunk(ID, TOKEN, 0, payload(11))) }
        assertTransferEndedAndPeerTold(rig)

        // A fresh offer starts over and completes.
        rig.manager.handleControl(wire, offer(bytes))
        feed(rig, bytes)
        assertArrayEquals(bytes, drain(rig.delivered.single()))
    }

    @Test
    fun aChunkThatWouldRunPastTheDeclaredSizeEndsTheTransferMidWay() {
        val bytes = payload(20)
        val rig = rig()
        rig.manager.handleControl(wire, offer(bytes))
        rig.manager.handleChunk(wire, chunk(ID, TOKEN, 0, bytes.copyOfRange(0, 15)))
        assertRejected { rig.manager.handleChunk(wire, chunk(ID, TOKEN, 15, payload(10))) }
        assertTransferEndedAndPeerTold(rig)

        rig.manager.handleControl(wire, offer(bytes))
        feed(rig, bytes)
        assertArrayEquals(bytes, drain(rig.delivered.single()))
    }

    @Test
    fun outOfOrderReplayedAndOverflowingOffsetsEndTheTransferAndTellTheSender() {
        val bytes = payload(40_000)
        for (offset in listOf(0L, 1L, CHUNK.toLong() - 1, CHUNK.toLong() + 1, 2L * CHUNK, -1L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            val rig = rig()
            rig.manager.handleControl(wire, offer(bytes))
            feed(rig, bytes, to = CHUNK)
            assertRejected {
                rig.manager.handleChunk(wire, chunk(ID, TOKEN, offset, byteArrayOf(1, 2, 3)))
            }
            assertTransferEndedAndPeerTold(rig)
        }
    }

    @Test
    fun aWriteFailureOnAnAcceptedReceiveEndsItAndTellsTheSender() {
        val bytes = payload(40_000)
        val rig = rig()
        rig.manager.handleControl(wire, offer(bytes))
        // The partial file becomes a link: the manager refuses to write through it.
        partial().delete()
        java.nio.file.Files.createSymbolicLink(partial().toPath(), tmp.newFile().toPath())

        assertRejected { feed(rig, bytes, to = CHUNK) }

        assertTransferEndedAndPeerTold(rig)
        assertRejectedWithoutMutation(rig) { feed(rig, bytes, to = CHUNK) }
    }

    @Test
    fun anOfferThatCannotBeAcceptedIsAnsweredNotDropped() {
        val rig = rig()
        partial().apply { parentFile?.mkdirs() }.writeText("in the way")

        assertRejected { rig.manager.handleControl(wire, offer(payload(10))) }

        val told = rig.controls.single()
        assertEquals(WebRtcFileAction.ERROR, told.action)
        assertEquals(ID, told.transferId)
        assertEquals(TOKEN, told.transferToken)
    }

    @Test
    fun malformedOrOversizedChunkFramesAreRejectedBeforeAnyStateIsTouched() {
        val bytes = payload(40_000)
        val rig = rig()
        rig.manager.handleControl(wire, offer(bytes))
        val good = chunk(ID, TOKEN, 0, bytes.copyOfRange(0, CHUNK))

        // One byte over the frame bound, with a digest that matches the forged payload.
        val forged = ByteArray(WebRtcFileControl.MAX_CHUNK_BYTES + 1) { 5 }
        val header = good.copyOf(20).also { java.nio.ByteBuffer.wrap(it).putInt(16, forged.size) }
        val oversized = header + MessageDigest.getInstance("SHA-256").digest(forged) +
            ID.toByteArray() + TOKEN.toByteArray() + forged

        val flipped = good.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        for (frame in listOf(
            oversized, ByteArray(0), ByteArray(10), good.copyOf(good.size - 1),
            good + byteArrayOf(0), flipped,
        )) {
            assertRejectedWithoutMutation(rig) { rig.manager.handleChunk(wire, frame) }
        }
        feed(rig, bytes)
        assertArrayEquals(bytes, drain(rig.delivered.single()))
    }

    // ------------------------------------------------- (g) duplicate offers

    @Test
    fun aReplayedOfferForAnInProgressTransferKeepsProgressAndCreatesNoSecondTransfer() {
        val bytes = payload(40_000)
        val rig = rig()
        rig.manager.handleControl(wire, offer(bytes))
        feed(rig, bytes, to = 2 * CHUNK)

        rig.manager.handleControl(wire, offer(bytes))
        rig.manager.handleControl(wire, offer(bytes))

        assertEquals(2L * CHUNK, rig.controls.last().offset)
        assertEquals(WebRtcFileAction.ACCEPT, rig.actions.last())
        assertEquals(listOf(".desklink-$ID.part"), leftovers())
        assertEquals(2L * CHUNK, partial().length())
        assertEquals(1, store.list().size)
        assertEquals(2L * CHUNK, store.load(ID)!!.offset)

        feed(rig, bytes, from = 2 * CHUNK)
        assertEquals("published exactly once", 1, rig.delivered.size)
        assertArrayEquals(bytes, drain(rig.delivered.single()))
    }

    @Test
    fun aReplayedOfferForACompletedTransferDoesNotDeliverASecondCopy() {
        val bytes = payload(100)
        val rig = rig()
        rig.manager.handleControl(wire, offer(bytes))
        feed(rig, bytes)
        assertEquals(1, rig.delivered.size)

        // The published payload has not been consumed yet, so its file still exists.
        assertRejected { rig.manager.handleControl(wire, offer(bytes)) }
        assertEquals(1, rig.delivered.size)
        assertArrayEquals(bytes, drain(rig.delivered.single()))
    }

    // ----------------------------------------- (h) corrupt persisted checkpoints

    private fun validReceiveCheckpoint(bytes: ByteArray): TransferCheckpoint {
        val file = partial().also { root.mkdirs(); it.writeBytes(bytes.copyOf(100)) }
        return TransferCheckpoint(
            transferId = ID,
            uri = file.toURI().toString(),
            offset = 100,
            totalSize = bytes.size.toLong(),
            state = "transferring",
            filename = "report.txt",
            deviceId = "desktop",
            sha256 = sha256(bytes),
            transferToken = TOKEN,
            sessionId = 1,
            connectionGeneration = 1,
            direction = "receive",
        )
    }

    @Test
    fun corruptOrTruncatedPersistedCheckpointsAreTreatedAsNoCheckpoint() {
        val bytes = payload(30_000)
        val sentinel = tmp.newFile("victim.bin").apply { writeText("do not touch") }
        // name -> (damage, offset the transfer must resume from)
        val cases: Map<String, Pair<(android.content.SharedPreferences.Editor) -> Unit, Long>> = mapOf(
            "only a uri survived" to Pair({ e: android.content.SharedPreferences.Editor ->
                prefs.all.keys.filter { !it.endsWith(".uri") }.forEach { e.remove(it) }
            }, 0L),
            "offset has the wrong type" to Pair({ e: android.content.SharedPreferences.Editor ->
                e.putString("transfer.$ID.offset", "banana")
            }, 0L),
            "state has the wrong type" to Pair({ e: android.content.SharedPreferences.Editor ->
                e.putLong("transfer.$ID.state", 3L)
            }, 0L),
            "total is missing" to Pair({ e: android.content.SharedPreferences.Editor ->
                e.remove("transfer.$ID.total")
            }, 0L),
            "token is missing" to Pair({ e: android.content.SharedPreferences.Editor ->
                e.remove("transfer.$ID.token")
            }, 0L),
            "direction is missing" to Pair({ e: android.content.SharedPreferences.Editor ->
                e.remove("transfer.$ID.direction")
            }, 0L),
            "offset is beyond the total" to Pair({ e: android.content.SharedPreferences.Editor ->
                e.putLong("transfer.$ID.offset", 1_000_000L)
            }, 0L),
            "offset is negative" to Pair({ e: android.content.SharedPreferences.Editor ->
                e.putLong("transfer.$ID.offset", -5L)
            }, 0L),
            // The stored uri is never used to find the partial file, so a bad one
            // costs nothing: the derived path still holds the checkpointed 100 bytes.
            "uri is not a uri" to Pair({ e: android.content.SharedPreferences.Editor ->
                e.putString("transfer.$ID.uri", "::not a uri::")
            }, 100L),
            // ... and one pointing elsewhere is never read, written or deleted.
            "uri points outside the transfer root" to Pair({ e: android.content.SharedPreferences.Editor ->
                e.putString("transfer.$ID.uri", sentinel.toURI().toString())
                    .putLong("transfer.$ID.offset", 12L)
            }, 12L),
        )
        for ((name, case) in cases) {
            val (corrupt, resumeAt) = case
            try {
                resetState()
                store.save(validReceiveCheckpoint(bytes))
                prefs.edit().also(corrupt).commit()
                val rig = rig()

                rig.manager.handleControl(wire, offer(bytes))

                assertEquals(listOf(WebRtcFileAction.ACCEPT), rig.actions)
                assertEquals("resume offset", resumeAt, rig.controls.single().offset)
                assertEquals(resumeAt, partial().length())
                feed(rig, bytes, from = resumeAt.toInt())
                assertEquals(1, rig.delivered.size)
                assertArrayEquals(bytes, drain(rig.delivered.single()))
                assertEquals("do not touch", sentinel.readText())
                assertEquals(emptyList<String>(), leftovers())
                assertTrue(prefs.all.keys.none { it.startsWith("transfer.$ID.") })
            } catch (error: Throwable) {
                throw AssertionError("case '$name': $error", error)
            }
        }
    }

    @Test
    fun resumeSendsSurvivesAnUnreadableCheckpointAndNeverOffersAFileFromOutsideTheTransferRoot() {
        val victim = tmp.newFile("victim.bin").apply { writeBytes(payload(300)) }
        val rig = rig()
        // A well-formed send checkpoint that points outside the transfer root...
        store.save(
            TransferCheckpoint(
                transferId = "escape",
                uri = victim.toURI().toString(),
                offset = 0,
                totalSize = victim.length(),
                state = "paused",
                filename = "victim.bin",
                deviceId = "desktop",
                sha256 = sha256(victim.readBytes()),
                transferToken = "tok",
                direction = "send",
            ),
        )
        // ... and one that cannot even be read back.
        prefs.edit()
            .putString("transfer.garbled.uri", "file:///nowhere")
            .putString("transfer.garbled.offset", "banana")
            .commit()

        rig.manager.resumeSends(wire)

        assertEquals("nothing may be offered from outside the transfer root", emptyList<WebRtcFileAction>(), rig.actions)
        assertArrayEquals(payload(300), victim.readBytes())
    }

    // ------------------------------------------------ (i) simultaneous transfers

    @Test
    fun aPeerCannotHoldOpenAnUnboundedNumberOfIncomingTransfers() {
        val max = WebRtcFileTransferManager.MAX_INCOMING_TRANSFERS
        val bytes = payload(100)
        val rig = rig()
        repeat(max) { i ->
            rig.manager.handleControl(wire, offer(bytes, id = "t-$i", token = "k-$i"))
        }
        assertEquals(max, rig.actions.count { it == WebRtcFileAction.ACCEPT })

        // Refused with no trace on disk, but the peer is told (ERROR) rather than left waiting.
        val before = snapshot()
        assertRejected { rig.manager.handleControl(wire, offer(bytes, id = "one-too-many", token = "k")) }
        assertEquals(before, snapshot())
        assertEquals(WebRtcFileAction.ERROR, rig.actions.last())
        assertEquals("one-too-many", rig.controls.last().transferId)
        rig.controls.last().validate(mirrored(wire))
        assertEquals(max, root.listFiles().orEmpty().count { it.name.endsWith(".part") })
        assertEquals(max, store.list().size)

        // Re-offering a transfer that is already open is a resume, not a new one.
        rig.manager.handleControl(wire, offer(bytes, id = "t-0", token = "k-0"))
        assertEquals(WebRtcFileAction.ACCEPT, rig.actions.last())

        // Finishing (or cancelling) one frees a slot.
        rig.manager.handleControl(wire, ctl(WebRtcFileAction.CANCEL, id = "t-1", token = "k-1"))
        rig.manager.handleControl(wire, offer(bytes, id = "one-too-many", token = "k"))
        assertEquals(WebRtcFileAction.ACCEPT, rig.actions.last())
        feed(rig, bytes, id = "t-0", token = "k-0")
        rig.manager.handleControl(wire, offer(bytes, id = "and-another", token = "k2"))
        assertEquals(WebRtcFileAction.ACCEPT, rig.actions.last())
    }

    // =================================================================== send

    @Test
    fun phoneToDesktopTransferDeliversIdenticalBytesInBoundedContiguousChunks() {
        val phone = rig()
        val desktop = peerRig(mirrored(wire))
        val bytes = payload(50_000)
        val callback = Recorder()

        val result = startSend(phone, sharePacket(bytes), callback)
        awaitOffer(phone)
        pump(phone, desktop)

        assertTrue(result.get(10, TimeUnit.SECONDS))
        assertEquals(1, callback.successes.get())
        assertEquals(emptyList<Throwable>(), callback.failures.toList())
        assertEquals(100, callback.progress.last())
        assertEquals(callback.progress.sorted(), callback.progress.toList())
        assertEquals(1, desktop.delivered.size)
        assertEquals("photo.bin", desktop.delivered.single().getString("filename"))
        assertArrayEquals(bytes, drain(desktop.delivered.single()))

        // Two current builds negotiate the larger chunk size.
        val chunks = phone.chunks
        assertEquals(listOf(0L, 46_080L), chunks.map { it.offset })
        assertTrue(chunks.all { it.data.size <= WebRtcFileControl.MAX_CHUNK_BYTES })
        assertEquals(WebRtcFileAction.OFFER, phone.actions.first())
        phone.controls.forEach { it.validate(desktop.wire) }
        assertEquals("sender copy must be deleted", emptyList<String>(), leftovers())
        assertEquals(emptyList<TransferCheckpoint>(), store.list())
    }

    @Test
    fun zeroByteFileTravelsPhoneToDesktop() {
        val phone = rig()
        val desktop = peerRig(mirrored(wire))
        val callback = Recorder()
        val result = startSend(phone, sharePacket(ByteArray(0)), callback)
        awaitOffer(phone)
        pump(phone, desktop)
        assertTrue(result.get(10, TimeUnit.SECONDS))
        assertEquals(1, callback.successes.get())
        assertEquals(0, drain(desktop.delivered.single()).size)
    }

    @Test
    fun aSendResumesAfterThePhoneRestartsWithoutResendingAcknowledgedBytes() {
        val phone = rig()
        val desktop = peerRig(mirrored(wire))
        val bytes = payload(50_000)
        val callback = Recorder()
        val result = startSend(phone, sharePacket(bytes), callback)
        awaitOffer(phone)

        // offer, accept (upgrade negotiated), chunk 0: the desktop now holds
        // one 45 KiB chunk, with the tail still queued on the phone.
        assertEquals(3, pump(phone, desktop, limit = 3))
        assertEquals(46_080L, desktop.controls.last().offset)

        // The phone process dies; the link is gone with it.
        phone.manager.close("phone restarted")
        assertFalse(result.get(10, TimeUnit.SECONDS))
        assertEquals(1, callback.failures.size)
        desktop.outbox.clear()
        assertEquals("paused", store.list().single().state)

        val session2 = wire.copy(sessionId = 99, generation = 9)
        phone.wire = session2
        desktop.wire = mirrored(session2)
        val phone2 = rig(session2)
        phone2.manager.resumeSends(session2)
        assertEquals(listOf(WebRtcFileAction.OFFER), phone2.actions)
        pump(phone2, desktop)

        assertEquals(1, desktop.delivered.size)
        assertArrayEquals(bytes, drain(desktop.delivered.single()))
        assertEquals(
            "only the unacknowledged tail is sent again",
            listOf(46_080L),
            phone2.chunks.map { it.offset },
        )
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(emptyList<TransferCheckpoint>(), store.list())

        // A resumed sender is forgotten once it completes: a replayed COMPLETE finds no transfer.
        val done = phone2.controls.first()
        assertRejected {
            phone2.manager.handleControl(
                session2,
                ctl(
                    WebRtcFileAction.COMPLETE, id = done.transferId, token = done.transferToken,
                    offset = bytes.size.toLong(), sha256 = sha256(bytes), w = session2,
                ),
            )
        }
    }

    @Test
    fun aSenderWhoseSourceChangesMidTransferFailsAtOnceAndTellsThePeer() {
        val phone = rig()
        val callback = Recorder()
        val result = startSend(phone, sharePacket(payload(40_000)), callback)
        val offer = awaitOffer(phone)
        phone.manager.handleControl(
            wire,
            ctl(WebRtcFileAction.ACCEPT, id = offer.transferId, token = offer.transferToken, offset = 0),
        )
        // Windowed: all 40 KB fits the window, as three legacy-sized chunks.
        assertEquals(3, phone.chunks.size)
        root.resolve(".desklink-${offer.transferId}.send").appendBytes(byteArrayOf(1))

        phone.manager.handleControl(
            wire,
            ctl(WebRtcFileAction.ACKNOWLEDGE, id = offer.transferId, token = offer.transferToken, offset = CHUNK.toLong()),
        )

        // Not a 30-minute hang: the send is over already.
        assertFalse(result.get(5, TimeUnit.SECONDS))
        assertEquals(1, callback.failures.size)
        assertEquals(WebRtcFileAction.ERROR, phone.actions.last())
        // Windowed: all 40 KB fits the window, as three legacy-sized chunks.
        assertEquals(3, phone.chunks.size)
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(emptyList<TransferCheckpoint>(), store.list())
    }

    @Test
    fun resumeSendsNeverRevivesAFailedOrCancelledTransferAndReclaimsItsCopy() {
        val phone = rig()
        val original = payload(300)
        root.mkdirs()
        for (state in listOf("failed", "cancelled", "completed")) {
            val id = "old-$state"
            val source = root.resolve(".desklink-$id.send").apply { writeBytes(original) }
            store.save(
                TransferCheckpoint(
                    transferId = id, uri = source.toURI().toString(), offset = 0,
                    totalSize = original.size.toLong(), state = state, filename = "photo.bin",
                    deviceId = "desktop", sha256 = sha256(original), transferToken = "tok",
                    direction = "send",
                ),
            )
        }
        phone.manager.resumeSends(wire)
        assertEquals(emptyList<WebRtcFileAction>(), phone.actions)
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(emptyList<TransferCheckpoint>(), store.list())
    }

    @Test
    fun aFileThatCannotBeDeliveredIsNotLeftBehindAndThePeerIsTold() {
        val bytes = payload(100)
        val rig = rig()
        every { rig.device.onWebRtcPacketReceived(any()) } throws IllegalStateException("no plugin")
        rig.manager.handleControl(wire, offer(bytes))

        assertRejected { feed(rig, bytes) }

        assertEquals(WebRtcFileAction.ERROR, rig.actions.last())
        assertFalse(WebRtcFileAction.COMPLETE in rig.actions)
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(emptyList<TransferCheckpoint>(), store.list())
    }

    @Test
    fun aSenderRejectsResponsesWithTheWrongTokenBindingOrOffsetWithoutChangingState() {
        val phone = rig()
        val bytes = payload(40_000)
        val callback = Recorder()
        val result = startSend(phone, sharePacket(bytes), callback)
        val offer = awaitOffer(phone)
        val token = offer.transferToken
        val id = offer.transferId
        val sha = offer.sha256!!
        fun reply(
            action: WebRtcFileAction,
            offset: Long = 0,
            tok: String = token,
            sha256: String? = null,
        ) = ctl(action, id = id, token = tok, offset = offset, sha256 = sha256, w = wire)

        val bad = listOf(
            "accept, wrong token" to reply(WebRtcFileAction.ACCEPT, tok = "wrong"),
            "accept, wrong device" to reply(WebRtcFileAction.ACCEPT).copy(deviceId = "mallory"),
            "accept, wrong session" to reply(WebRtcFileAction.ACCEPT).copy(sessionId = 43),
            "accept, wrong generation" to reply(WebRtcFileAction.ACCEPT).copy(connectionGeneration = 8),
            "accept, unknown transfer" to reply(WebRtcFileAction.ACCEPT).copy(transferId = "unknown"),
            "complete, wrong token" to reply(WebRtcFileAction.COMPLETE, offset = 40_000, sha256 = sha, tok = "wrong"),
            "cancel, wrong token" to reply(WebRtcFileAction.CANCEL, tok = "wrong"),
            "error, wrong token" to reply(WebRtcFileAction.ERROR, tok = "wrong"),
        )
        fun assertAllRejected() {
            for ((name, message) in bad) {
                try {
                    assertRejectedWithoutMutation(phone) { phone.manager.handleControl(wire, message) }
                } catch (error: AssertionError) {
                    throw AssertionError("$name: ${error.message}", error)
                }
            }
        }
        assertAllRejected()
        assertTrue(phone.chunks.isEmpty())

        // A genuine accept starts the transfer; then acknowledgements must match the chunk in flight.
        phone.manager.handleControl(wire, reply(WebRtcFileAction.ACCEPT))
        assertEquals(listOf(0L, 16_384L, 32_768L), phone.chunks.map { it.offset })
        assertRejectedWithoutMutation(phone) {
            phone.manager.handleControl(wire, reply(WebRtcFileAction.ACKNOWLEDGE, offset = CHUNK.toLong(), tok = "wrong"))
        }
        assertEquals(0, callback.successes.get())
        assertTrue(callback.failures.isEmpty())

        phone.manager.handleControl(wire, reply(WebRtcFileAction.CANCEL))
        assertFalse(result.get(10, TimeUnit.SECONDS))
    }

    /**
     * The peer holds the transfer's token but its progress makes no sense: the
     * two sides no longer agree, so the send ends and the peer is told.
     */
    @Test
    fun progressThatDisagreesWithTheTransferEndsTheSendAndTellsThePeer() {
        val cases = listOf<Triple<String, Boolean, (String, String, String) -> WebRtcFileControl>>(
            Triple("accept, offset beyond file", false) { id, token, _ ->
                ctl(WebRtcFileAction.ACCEPT, id = id, token = token, offset = 40_001)
            },
            Triple("acknowledge before accept", false) { id, token, _ ->
                ctl(WebRtcFileAction.ACKNOWLEDGE, id = id, token = token, offset = CHUNK.toLong())
            },
            // Acks are cumulative, so any offset up to what was sent is fine;
            // one past everything sent is progress the sender never made.
            Triple("acknowledge beyond what was sent", true) { id, token, _ ->
                ctl(WebRtcFileAction.ACKNOWLEDGE, id = id, token = token, offset = 40_001)
            },
            Triple("complete, wrong checksum", false) { id, token, _ ->
                ctl(WebRtcFileAction.COMPLETE, id = id, token = token, offset = 40_000, sha256 = "0".repeat(64))
            },
            Triple("complete, wrong size", false) { id, token, sha ->
                ctl(WebRtcFileAction.COMPLETE, id = id, token = token, offset = 39_999, sha256 = sha)
            },
        )
        for ((name, accepted, forge) in cases) {
            val phone = rig()
            val callback = Recorder()
            val result = startSend(phone, sharePacket(payload(40_000)), callback)
            val offer = awaitOffer(phone)
            if (accepted) {
                phone.manager.handleControl(
                    wire,
                    ctl(WebRtcFileAction.ACCEPT, id = offer.transferId, token = offer.transferToken, offset = 0),
                )
            }

            assertRejected {
                phone.manager.handleControl(wire, forge(offer.transferId, offer.transferToken, offer.sha256!!))
            }

            assertFalse(name, result.get(10, TimeUnit.SECONDS))
            assertEquals(name, WebRtcFileAction.ERROR, phone.actions.last())
            assertEquals(name, 1, callback.failures.size)
            assertEquals(name, emptyList<String>(), leftovers())
            assertEquals(name, emptyList<TransferCheckpoint>(), store.list())
        }
    }

    private fun assertPeerEndsSendAndCleansUp(action: WebRtcFileAction, message: String?) {
        val phone = rig()
        val callback = Recorder()
        val result = startSend(phone, sharePacket(payload(40_000)), callback)
        val offer = awaitOffer(phone)
        phone.manager.handleControl(
            wire,
            ctl(WebRtcFileAction.ACCEPT, id = offer.transferId, token = offer.transferToken, offset = 0),
        )
        // Windowed: all 40 KB fits the window, as three legacy-sized chunks.
        assertEquals(3, phone.chunks.size)

        phone.manager.handleControl(
            wire,
            ctl(action, id = offer.transferId, token = offer.transferToken, offset = 0, error = message),
        )

        assertFalse(result.get(10, TimeUnit.SECONDS))
        assertEquals(0, callback.successes.get())
        assertEquals(1, callback.failures.size)
        if (message != null) assertEquals(message, callback.failures.single().message)
        assertEquals("the sender copy must not outlive a finished transfer", emptyList<String>(), leftovers())
        assertEquals(emptyList<TransferCheckpoint>(), store.list())
        // Nothing listens any more: a late acknowledgement is refused.
        assertRejected {
            phone.manager.handleControl(
                wire,
                ctl(WebRtcFileAction.ACKNOWLEDGE, id = offer.transferId, token = offer.transferToken, offset = CHUNK.toLong()),
            )
        }
        // Windowed: all 40 KB fits the window, as three legacy-sized chunks.
        assertEquals(3, phone.chunks.size)
    }

    @Test
    fun peerCancelStopsASendAndRemovesItsCopyAndCheckpoint() =
        assertPeerEndsSendAndCleansUp(WebRtcFileAction.CANCEL, null)

    @Test
    fun peerErrorStopsASendReportsItsReasonAndRemovesItsCopyAndCheckpoint() =
        assertPeerEndsSendAndCleansUp(WebRtcFileAction.ERROR, "disk full")

    @Test
    fun cancellingALocalSendTellsThePeerAndRemovesItsCopyAndCheckpoint() {
        val phone = rig()
        val callback = Recorder()
        val packet = sharePacket(payload(40_000))
        val result = startSend(phone, packet, callback)
        awaitOffer(phone)

        packet.cancel()

        assertFalse(result.get(10, TimeUnit.SECONDS))
        assertEquals(WebRtcFileAction.CANCEL, phone.actions.last())
        assertEquals(1, callback.failures.size)
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(emptyList<TransferCheckpoint>(), store.list())
    }

    @Test
    fun aSendWhoseOfferCannotBeSentFailsCleanlyAndLeavesNothingBehind() {
        val phone = rig()
        phone.failSends = true
        val closed = AtomicBoolean()
        val callback = Recorder()

        val result = startSend(phone, sharePacket(payload(100), closed = closed), callback).get(10, TimeUnit.SECONDS)

        assertFalse(result)
        assertEquals(1, callback.failures.size)
        assertTrue("payload must be closed", closed.get())
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(emptyList<TransferCheckpoint>(), store.list())

        // The id is free again.
        phone.failSends = false
        val retry = startSend(phone, sharePacket(payload(100)), Recorder())
        awaitOffer(phone)
        phone.manager.close("done")
        assertFalse(retry.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun hostileLocalFilenamesAndTransferIdsAreRefusedBeforeAnyFileIsCreated() {
        val phone = rig()
        val before = snapshot()
        val hostileNames = listOf("../x", "/etc/passwd", "a/b", "a\\b", "a\u0000b", ".", "..", "", "a".repeat(256))
        for (name in hostileNames) {
            val closed = AtomicBoolean()
            val callback = Recorder()
            val result = startSend(phone, sharePacket(payload(10), filename = name, closed = closed), callback)
                .get(10, TimeUnit.SECONDS)
            assertFalse("filename '${name.take(12)}'", result)
            assertEquals(1, callback.failures.size)
            assertTrue(closed.get())
        }
        for (id in listOf("../x", "../../escape", "a/b", "", "x".repeat(129), "a b")) {
            val closed = AtomicBoolean()
            val callback = Recorder()
            val result = startSend(phone, sharePacket(payload(10), transferId = id, closed = closed), callback)
                .get(10, TimeUnit.SECONDS)
            assertFalse("transfer id '${id.take(12)}'", result)
            assertEquals(1, callback.failures.size)
            assertTrue(closed.get())
        }
        assertEquals(emptyList<WebRtcFileAction>(), phone.actions)
        assertEquals(before, snapshot())
        assertFalse(context.filesDir.resolve("escape").exists())
        assertFalse(root.resolve("..").resolve("x").exists())
    }

    @Test
    fun aSecondSendWithTheSameTransferIdCannotDisturbTheFirst() {
        val phone = rig()
        val first = Recorder()
        val firstResult = startSend(phone, sharePacket(payload(40_000)), first)
        val offer = awaitOffer(phone)
        val stored = store.load(offer.transferId)!!

        val closed = AtomicBoolean()
        val second = Recorder()
        val secondResult = startSend(phone, sharePacket(payload(77), closed = closed), second)
            .get(10, TimeUnit.SECONDS)

        assertFalse(secondResult)
        assertEquals(1, second.failures.size)
        assertTrue(closed.get())
        assertEquals("the first transfer's checkpoint is intact", stored, store.load(offer.transferId))
        assertEquals(1, phone.actions.count { it == WebRtcFileAction.OFFER })
        phone.manager.handleControl(
            wire,
            ctl(WebRtcFileAction.ACCEPT, id = offer.transferId, token = offer.transferToken, offset = 0),
        )
        // Windowed: all 40 KB fits the window, as three legacy-sized chunks.
        assertEquals(3, phone.chunks.size)
        assertTrue(first.failures.isEmpty())
        phone.manager.close("done")
        assertFalse(firstResult.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun resumeSendsRefusesATamperedSourceAndAnotherPeersTransfer() {
        val phone = rig()
        val original = payload(300)
        root.mkdirs()
        val source = root.resolve(".desklink-send-1.send").apply { writeBytes(original) }
        fun checkpoint(id: String, device: String, file: File) = TransferCheckpoint(
            transferId = id,
            uri = file.toURI().toString(),
            offset = 0,
            totalSize = original.size.toLong(),
            state = "paused",
            filename = "photo.bin",
            deviceId = device,
            sha256 = sha256(original),
            transferToken = "tok-$id",
            direction = "send",
        )
        val otherSource = root.resolve(".desklink-send-2.send").apply { writeBytes(original) }
        store.save(checkpoint("send-1", "desktop", source))
        store.save(checkpoint("send-2", "some-other-device", otherSource))

        // Same size, different content.
        source.writeBytes(original.copyOf().also { it[0] = (it[0] + 1).toByte() })

        phone.manager.resumeSends(wire)

        assertEquals("a tampered copy is never offered", emptyList<WebRtcFileAction>(), phone.actions)
        assertNull(store.load("send-1"))
        assertFalse(source.exists())
        assertTrue("another device's transfer is left alone", otherSource.exists())
        assertEquals("some-other-device", store.load("send-2")!!.deviceId)
    }

    private companion object {
        const val ID = "transfer-1"
        const val TOKEN = "token-1"
        // One chunk as a sender that never saw a chunk-size upgrade sends it.
        const val CHUNK = WebRtcFileControl.LEGACY_CHUNK_BYTES
        const val CHECKPOINT_PREFS = "desklink.transfer.checkpoints"
        const val PEER_CHECKPOINT_PREFS = "peer-side-checkpoints"
    }
}
