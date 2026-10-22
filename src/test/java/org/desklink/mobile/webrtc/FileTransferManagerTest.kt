/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.InputStream
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The manager's routing and limits, with two managers back to back and a manual file thread. */
@RunWith(RobolectricTestRunner::class)
class FileTransferManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var phoneContext: Application
    private val executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    /** A file thread whose work the test runs itself. */
    private class ManualRunner : FileRunner {
        private val queue = ArrayDeque<Runnable>()
        override fun post(task: Runnable) = synchronized(queue) { queue.add(task); Unit }
        override fun postDelayed(delayMs: Long, task: Runnable) = post(task)
        override fun every(periodMs: Long, task: Runnable) = Unit
        fun runPending(): Boolean {
            val next = synchronized(queue) { queue.poll() } ?: return false
            next.run()
            return true
        }
        fun pendingCount() = synchronized(queue) { queue.size }
    }

    private val phoneWire = WebRtcWireBinding("phone", "desk", 42, 7)
    private val deskWire = WebRtcWireBinding("desk", "phone", 42, 7)

    private inner class Side(val context: Context, val wire: WebRtcWireBinding, val peerWire: WebRtcWireBinding) {
        val runner = ManualRunner()
        val delivered = CopyOnWriteArrayList<NetworkPacket>()
        val device: Device = mockk<Device>(relaxed = true).also { every { it.onWebRtcPacketReceived(any()) } answers { delivered += firstArg<NetworkPacket>() } }
        val outControl = ArrayDeque<WebRtcFileControl>()
        val outFrames = ArrayDeque<ByteArray>()
        var linkDown = false
        var bufferedAmount = 0L
        val sent = CopyOnWriteArrayList<WebRtcFileControl>()
        val link = object : FileLink {
            override fun sendControl(control: WebRtcFileControl) {
                if (linkDown) throw org.desklink.mobile.transport.TransportError(org.desklink.mobile.transport.TransportErrorCode.CLOSED, "closed")
                sent += control
                outControl.add(control)
            }
            override fun sendFrame(message: ByteArray) {
                if (linkDown) throw org.desklink.mobile.transport.TransportError(org.desklink.mobile.transport.TransportErrorCode.CLOSED, "closed")
                outFrames.add(message)
            }
            override fun bufferedAmount() = bufferedAmount
        }
        var now = 0L
        val manager = WebRtcFileTransferManager(context, device, link, runner, { now }, { Long.MAX_VALUE })
        val root: File get() = File(context.filesDir, "webrtc-transfers")
        fun control(action: WebRtcFileAction, id: String, token: String = "t".repeat(32), block: WebRtcFileControl.() -> WebRtcFileControl = { this }) = WebRtcFileControl(
            action = action, transferId = id, deviceId = peerWire.senderDeviceId, sessionId = peerWire.sessionId,
            connectionGeneration = peerWire.generation, transferToken = token,
        ).block()
    }

    private lateinit var phone: Side
    private lateinit var desk: Side

    @Before
    fun setUp() {
        phoneContext = ApplicationProvider.getApplicationContext()
        File(phoneContext.filesDir, "webrtc-transfers").deleteRecursively()
        phone = Side(phoneContext, phoneWire, deskWire)
        val deskDir = tmp.newFolder()
        val deskContext = mockk<Context>()
        every { deskContext.applicationContext } returns deskContext
        every { deskContext.filesDir } returns deskDir
        desk = Side(deskContext, deskWire, phoneWire)
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        File(phoneContext.filesDir, "webrtc-transfers").deleteRecursively()
    }

    /** Runs both file threads and carries messages between the two managers until quiet. */
    private fun pump(limit: Int = 100_000, maxMessages: Int = Int.MAX_VALUE): Int {
        var n = 0
        var messages = 0
        while (n < limit) {
            var progressed = false
            if (phone.runner.runPending() || desk.runner.runPending()) progressed = true
            if (messages < maxMessages) {
                phone.outControl.poll()?.let { desk.manager.handleControl(deskWire, it); messages++; progressed = true }
                desk.outControl.poll()?.let { phone.manager.handleControl(phoneWire, it); messages++; progressed = true }
                phone.outFrames.poll()?.let { desk.manager.handleFrame(it); messages++; progressed = true }
            }
            if (!progressed) break
            n++
        }
        return n
    }

    private fun data(size: Int) = ByteArray(size) { ((it * 31) + (it shr 12)).toByte() }

    private class Recorder : Device.SendPacketStatusCallback() {
        val successes = AtomicInteger()
        val failures = CopyOnWriteArrayList<Throwable>()
        val percents = CopyOnWriteArrayList<Int>()
        override fun onSuccess() { successes.incrementAndGet() }
        override fun onFailure(e: Throwable) { failures += e }
        override fun onPayloadProgressChanged(percent: Int) { percents += percent }
    }

    private fun share(bytes: ByteArray, name: String = "photo.jpg") = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
        this["filename"] = name
        payload = NetworkPacket.Payload(java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun startSend(packet: NetworkPacket, callback: Recorder): Future<Boolean> =
        executor.submit<Boolean> { phone.manager.sendPayload(phoneWire, packet, callback) }

    private fun runUntilDone(future: Future<Boolean>, maxMessages: Int = Int.MAX_VALUE): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!future.isDone && System.nanoTime() < deadline) {
            if (pump(maxMessages = maxMessages) == 0) Thread.sleep(2)
        }
        return future.get(5, TimeUnit.SECONDS)
    }

    private fun deliveredBytes(packet: NetworkPacket): ByteArray {
        val bytes = packet.payload!!.inputStream!!.readBytes()
        packet.payload!!.close()
        return bytes
    }

    // ====================================================== end to end

    @Test
    fun aShareGoesAcrossAndTheCallerHearsEveryStep() {
        val bytes = data(2 * FileV2.BLOCK_BYTES + 12345)
        val recorder = Recorder()
        val ok = runUntilDone(startSend(share(bytes), recorder))
        assertTrue(ok)
        assertEquals(1, recorder.successes.get())
        assertEquals(emptyList<Throwable>(), recorder.failures.toList())
        assertEquals(100, recorder.percents.last())
        assertEquals("photo.jpg", desk.delivered.single().getString("filename"))
        assertArrayEquals(bytes, deliveredBytes(desk.delivered.single()))
        assertTrue("nothing left of the spool or the checkpoint on the sender", phone.root.listFiles().orEmpty().isEmpty())
        assertTrue(desk.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aFileThatIsReadInPlaceIsNeverCopiedFirst() {
        val bytes = data(FileV2.BLOCK_BYTES + 5)
        val packet = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
            this["filename"] = "in-place.bin"
            payload = NetworkPacket.Payload(bytes.size.toLong()).also {
                it.source = object : FileSource {
                    override fun stat() = FileSource.Stat(bytes.size.toLong(), 77)
                    override fun open(offset: Long): InputStream = java.io.ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
                }
            }
        }
        val future = startSend(packet, Recorder())
        // While the transfer runs, no spool file ever exists in the transfer root.
        var sawSpool = false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!future.isDone && System.nanoTime() < deadline) {
            if (phone.root.listFiles().orEmpty().any { it.name.endsWith(".spool") }) sawSpool = true
            if (pump() == 0) Thread.sleep(2)
        }
        assertFalse("no pre-copy", sawSpool)
        assertTrue(future.get(5, TimeUnit.SECONDS))
        assertArrayEquals(bytes, deliveredBytes(desk.delivered.single()))
    }

    @Test
    fun aDroppedLinkPausesTheSendAndANewConnectionResumesItWithoutResendingVerifiedBlocks() {
        val bytes = data(6 * FileV2.BLOCK_BYTES)
        val recorder = Recorder()
        val future = startSend(share(bytes), recorder)
        // A little of it crosses, then the Wi-Fi is pulled.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (desk.sent.count { it.action == WebRtcFileAction.BLOCK_ACK } < 2 && System.nanoTime() < deadline) { if (pump(maxMessages = 5) == 0) Thread.sleep(2) }
        phone.outControl.clear(); phone.outFrames.clear(); desk.outControl.clear()
        phone.manager.close("link lost"); desk.manager.close("link lost")
        pump()
        assertFalse(future.get(5, TimeUnit.SECONDS))
        assertTrue("the owner is told it is resuming, not that it failed", recorder.failures.single() is TransferResumingException)
        val firstSession = desk.sent.count { it.action == WebRtcFileAction.BLOCK_ACK }
        assertTrue(phone.root.listFiles().orEmpty().any { it.name.endsWith(".json") })

        // A new connection.
        phone.manager.resumeSends(phoneWire)
        pump()
        assertEquals(1, desk.delivered.size)
        assertArrayEquals(bytes, deliveredBytes(desk.delivered.single()))
        val accept = desk.sent.last { it.action == WebRtcFileAction.ACCEPT }
        assertTrue("resumed after the verified blocks (${accept.resumeBlock})", accept.resumeBlock!! in 1..6)
        assertTrue("nothing already verified was sent again", desk.sent.count { it.action == WebRtcFileAction.BLOCK_ACK } >= firstSession)
        assertTrue(phone.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aSourceThatKeepsChangingIsRestartedTwiceThenFailsInPlainWords() {
        val stats = AtomicInteger()
        val bytes = data(FileV2.BLOCK_BYTES * 3)
        val packet = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
            this["filename"] = "moving.bin"
            payload = NetworkPacket.Payload(bytes.size.toLong()).also {
                it.source = object : FileSource {
                    // The first stat starts the send; every later look finds a different mtime.
                    override fun stat() = FileSource.Stat(bytes.size.toLong(), 1000L + stats.incrementAndGet() / 2)
                    override fun open(offset: Long): InputStream = java.io.ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
                }
            }
        }
        val recorder = Recorder()
        val ok = runUntilDone(startSend(packet, recorder))
        assertFalse(ok)
        assertEquals("The file keeps changing, so it was not sent.", recorder.failures.single().message)
        assertEquals(FileV2.MAX_RESTARTS + 1, phone.sent.count { it.action == WebRtcFileAction.OFFER })
        assertEquals("each restart is a new transfer", FileV2.MAX_RESTARTS + 1, phone.sent.filter { it.action == WebRtcFileAction.OFFER }.map { it.transferId }.toSet().size)
        assertTrue(phone.root.listFiles().orEmpty().isEmpty())
        assertTrue(desk.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aFileThatDoesNotExistFailsAtOnceWithoutAnyOffer() {
        val packet = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
            this["filename"] = "gone.bin"
            payload = NetworkPacket.Payload(10).also { it.source = object : FileSource { override fun stat() = null; override fun open(offset: Long): InputStream = error("gone") } }
        }
        val recorder = Recorder()
        assertFalse(phone.manager.sendPayload(phoneWire, packet, recorder))
        assertEquals("The file no longer exists.", recorder.failures.single().message)
        assertEquals(0, phone.sent.size)
    }

    // ============================================== what a peer may and may not do

    private fun offer(id: String = UUID.randomUUID().toString(), name: String = "a.bin", size: Long = 10, block: WebRtcFileControl.() -> WebRtcFileControl = { this }) =
        desk.control(WebRtcFileAction.OFFER, id) { copy(filename = name, totalSize = size, mtime = 1, blockSize = FileV2.BLOCK_BYTES, maxFrameBytes = FileV2.FRAME_BYTES_DEFAULT).block() }

    @Test
    fun anUnrequestedBucketFileIsRefusedAndNothingIsStored() {
        val hex = "ab".repeat(32)
        desk.manager.handleControl(deskWire, offer(name = "x.jpg") { copy(folder = "bucket", relpath = "Photos/x.jpg", contentSha256 = hex) })
        pump(maxMessages = 0)
        val answer = desk.sent.single()
        assertEquals(WebRtcFileAction.ERROR, answer.action)
        assertEquals(FileCodes.REFUSED, answer.code)
        assertEquals("folder-refused", answer.message)
        assertTrue(desk.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun hostileOrMalformedOffersAreRefusedAndTouchNothing() {
        listOf("../escape", "a/b", "..", "", "x".repeat(300), "nul\u0000byte").forEach { name ->
            desk.manager.handleControl(deskWire, offer(name = name))
        }
        desk.manager.handleControl(deskWire, offer(size = FileV2.MAX_TRANSFER_BYTES + 1))
        desk.manager.handleControl(deskWire, offer { copy(blockSize = 4096) })
        desk.manager.handleControl(WebRtcWireBinding("desk", "someone-else", 42, 7), offer())
        pump(maxMessages = 0)
        assertTrue("nothing was created", desk.root.listFiles().orEmpty().isEmpty())
        assertTrue(desk.delivered.isEmpty())
    }

    @Test
    fun aThirdIncomingFileIsToldToWaitNotRefused() {
        val ids = List(3) { UUID.randomUUID().toString() }
        ids.forEach { desk.manager.handleControl(deskWire, offer(id = it, size = FileV2.BLOCK_BYTES * 4L)) }
        pump(maxMessages = 0)
        assertEquals(2, desk.sent.count { it.action == WebRtcFileAction.ACCEPT })
        val busy = desk.sent.single { it.action == WebRtcFileAction.ERROR }
        assertEquals(FileCodes.BUSY, busy.code)
        assertEquals(ids[2], busy.transferId)
        assertNull("no state was created for the one that must wait", FileTransferStore(desk.root).load(ids[2]))
    }

    @Test
    fun aReplayedOfferForATransferInProgressChangesNothing() {
        val id = UUID.randomUUID().toString()
        val o = offer(id = id, size = FileV2.BLOCK_BYTES * 2L)
        desk.manager.handleControl(deskWire, o)
        pump(maxMessages = 0)
        val filesBefore = desk.root.listFiles().orEmpty().map { it.name }.sorted()
        desk.manager.handleControl(deskWire, o)
        pump(maxMessages = 0)
        assertEquals(1, desk.sent.count { it.action == WebRtcFileAction.ACCEPT })
        assertEquals(filesBefore, desk.root.listFiles().orEmpty().map { it.name }.sorted())
        // A replay with another token is refused, and the real transfer is untouched.
        desk.manager.handleControl(deskWire, offer(id = id, size = FileV2.BLOCK_BYTES * 2L).copy(transferToken = "z".repeat(32)))
        pump(maxMessages = 0)
        assertEquals(filesBefore, desk.root.listFiles().orEmpty().map { it.name }.sorted())
    }

    @Test
    fun messagesWithTheWrongTokenBindingOrForUnknownTransfersChangeNothing() {
        val id = UUID.randomUUID().toString()
        desk.manager.handleControl(deskWire, offer(id = id, size = FileV2.BLOCK_BYTES * 2L))
        pump(maxMessages = 0)
        val before = desk.root.listFiles().orEmpty().map { it.name }.sorted()
        // Wrong token: the cancel does not end it.
        desk.manager.handleControl(deskWire, desk.control(WebRtcFileAction.CANCEL, id, token = "wrong".repeat(8)) { copy(code = FileCodes.USER) })
        // Wrong session / device.
        desk.manager.handleControl(WebRtcWireBinding("desk", "phone", 99, 7), desk.control(WebRtcFileAction.CANCEL, id) { copy(code = FileCodes.USER, sessionId = 42) })
        // An unknown transfer.
        desk.manager.handleControl(deskWire, desk.control(WebRtcFileAction.CANCEL, UUID.randomUUID().toString()) { copy(code = FileCodes.USER) })
        desk.manager.handleControl(deskWire, desk.control(WebRtcFileAction.DONE, UUID.randomUUID().toString()) { copy(fileHash = "ab".repeat(32), blocks = 0, totalSize = 0) })
        pump(maxMessages = 0)
        assertEquals(before, desk.root.listFiles().orEmpty().map { it.name }.sorted())
        // The right cancel ends it and cleans up.
        desk.manager.handleControl(deskWire, desk.control(WebRtcFileAction.CANCEL, id) { copy(code = FileCodes.USER) })
        pump(maxMessages = 0)
        assertTrue(desk.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aPeerCancelAfterOurRestartCleansThePersistedTransfer() {
        val id = UUID.randomUUID().toString()
        val store = FileTransferStore(desk.root)
        store.save(FileCheckpoint(id, "receive", "t".repeat(32), "phone", "a.bin", 100, 1, 0))
        store.partial(id).writeBytes(ByteArray(5))
        desk.manager.handleControl(deskWire, desk.control(WebRtcFileAction.CANCEL, id) { copy(code = FileCodes.USER) })
        pump(maxMessages = 0)
        assertNull(store.load(id))
        assertFalse(store.partial(id).exists())
    }

    @Test
    fun aFloodOfFramesPastTheQueueCapPausesTheReceiversInsteadOfDroppingSilently() {
        val id = UUID.randomUUID().toString()
        desk.manager.handleControl(deskWire, offer(id = id, size = 100L * FileV2.BLOCK_BYTES))
        pump(maxMessages = 0)
        val frame = FileFrame.encode(UUID.fromString(id), 0, 0, 0, ByteArray(60_000), 0, 60_000, null)
        // The file thread is busy (nothing runs): frames pile up until the cap.
        repeat((FileV2.FILE_QUEUE_MAX_BYTES / frame.size).toInt() + 50) { desk.manager.handleFrame(frame) }
        pump(maxMessages = 0)
        val pause = desk.sent.last { it.action == WebRtcFileAction.PAUSE }
        assertEquals(FileCodes.BACKLOG, pause.reason)
        assertEquals("paused, not cancelled: ${desk.sent.filter { it.action == WebRtcFileAction.ERROR || it.action == WebRtcFileAction.CANCEL }.map { it.code + it.message }}", 0, desk.sent.count { it.action == WebRtcFileAction.ERROR || it.action == WebRtcFileAction.CANCEL })
        // Drained, it asks the sender to restart at its last verified block.
        assertTrue(desk.sent.any { it.action == WebRtcFileAction.RESUME })
    }

    @Test
    fun malformedFramesAreDroppedOnTheCallbackThreadWithoutQueueingWork() {
        val before = desk.runner.pendingCount()
        desk.manager.handleFrame(ByteArray(0))
        desk.manager.handleFrame(ByteArray(100) { 1 })
        desk.manager.handleFrame("DLF2".toByteArray() + ByteArray(60))
        assertEquals(before, desk.runner.pendingCount())
    }

    @Test
    fun twoBigTransfersAndALateOneAllMakeProgressTogether() {
        val a = data(3 * FileV2.BLOCK_BYTES + 1)
        val b = data(2 * FileV2.BLOCK_BYTES + 7).also { it[0] = 9 }
        val ra = Recorder(); val rb = Recorder()
        val fa = startSend(share(a, "a.bin"), ra)
        val fb = startSend(share(b, "b.bin"), rb)
        assertTrue(runUntilDone(fa))
        assertTrue(runUntilDone(fb))
        val byName = desk.delivered.associateBy { it.getString("filename") }
        assertArrayEquals(a, deliveredBytes(byName.getValue("a.bin")))
        assertArrayEquals(b, deliveredBytes(byName.getValue("b.bin")))
    }
}
