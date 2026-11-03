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
import org.junit.Assert.assertNotNull
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
    class Received(val name: String, val bytes: ByteArray, val completesSentBefore: Int = 0)

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var phoneContext: Application
    private val executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    /** A file thread whose work the test runs itself. */
    private class ManualRunner : FileRunner {
        var tickStep: (() -> Boolean)? = null
        override fun tickWhile(periodMs: Long, step: () -> Boolean) { tickStep = step }
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

    private inner class Side(val context: Context, val wire: WebRtcWireBinding, val peerWire: WebRtcWireBinding, val lanes: FileLanes? = null) {
        val runner = ManualRunner()
        /** What this side's destination was handed: a verified file, copied out before the part is deleted. */
        val delivered = CopyOnWriteArrayList<Received>()
        var publishFailure: java.io.IOException? = null
        var free = Long.MAX_VALUE
        var publishes = 0
        val destination = object : org.desklink.mobile.plugins.share.ReceiveDestination {
            override fun freeBytes() = free
            override fun publish(source: File, name: String, lastModified: Long?, mime: String?): org.desklink.mobile.plugins.share.Published {
                publishes++
                publishFailure?.let { throw it }
                delivered += Received(name, source.readBytes(), sent.count { it.action == WebRtcFileAction.COMPLETE })
                return org.desklink.mobile.plugins.share.Published(android.net.Uri.parse("content://fake/$name"), name, mime, source.length())
            }
        }
        val device: Device = mockk<Device>(relaxed = true).also { every { it.deviceId } returns wire.peerDeviceId }
        val outControl = ArrayDeque<WebRtcFileControl>()
        val outFrames = ArrayDeque<ByteArray>()
        var linkDown = false
        var frameCount = 0
        var bufferedAmount = 0L
        val sent = CopyOnWriteArrayList<WebRtcFileControl>()
        val link = object : FileLink {
            override fun sendControl(control: WebRtcFileControl) {
                if (linkDown) throw org.desklink.mobile.transport.TransportError(org.desklink.mobile.transport.TransportErrorCode.CLOSED, "closed")
                sent += control
                outControl.add(control)
            }
            override fun sendFrame(message: ByteArray) {
                frameCount++
                if (linkDown) throw org.desklink.mobile.transport.TransportError(org.desklink.mobile.transport.TransportErrorCode.CLOSED, "closed")
                outFrames.add(message)
            }
            override fun bufferedAmount() = bufferedAmount
        }
        var now = 0L
        fun tick() { runner.tickStep?.invoke() }
        val manager = WebRtcFileTransferManager(context, device, link, runner, { now }, { Long.MAX_VALUE }, destination, java.util.concurrent.Executor { it.run() }, lanes)
        val root: File get() = File(context.filesDir, "webrtc-transfers")
        fun control(action: WebRtcFileAction, id: String, token: String = "t".repeat(32), block: WebRtcFileControl.() -> WebRtcFileControl = { this }) = WebRtcFileControl(
            action = action, transferId = id, deviceId = peerWire.senderDeviceId, sessionId = peerWire.sessionId,
            connectionGeneration = peerWire.generation, transferToken = token,
        ).block()
    }

    private lateinit var phone: Side
    private lateinit var desk: Side
    private lateinit var phoneLanes: FakeLanes
    private lateinit var deskLanes: FakeLanes

    /** Lane A in memory: frames written to it go straight to the other manager's lane intake. */
    private class FakeLanes : FileLanes {
        var offer = false
        var issues = false
        var dialOk = true
        var failAfterFrames = Int.MAX_VALUE
        /** Only the first lane breaks; a later one (promotion) is whole. */
        var failFirstOnly = false
        /** The lane swallows frames (they never arrive), as a lane does that is closing. */
        var swallow = false
        /** The first frame of this block is damaged on the way, the next [corruptTimes] times it is sent over a lane. */
        var corruptBlock = -1
        var corruptTimes = 0
        var dialed = 0
        var failedCalls = 0
        var cleanCalls = 0
        var coolingNow = false
        var peer: () -> WebRtcFileTransferManager = { error("no peer") }
        var out: FakeOut? = null
        val outs = mutableListOf<FakeOut>()
        private var serial = 0L

        inner class FakeOut(private val target: WebRtcFileTransferManager, val transferId: String, val connection: Long) : LaneOut {
            var frames = 0
            @Volatile var closed = false
            override var onFailure: ((Throwable) -> Unit)? = null
            override fun write(frame: ByteArray) {
                if (closed) throw java.io.IOException("closed")
                if (++frames > failAfterFrames) { closed = true; throw java.io.IOException("lane broke") }
                if (swallow) return
                var delivered = frame
                if (corruptTimes > 0) {
                    val f = FileFrame.decode(frame, FileV2.FRAME_BYTES_PROVEN)
                    if (f.blockIndex == corruptBlock && f.offset % FileV2.BLOCK_BYTES == 0L) {
                        corruptTimes--
                        delivered = frame.copyOf().also { it[FileV2.FRAME_HEADER_BYTES] = (it[FileV2.FRAME_HEADER_BYTES] + 1).toByte() }
                    }
                }
                target.handleLaneFrame(delivered, connection)
            }
            override fun buffered() = 0L
            override fun close() {
                if (!closed || !endedTold) { closed = true; endedTold = true; target.onLaneEnded(transferId, connection) } // the receiver's reader reaches the end
            }
            private var endedTold = false
        }

        override fun canOffer(totalSize: Long) = offer
        override fun issue(transferId: String) = if (issues) FileLaneSpec(v = 1, port = 1717, token = "00112233445566778899aabbccddeeff", ttlMs = 60_000) else null
        override fun cooling(nowMs: Long) = coolingNow
        override fun failed(nowMs: Long) { failedCalls++ }
        override fun clean() { cleanCalls++ }
        /** The dial is not answered until [heldDial] is called (a connection still being made). */
        var holdDial = false
        var heldDial: (() -> Unit)? = null

        override fun dial(transferId: String, spec: FileLaneSpec, pass: Int, startBlock: Int, result: (LaneOut?) -> Unit) {
            dialed++
            if (!dialOk) return result(null)
            val finish = {
                val connection = ++serial
                peer().onLaneAccepted(transferId, pass, startBlock, connection) // as the real listener does once the token is good
                if (failFirstOnly && dialed > 1) failAfterFrames = Int.MAX_VALUE
                result(FakeOut(peer(), transferId, connection).also { out = it; outs += it })
            }
            if (holdDial) heldDial = finish else finish()
        }
    }

    @Before
    fun setUp() {
        phoneContext = ApplicationProvider.getApplicationContext()
        File(phoneContext.filesDir, "webrtc-transfers").deleteRecursively()
        File(phoneContext.filesDir, "webrtc-delivered.json").delete()
        phoneLanes = FakeLanes()
        deskLanes = FakeLanes()
        phone = Side(phoneContext, phoneWire, deskWire, phoneLanes)
        val deskDir = tmp.newFolder()
        val deskContext = mockk<Context>()
        every { deskContext.applicationContext } returns deskContext
        every { deskContext.filesDir } returns deskDir
        desk = Side(deskContext, deskWire, phoneWire, deskLanes)
        phoneLanes.peer = { desk.manager }
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

    private fun deliveredBytes(received: Received): ByteArray = received.bytes

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
        assertEquals("photo.jpg", desk.delivered.single().name)
        assertArrayEquals(bytes, deliveredBytes(desk.delivered.single()))
        assertTrue("nothing left of the spool or the checkpoint on the sender", phone.root.listFiles().orEmpty().isEmpty())
        assertTrue(desk.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun theSenderHearsCompleteOnlyAfterTheFileIsInPlace() {
        val bytes = data(FileV2.BLOCK_BYTES + 777)
        assertTrue(runUntilDone(startSend(share(bytes, "report.pdf"), Recorder())))
        val received = desk.delivered.single()
        assertEquals("report.pdf", received.name)
        assertArrayEquals(bytes, received.bytes)
        assertEquals("no `complete` had gone out when the file was handed to its destination", 0, received.completesSentBefore)
        assertEquals("and it went out after", 1, desk.sent.count { it.action == WebRtcFileAction.COMPLETE })
    }

    @Test
    fun aFileThatCannotBeSavedIsAnErrorToTheSenderAndIsKeptForARetry() {
        val bytes = data(2 * FileV2.BLOCK_BYTES + 5)
        desk.publishFailure = java.io.IOException("No space left on device")
        val recorder = Recorder()
        assertFalse(runUntilDone(startSend(share(bytes, "big.bin"), recorder)))
        assertEquals("never `complete` for a file that is not in place", 0, desk.sent.count { it.action == WebRtcFileAction.COMPLETE })
        assertEquals("a full disk says so", FileCodes.STORAGE_FULL, desk.sent.last { it.action == WebRtcFileAction.ERROR }.code)
        assertEquals("the sender is told it failed", 1, recorder.failures.size)
        assertEquals("in words the person can act on", true, desk.sent.last { it.action == WebRtcFileAction.ERROR }.message?.startsWith("Not enough space on the phone"))
        assertTrue(org.desklink.mobile.plugins.share.UnpublishedFiles.list(desk.context).single().storageFull)
        assertTrue("nothing is left in the transfer directory", desk.root.listFiles().orEmpty().isEmpty())
        // The verified file is kept whole.
        val kept = org.desklink.mobile.plugins.share.UnpublishedFiles.list(desk.context)
        assertEquals(listOf("big.bin"), kept.map { it.name })
        assertArrayEquals(bytes, kept.single().file.readBytes())
        // Room again: trying once more puts it in place and clears the kept copy.
        desk.publishFailure = null
        val published = org.desklink.mobile.plugins.share.UnpublishedFiles.retry(desk.context, kept.single().id, desk.destination)
        assertEquals("big.bin", (published as org.desklink.mobile.plugins.share.UnpublishedFiles.Retry.Done).published.name)
        assertArrayEquals(bytes, desk.delivered.single().bytes)
        assertEquals(emptyList<Any>(), org.desklink.mobile.plugins.share.UnpublishedFiles.list(desk.context))
    }

    @Test
    fun aRetryThatFailsAgainKeepsTheFile() {
        val bytes = data(100)
        desk.publishFailure = java.io.IOException("the folder is gone")
        assertFalse(runUntilDone(startSend(share(bytes, "x.txt"), Recorder())))
        val id = org.desklink.mobile.plugins.share.UnpublishedFiles.list(desk.context).single().id
        assertTrue(org.desklink.mobile.plugins.share.UnpublishedFiles.retry(desk.context, id, desk.destination) is org.desklink.mobile.plugins.share.UnpublishedFiles.Retry.Failed)
        assertEquals(1, org.desklink.mobile.plugins.share.UnpublishedFiles.list(desk.context).size)
    }

    @Test
    fun withoutRoomForASecondWholeCopyNothingIsPublishedAndTheFileIsKept() {
        val bytes = data(FileV2.BLOCK_BYTES + 5)
        desk.free = bytes.size + 1024L // the copy needs the size plus a reserve
        val recorder = Recorder()
        assertFalse(runUntilDone(startSend(share(bytes, "big.bin"), recorder)))
        assertEquals("checked before a long copy that would fail halfway", 0, desk.publishes)
        assertEquals(FileCodes.STORAGE_FULL, desk.sent.last { it.action == WebRtcFileAction.ERROR }.code)
        val kept = org.desklink.mobile.plugins.share.UnpublishedFiles.list(desk.context).single()
        assertTrue(kept.storageFull)
        // Retrying with the room still short says how much to free.
        val again = org.desklink.mobile.plugins.share.UnpublishedFiles.retry(desk.context, kept.id, desk.destination)
        val failed = again as org.desklink.mobile.plugins.share.UnpublishedFiles.Retry.Failed
        assertEquals(bytes.size + org.desklink.mobile.plugins.share.UnpublishedFiles.RESERVE_BYTES - desk.free, failed.freeUpBytes)
        assertEquals(0, desk.publishes)
        desk.free = Long.MAX_VALUE
        assertTrue(org.desklink.mobile.plugins.share.UnpublishedFiles.retry(desk.context, kept.id, desk.destination) is org.desklink.mobile.plugins.share.UnpublishedFiles.Retry.Done)
    }

    @Test
    fun aSecondTapOnTryAgainWhileTheFirstRunsPublishesNothingTwice() {
        val bytes = data(100)
        desk.publishFailure = java.io.IOException("the folder is gone")
        assertFalse(runUntilDone(startSend(share(bytes, "x.txt"), Recorder())))
        desk.publishFailure = null
        val id = org.desklink.mobile.plugins.share.UnpublishedFiles.list(desk.context).single().id
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slow = object : org.desklink.mobile.plugins.share.ReceiveDestination {
            var calls = 0
            override fun publish(source: File, name: String, lastModified: Long?, mime: String?): org.desklink.mobile.plugins.share.Published {
                calls++
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                return org.desklink.mobile.plugins.share.Published(android.net.Uri.parse("content://fake/$name"), name, mime, source.length())
            }
        }
        val first = executor.submit<org.desklink.mobile.plugins.share.UnpublishedFiles.Retry> { org.desklink.mobile.plugins.share.UnpublishedFiles.retry(desk.context, id, slow) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        assertTrue(org.desklink.mobile.plugins.share.UnpublishedFiles.isRetrying(id))
        assertEquals(org.desklink.mobile.plugins.share.UnpublishedFiles.Retry.Busy, org.desklink.mobile.plugins.share.UnpublishedFiles.retry(desk.context, id, slow))
        release.countDown()
        assertTrue(first.get(10, TimeUnit.SECONDS) is org.desklink.mobile.plugins.share.UnpublishedFiles.Retry.Done)
        assertEquals("published once", 1, slow.calls)
        assertFalse(org.desklink.mobile.plugins.share.UnpublishedFiles.isRetrying(id))
    }

    @Test
    fun aFileAlreadyInPlaceIsNotReceivedAgainWhenTheSenderOffersItAgain() {
        // The `complete` never reached the sender (lost, or the phone died right after publishing): the sender offers again.
        val bytes = data(FileV2.BLOCK_BYTES + 5)
        assertTrue(runUntilDone(startSend(share(bytes, "report.pdf"), Recorder())))
        val offer = phone.sent.first { it.action == WebRtcFileAction.OFFER }
        assertEquals(1, desk.delivered.size)
        desk.sent.clear()
        // A restarted phone: a new manager on the same storage.
        val restarted = WebRtcFileTransferManager(desk.context, desk.device, desk.link, desk.runner, { 0L }, { Long.MAX_VALUE }, desk.destination, java.util.concurrent.Executor { it.run() })
        restarted.handleControl(deskWire, offer)
        desk.runner.runPending()
        val accept = desk.sent.single { it.action == WebRtcFileAction.ACCEPT }
        assertEquals("all of it is here", FileV2.blockCount(bytes.size.toLong()), accept.resumeBlock)
        assertEquals(1, desk.delivered.size)
        // The sender's `done`: answered `complete` with the same hash, nothing written again.
        val done = desk.control(WebRtcFileAction.DONE, offer.transferId, offer.transferToken) {
            copy(fileHash = accept.prefixSha256, totalSize = bytes.size.toLong(), blocks = FileV2.blockCount(bytes.size.toLong()))
        }
        restarted.handleControl(deskWire, done)
        desk.runner.runPending()
        assertEquals(accept.prefixSha256, desk.sent.single { it.action == WebRtcFileAction.COMPLETE }.fileHash)
        assertEquals("never a second copy", 1, desk.delivered.size)
        assertEquals(1, desk.publishes)
    }

    /** A provider like the Downloads app's: a size and a name, a descriptor for the bytes. */
    class FilesProvider : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: android.net.Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): android.database.Cursor {
            if (revoked) throw SecurityException("the grant is gone")
            val file = files.getValue(uri.lastPathSegment!!)
            val columns = projection ?: arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE)
            return android.database.MatrixCursor(columns).apply {
                addRow(columns.map<String, Any?> { c ->
                    when (c) {
                        android.provider.OpenableColumns.DISPLAY_NAME -> file.name
                        android.provider.OpenableColumns.SIZE -> file.length()
                        "last_modified", "date_modified" -> file.lastModified() / 1000
                        else -> null
                    }
                }.toTypedArray())
            }
        }
        override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor {
            if (revoked) throw SecurityException("the grant is gone")
            return android.os.ParcelFileDescriptor.open(files.getValue(uri.lastPathSegment!!), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun getType(uri: android.net.Uri) = "application/octet-stream"
        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
        override fun delete(uri: android.net.Uri, selection: String?, args: Array<String>?) = 0
        override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<String>?) = 0
        companion object { val files = HashMap<String, File>(); @Volatile var revoked = false }
    }

    /**
     * The real fault: a file shared from another app is readable only while the sharing screen's grant lasts, which ends
     * moments after the screen closes. A file of one block was sent before that; a bigger one asked the provider again
     * for block 2 (and its size), got "no", and was cancelled as "no longer exists".
     */
    @Test
    fun aSharedFileOfSeveralBlocksStillGoesAcrossAfterTheShareScreenGrantEnds() {
        org.robolectric.Robolectric.setupContentProvider(FilesProvider::class.java, "org.desklink.test.files")
        val bytes = data(3 * FileV2.BLOCK_BYTES + 5)
        FilesProvider.files["late"] = tmp.newFile("late.bin").apply { writeBytes(bytes) }
        FilesProvider.revoked = false
        val packet = org.desklink.mobile.helpers.FilesHelper.uriToNetworkPacket(phoneContext, android.net.Uri.parse("content://org.desklink.test.files/late"), DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST)!!
        try {
            FilesProvider.revoked = true // the share screen closed
            val recorder = Recorder()
            assertTrue(runUntilDone(startSend(packet, recorder)))
            assertEquals(emptyList<Throwable>(), recorder.failures.toList())
            assertArrayEquals(bytes, desk.delivered.single().bytes)
        } finally {
            FilesProvider.revoked = false
        }
    }

    @Test
    fun aStoredSendWhoseSourceCannotBeOpenedAfterARestartIsEndedVisiblyNotRetried() {
        org.robolectric.Robolectric.setupContentProvider(FilesProvider::class.java, "org.desklink.test.files")
        FilesProvider.files["lost"] = tmp.newFile("lost.bin").apply { writeBytes(data(100)) }
        val id = UUID.randomUUID().toString()
        val store = FileTransferStore(phone.root)
        assertTrue(
            store.save(
                FileCheckpoint(id, "send", "t".repeat(32), phoneWire.peerDeviceId, "lost.bin", 100, 5, 0, sourceUri = "content://org.desklink.test.files/lost", spooled = false, meta = emptyMap()),
            ),
        )
        org.desklink.mobile.plugins.share.SendAgain.dismiss(phoneContext, id)
        FilesProvider.revoked = true // after the restart the share's grant is gone
        try {
            phone.manager.resumeSends(phoneWire)
            pump()
            assertEquals("no offer goes out for it", emptyList<WebRtcFileControl>(), phone.sent.filter { it.action == WebRtcFileAction.OFFER })
            assertNull("the stale record is gone, so it never loops", store.load(id))
            val item = org.desklink.mobile.plugins.share.SendAgain.list(phoneContext).single { it.id == id }
            assertEquals("lost.bin", item.name)
            assertTrue("said as: Android no longer lets DeskLink read it", item.unreadable)
            phone.manager.resumeSends(phoneWire)
            pump()
            assertEquals("a second connection does not report it again", 1, org.desklink.mobile.plugins.share.SendAgain.list(phoneContext).count { it.id == id })
        } finally {
            FilesProvider.revoked = false
            org.desklink.mobile.plugins.share.SendAgain.dismiss(phoneContext, id)
        }
    }

    @Test
    fun aSourceNotOpenedWhileTheGrantLastedIsReportedInWordsNotSilently() {
        org.robolectric.Robolectric.setupContentProvider(FilesProvider::class.java, "org.desklink.test.files")
        FilesProvider.files["gone"] = tmp.newFile("gone.bin").apply { writeBytes(data(100)) }
        val packet = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
            this["filename"] = "gone.bin"
            payload = NetworkPacket.Payload(100L).also { it.source = org.desklink.mobile.webrtc.UriFileSource(phoneContext, android.net.Uri.parse("content://org.desklink.test.files/gone")) }
        }
        FilesProvider.revoked = true
        try {
            val recorder = Recorder()
            assertFalse(runUntilDone(startSend(packet, recorder)))
            assertEquals("The file no longer exists.", recorder.failures.single().message)
        } finally {
            FilesProvider.revoked = false
        }
    }

    @Test
    fun aContentUriFileOfSeveralBlocksGoesAcrossLikeAShareFromTheDownloadsApp() {
        org.robolectric.Robolectric.setupContentProvider(FilesProvider::class.java, "org.desklink.test.files")
        for (size in listOf(64, FileV2.BLOCK_BYTES, 2 * FileV2.BLOCK_BYTES, 3 * FileV2.BLOCK_BYTES + 5)) {
            val bytes = data(size)
            val file = tmp.newFile("share-$size.bin").apply { writeBytes(bytes) }
            FilesProvider.files["f$size"] = file
            val uri = android.net.Uri.parse("content://org.desklink.test.files/f$size")
            val packet = org.desklink.mobile.helpers.FilesHelper.uriToNetworkPacket(phoneContext, uri, DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST)!!
            val recorder = Recorder()
            val before = desk.delivered.size
            assertTrue("$size bytes", runUntilDone(startSend(packet, recorder)))
            assertEquals("$size bytes: ${recorder.failures.map { it.message }}", emptyList<Throwable>(), recorder.failures.toList())
            assertEquals(before + 1, desk.delivered.size)
            assertArrayEquals(bytes, desk.delivered.last().bytes)
        }
    }

    private fun sha(bytes: ByteArray, from: Int, to: Int) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(from, to))

    private fun bucketMeta(path: String, sha: String) = mapOf("folder" to "bucket", "relpath" to path, "contentSha256" to sha)

    @Test
    fun aBucketFileOfferedAgainUnderANewIdKeepsItsVerifiedBlocksAndRedoesUnderOneBlock() {
        val block = FileV2.BLOCK_BYTES
        val bytes = data(10 * block + 100)
        val oldId = UUID.randomUUID().toString()
        val newId = UUID.randomUUID().toString()
        val contentSha = "ab".repeat(32)
        val store = FileTransferStore(desk.root)
        // The link was cut after three verified blocks and part of the fourth.
        store.partial(oldId).writeBytes(bytes.copyOfRange(0, 3 * block + 5000))
        val hashes = (0 until 3).map { sha(bytes, it * block, (it + 1) * block) }
        assertTrue(store.writeBlocks(oldId, hashes))
        assertTrue(store.save(FileCheckpoint(oldId, "receive", "o".repeat(32), deskWire.peerDeviceId, "big.bin", bytes.size.toLong(), 5, 3, meta = bucketMeta("a/big.bin", contentSha))))
        val offer = desk.control(WebRtcFileAction.OFFER, newId, "n".repeat(32)) {
            copy(filename = "big.bin", totalSize = bytes.size.toLong(), mtime = 5, blockSize = block, maxFrameBytes = FileV2.FRAME_BYTES_PROVEN,
                folder = "bucket", relpath = "a/big.bin", contentSha256 = contentSha)
        }
        val adopted = desk.manager.adoptBucketCheckpoint(deskWire, offer)!!
        assertEquals(newId, adopted.transferId)
        assertNull("nothing left under the old id", store.load(oldId))
        assertFalse(store.partial(oldId).exists())
        // The receiver answers from block 3: only the half-written fourth block is sent again.
        val link = object : FileLink {
            val out = ArrayList<WebRtcFileControl>()
            override fun sendControl(control: WebRtcFileControl) { out += control }
            override fun sendFrame(message: ByteArray) = Unit
            override fun bufferedAmount() = 0L
        }
        val listener = object : FileReceiver.Listener {
            override fun progress(receiver: FileReceiver, bytesDone: Long) = Unit
            override fun deliver(receiver: FileReceiver, partial: File, fileHash: String) = Unit
            override fun ended(receiver: FileReceiver, outcome: FileReceiver.Outcome, message: String?) = Unit
        }
        FileReceiver(deskWire, store, link, { 0L }, { Long.MAX_VALUE }, listener, offer).start(adopted)
        val accept = link.out.single { it.action == WebRtcFileAction.ACCEPT }
        assertEquals(3, accept.resumeBlock)
        assertEquals(FileHashes.prefixHash(hashes, 3), accept.prefixSha256)
        assertEquals("the verified prefix is the file on disk", (3L * block), store.partial(newId).length())
    }

    @Test
    fun aBucketFileRequestedAgainAfterALinkCutResumesTheStoredSendInsteadOfStartingOver() {
        val block = FileV2.BLOCK_BYTES
        val bytes = data(6 * block + 7)
        val oldId = UUID.randomUUID().toString()
        val contentSha = "cd".repeat(32)
        val store = FileTransferStore(phone.root)
        assertTrue(store.writeBlocks(oldId, (0 until 3).map { sha(bytes, it * block, (it + 1) * block) }))
        assertTrue(
            store.save(
                FileCheckpoint(oldId, "send", "o".repeat(32), phoneWire.peerDeviceId, "big.bin", bytes.size.toLong(), 77, 3, sourceUri = "file:///x", meta = bucketMeta("big.bin", contentSha) + ("mtime" to "77")),
            ),
        )
        val packet = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
            this["filename"] = "big.bin"
            this["folder"] = "bucket"
            this["relpath"] = "big.bin"
            this["mtime"] = 77L
            this["contentSha256"] = contentSha
            payload = NetworkPacket.Payload(bytes.size.toLong()).also {
                it.source = object : FileSource {
                    override fun stat() = FileSource.Stat(bytes.size.toLong(), 77)
                    override fun open(offset: Long): InputStream = java.io.ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
                }
            }
        }
        startSend(packet, Recorder())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (phone.sent.none { it.action == WebRtcFileAction.OFFER } && System.nanoTime() < deadline) { phone.runner.runPending(); Thread.sleep(2) }
        val offer = phone.sent.first { it.action == WebRtcFileAction.OFFER }
        assertEquals("the same transfer, not a new one", oldId, offer.transferId)
        assertEquals("o".repeat(32), offer.transferToken)
        assertEquals(1, store.list().count { it.direction == "send" })
    }

    private fun bucketPacket(bytes: ByteArray, path: String, sha: String) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
        this["filename"] = path.substringAfterLast('/')
        this["folder"] = "bucket"
        this["relpath"] = path
        this["mtime"] = 77L
        this["contentSha256"] = sha
        payload = NetworkPacket.Payload(bytes.size.toLong()).also {
            it.source = object : FileSource {
                override fun stat() = FileSource.Stat(bytes.size.toLong(), 77)
                override fun open(offset: Long): InputStream = java.io.ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
            }
        }
    }

    /** The computer's side of a Bucket send: the phone asked for [path] (the engine's wanted list), and has room for it. */
    private fun asBucketFileWanted(path: String, size: Long, sha: String, body: () -> Unit) {
        val engine = org.desklink.mobile.plugins.folders.BucketEngine
        @Suppress("UNCHECKED_CAST")
        val wanted = engine::class.java.getDeclaredField("wanted").apply { isAccessible = true }.get(null) as HashMap<Pair<String, String>, org.desklink.mobile.plugins.folders.Entry>
        synchronized(wanted) { wanted["phone" to path] = org.desklink.mobile.plugins.folders.Entry(size, 77, sha) }
        val saved = engine.freeSpace
        engine.freeSpace = { Long.MAX_VALUE / 2 }
        try {
            body()
        } finally {
            engine.freeSpace = saved
            synchronized(wanted) { wanted.remove("phone" to path) }
        }
    }

    private fun startBucketSend(bytes: ByteArray, sha: String, recorder: Recorder): Future<Boolean> {
        val future = executor.submit<Boolean> { phone.manager.sendPayload(phoneWire, bucketPacket(bytes, "A/big.bin", sha), recorder) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (desk.sent.count { it.action == WebRtcFileAction.BLOCK_ACK } < 1 && System.nanoTime() < deadline) { if (pump(maxMessages = 5) == 0) Thread.sleep(2) }
        assertTrue("the transfer is live", desk.sent.any { it.action == WebRtcFileAction.ACCEPT })
        return future
    }

    @Test
    fun removingABucketFileWhileThePhoneSendsItEndsTheSendAndTellsTheComputer() {
        val bytes = data(6 * FileV2.BLOCK_BYTES)
        val sha = "cd".repeat(32)
        asBucketFileWanted("A/big.bin", bytes.size.toLong(), sha) {
            val recorder = Recorder()
            val future = startBucketSend(bytes, sha, recorder)
            phone.manager.cancelFolderSend("A/big.bin")
            assertFalse(runUntilDone(future))
            assertEquals(FileCodes.REMOVED, phone.sent.last { it.action == WebRtcFileAction.CANCEL }.code)
            assertEquals(1, recorder.failures.size)
            assertEquals(0, recorder.successes.get())
            assertTrue("nothing is left of it on the sender", FileTransferStore(phone.root).list().none { it.direction == "send" })
        }
    }

    @Test
    fun removingABucketFileWhileItArrivesEndsTheReceiveAndTellsTheComputer() {
        val bytes = data(6 * FileV2.BLOCK_BYTES)
        val sha = "ef".repeat(32)
        asBucketFileWanted("A/big.bin", bytes.size.toLong(), sha) {
            val recorder = Recorder()
            val future = startBucketSend(bytes, sha, recorder)
            desk.manager.cancelFolderReceive("A/big.bin")
            assertFalse(runUntilDone(future))
            assertEquals(FileCodes.REMOVED, desk.sent.last { it.action == WebRtcFileAction.CANCEL }.code)
            assertEquals(1, recorder.failures.size)
        }
    }

    @Test
    fun aGalleryImageIsOfferedWithTheGalleryFlagAndItsTypeAndNothingFromABucketOrTheClipboard() {
        val bytes = data(1000)
        val packet = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
            this["filename"] = "Screenshot_1.png"
            this["gallery"] = true
            this["mimeType"] = "image/png"
            this["mtime"] = 1_730_000_000_000L
            payload = NetworkPacket.Payload(bytes.size.toLong()).also {
                it.source = object : FileSource {
                    override fun stat() = FileSource.Stat(bytes.size.toLong(), 1_730_000_000_000L)
                    override fun open(offset: Long): InputStream = java.io.ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
                }
            }
        }
        startSend(packet, Recorder())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (phone.sent.none { it.action == WebRtcFileAction.OFFER } && System.nanoTime() < deadline) { phone.runner.runPending(); Thread.sleep(2) }
        val offer = phone.sent.first { it.action == WebRtcFileAction.OFFER }
        assertEquals(true, offer.gallery)
        assertEquals("image/png", offer.mimeType)
        assertEquals(1_730_000_000_000L, offer.mtime)
        assertEquals("Screenshot_1.png", offer.filename)
        assertNull(offer.folder); assertNull(offer.clipboard); assertNull(offer.captureId); assertNull(offer.relpath)
        // On the wire as an additive boolean, and read back.
        assertEquals(true, WebRtcFileControl.fromJson(offer.toJson()).gallery)
    }

    @Test
    fun removingABucketFileWhileItsSendIsPausedByALostLinkForgetsTheStoredSend() {
        val oldId = UUID.randomUUID().toString()
        val store = FileTransferStore(phone.root)
        assertTrue(store.save(FileCheckpoint(oldId, "send", "o".repeat(32), phoneWire.peerDeviceId, "big.bin", 100, 77, 0, sourceUri = "file:///x", meta = bucketMeta("A/big.bin", "cd".repeat(32)))))
        val other = UUID.randomUUID().toString()
        assertTrue(store.save(FileCheckpoint(other, "send", "p".repeat(32), phoneWire.peerDeviceId, "keep.bin", 100, 77, 0, sourceUri = "file:///y", meta = bucketMeta("A/keep.bin", "ef".repeat(32)))))
        phone.manager.cancelFolderSend("A/big.bin")
        pump()
        assertEquals(listOf(other), store.list().filter { it.direction == "send" }.map { it.transferId })
    }

    @Test
    fun turningTheGalleryOffForgetsItsStoredSendsAndOnlyThose() {
        val store = FileTransferStore(phone.root)
        val gallery = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        assertTrue(store.save(FileCheckpoint(gallery, "send", "g".repeat(32), phoneWire.peerDeviceId, "shot.png", 100, 77, 0, sourceUri = "file:///s", meta = mapOf("gallery" to "true", "mimeType" to "image/png"))))
        assertTrue(store.save(FileCheckpoint(other, "send", "h".repeat(32), phoneWire.peerDeviceId, "doc.pdf", 100, 77, 0, sourceUri = "file:///d")))
        phone.manager.cancelGallery()
        pump()
        assertEquals(listOf(other), store.list().filter { it.direction == "send" }.map { it.transferId })
    }

    private fun resumedJob(id: String) = (1L shl 40) + (id.hashCode().toLong() and 0xffffffffL)
    private fun receiveJob(id: String) = (1L shl 41) + (id.hashCode().toLong() and 0xffffffffL)

    @Test
    fun cancelOnTheTransferNotificationEndsTheShownResumedSendsAndNotTheBucketsOwnOrOtherFiles() {
        val store = FileTransferStore(phone.root)
        val shown = UUID.randomUUID().toString()
        val unlisted = UUID.randomUUID().toString()
        val bucket = UUID.randomUUID().toString()
        assertTrue(store.save(FileCheckpoint(shown, "send", "p".repeat(32), phoneWire.peerDeviceId, "a.bin", 100, 77, 0, sourceUri = "file:///a")))
        assertTrue(store.save(FileCheckpoint(unlisted, "send", "r".repeat(32), phoneWire.peerDeviceId, "c.bin", 100, 77, 0, sourceUri = "file:///c")))
        assertTrue(store.save(FileCheckpoint(bucket, "send", "q".repeat(32), phoneWire.peerDeviceId, "b.bin", 100, 77, 0, sourceUri = "file:///b", meta = bucketMeta("A/b.bin", "cd".repeat(32)))))
        phone.manager.cancelJobs(setOf(resumedJob(shown)))
        pump()
        assertEquals(setOf(unlisted, bucket), store.list().filter { it.direction == "send" }.map { it.transferId }.toSet())
    }

    @Test
    fun cancelOnTheNotificationEndsALiveReceiveAndTellsTheComputer() {
        val bytes = data(6 * FileV2.BLOCK_BYTES)
        val recorder = Recorder()
        val future = startSend(share(bytes), recorder)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (desk.sent.count { it.action == WebRtcFileAction.BLOCK_ACK } < 1 && System.nanoTime() < deadline) { if (pump(maxMessages = 5) == 0) Thread.sleep(2) }
        val id = phone.sent.first { it.action == WebRtcFileAction.OFFER }.transferId
        desk.manager.cancelJobs(setOf(receiveJob(id)))
        assertFalse(runUntilDone(future))
        assertEquals(FileCodes.USER, desk.sent.last { it.action == WebRtcFileAction.CANCEL }.code)
        assertEquals(1, recorder.failures.size)
        assertTrue("the partial is gone", desk.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancelOnTheNotificationEndsALiveSend() {
        val bytes = data(6 * FileV2.BLOCK_BYTES)
        val recorder = Recorder()
        val future = startSend(share(bytes), recorder)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (desk.sent.count { it.action == WebRtcFileAction.BLOCK_ACK } < 1 && System.nanoTime() < deadline) { if (pump(maxMessages = 5) == 0) Thread.sleep(2) }
        val id = phone.sent.first { it.action == WebRtcFileAction.OFFER }.transferId
        phone.manager.cancelJobs(setOf(resumedJob(id)))
        assertFalse(runUntilDone(future))
        assertEquals(FileCodes.USER, phone.sent.last { it.action == WebRtcFileAction.CANCEL }.code)
        assertEquals(1, recorder.failures.size)
    }

    @Test
    fun aBigFileGoesOverTheFastLaneWhenBothSidesHaveItAndNoFrameUsesTheDataChannel() {
        phoneLanes.offer = true; deskLanes.issues = true
        val bytes = data(3 * FileV2.BLOCK_BYTES + 17)
        assertTrue(runUntilDone(startSend(share(bytes, "big.bin"), Recorder())))
        val offer = phone.sent.first { it.action == WebRtcFileAction.OFFER }
        assertEquals(1, offer.lane?.v)
        assertNotNull("the accept carried the lane", desk.sent.first { it.action == WebRtcFileAction.ACCEPT }.lane?.token)
        assertEquals(1, phoneLanes.dialed)
        assertEquals("every frame went over the lane", 0, phone.frameCount)
        assertTrue(phoneLanes.out!!.frames > 0)
        assertArrayEquals(bytes, desk.delivered.single().bytes)
        assertNull("the lane label of a finished file is forgotten", org.desklink.mobile.plugins.share.TransferNotification.laneWord(phoneContext, phoneWire.peerDeviceId, true, "big.bin"))
        assertTrue("no failover: the only lane action announces the tcp lane", phone.sent.filter { it.action == WebRtcFileAction.LANE }.all { it.lane?.kind == FileLaneSpec.TCP })
        val announce = phone.sent.single { it.action == WebRtcFileAction.LANE }
        assertNull("a lane action carries no version", announce.lane?.v)
        assertEquals(0, announce.lane?.startBlock)
    }

    @Test
    fun aFailedLaneIsAskedForAgainByAReOfferAfterTenSecondsAndTheFileStillArrivesWhole() {
        phoneLanes.offer = true; deskLanes.issues = true
        phoneLanes.failFirstOnly = true
        phoneLanes.failAfterFrames = 20
        val bytes = data(24 * FileV2.BLOCK_BYTES + 7)
        val recorder = Recorder()
        val future = startSend(share(bytes, "big.bin"), recorder)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (phone.sent.none { it.action == WebRtcFileAction.LANE && it.lane?.kind == FileLaneSpec.WEBRTC } && System.nanoTime() < deadline) { if (pump(maxMessages = 1) == 0) Thread.sleep(2) }
        assertEquals("nothing re-offered yet", 1, phone.sent.count { it.action == WebRtcFileAction.OFFER })
        phone.tick() // too early: the first wait is ten seconds
        assertEquals(1, phone.sent.count { it.action == WebRtcFileAction.OFFER })
        phone.now += FileLaneProtocol.RETRY_MS + 1
        phone.tick()
        // Data is in flight on the data channel: the promotion waits for it to be acknowledged (it would be thrown away)...
        assertEquals("not while blocks are in flight", 1, phone.sent.count { it.action == WebRtcFileAction.OFFER })
        // ...but not for ever.
        phone.now += FileLaneProtocol.PROMOTE_DRAIN_MS + 1
        phone.tick()
        assertEquals("a re-offer asked for a lane again", 2, phone.sent.count { it.action == WebRtcFileAction.OFFER })
        val ok = runUntilDone(future)
        assertTrue("failed: ${recorder.failures} ${phone.sent.map { it.action.wireName + ":" + (it.message ?: "") }.takeLast(12)} desk=${desk.sent.map { it.action.wireName + ":" + (it.message ?: "") }.takeLast(8)}", ok)
        assertEquals("the second lane was dialled and carried the rest", 2, phoneLanes.dialed)
        assertEquals("the tcp lane was announced twice", 2, phone.sent.count { it.action == WebRtcFileAction.LANE && it.lane?.kind == FileLaneSpec.TCP })
        assertArrayEquals(bytes, desk.delivered.single().bytes)
    }

    @Test
    fun aTransferPausedForSpaceLetsItsLaneGoWithoutAFailureAndAsksForOneAgain() {
        phoneLanes.offer = true; deskLanes.issues = true
        val bytes = data(24 * FileV2.BLOCK_BYTES + 7)
        val recorder = Recorder()
        val future = startSend(share(bytes, "big.bin"), recorder)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (desk.sent.count { it.action == WebRtcFileAction.BLOCK_ACK } < 1 && System.nanoTime() < deadline) { if (pump(maxMessages = 1) == 0) Thread.sleep(2) }
        val offered = phone.sent.first { it.action == WebRtcFileAction.OFFER }
        phone.manager.handleControl(phoneWire, phone.control(WebRtcFileAction.PAUSE, offered.transferId, offered.transferToken) { copy(reason = FileCodes.STORAGE_FULL) })
        pump(maxMessages = 10)
        assertEquals("a pause is not a failed lane", 0, phoneLanes.failedCalls)
        assertEquals("the lane was let go", 1, webrtcSwitches(phone).size)
        assertTrue(phoneLanes.out!!.closed)
        phone.now += FileLaneProtocol.RETRY_MS + 1
        phone.tick()
        assertEquals("not asked for again while it is held", 1, phone.sent.count { it.action == WebRtcFileAction.OFFER })
        // The data channel is full, so nothing is sent after the resume: a promotion has nothing in flight to wait for.
        phone.bufferedAmount = 1L shl 40
        phone.manager.handleControl(phoneWire, phone.control(WebRtcFileAction.RESUME, offered.transferId, offered.transferToken))
        pump(maxMessages = 1)
        phone.tick()
        assertEquals("a lane is asked for again once it moves", 2, phone.sent.count { it.action == WebRtcFileAction.OFFER })
        assertEquals("with nothing in flight to throw away", 0L, phone.manager.senderForTest(offered.transferId)!!.promoteInFlight)
        phone.bufferedAmount = 0
        assertTrue("failed: ${recorder.failures}", runUntilDone(future))
        assertEquals(0, phoneLanes.failedCalls)
        assertEquals("the second lane was dialled and announced", 2, phone.sent.count { it.action == WebRtcFileAction.LANE && it.lane?.kind == FileLaneSpec.TCP })
        assertEquals(2, phoneLanes.dialed)
        assertArrayEquals(bytes, desk.delivered.single().bytes)
    }

    @Test
    fun aPauseWhileTheLaneIsStillBeingDialledAbandonsThatDialWithoutAFailure() {
        phoneLanes.offer = true; deskLanes.issues = true; phoneLanes.holdDial = true
        val bytes = data(3 * FileV2.BLOCK_BYTES + 17)
        val future = startSend(share(bytes, "big.bin"), Recorder())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (phoneLanes.heldDial == null && System.nanoTime() < deadline) { if (pump(maxMessages = 1) == 0) Thread.sleep(2) }
        val offered = phone.sent.first { it.action == WebRtcFileAction.OFFER }
        phone.manager.handleControl(phoneWire, phone.control(WebRtcFileAction.PAUSE, offered.transferId, offered.transferToken) { copy(reason = FileCodes.STORAGE_FULL) })
        pump(maxMessages = 3)
        phoneLanes.heldDial!!.invoke() // the connection arrives: nobody wants it any more
        pump(maxMessages = 3)
        assertTrue("it was closed, not used", phoneLanes.out!!.closed)
        assertTrue("and never announced", phone.sent.none { it.action == WebRtcFileAction.LANE && it.lane?.kind == FileLaneSpec.TCP })
        assertEquals(0, phoneLanes.failedCalls)
        phoneLanes.holdDial = false
        phone.manager.handleControl(phoneWire, phone.control(WebRtcFileAction.RESUME, offered.transferId, offered.transferToken))
        assertTrue(runUntilDone(future))
        assertArrayEquals(bytes, desk.delivered.single().bytes)
    }

    @Test
    fun aPauseWhenEveryBlockIsAcknowledgedClosesTheLaneAndDoesNotSayDoneAgain() {
        phoneLanes.offer = true; deskLanes.issues = true
        val bytes = data(3 * FileV2.BLOCK_BYTES + 17)
        val future = startSend(share(bytes, "big.bin"), Recorder())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (phone.sent.none { it.action == WebRtcFileAction.DONE } && System.nanoTime() < deadline) { if (pump(maxMessages = 1) == 0) Thread.sleep(2) }
        val offered = phone.sent.first { it.action == WebRtcFileAction.OFFER }
        phone.manager.handleControl(phoneWire, phone.control(WebRtcFileAction.PAUSE, offered.transferId, offered.transferToken) { copy(reason = FileCodes.STORAGE_FULL) })
        assertTrue(runUntilDone(future))
        assertEquals("done is said once", 1, phone.sent.count { it.action == WebRtcFileAction.DONE })
        assertTrue("and nothing was carried on over the data channel", webrtcSwitches(phone).isEmpty())
        assertArrayEquals(bytes, desk.delivered.single().bytes)
    }

    @Test
    fun aPromotionWaitingForAcknowledgementsNeverFreezesTheTransferWhenTheLaneIsNoLongerAllowed() {
        phoneLanes.offer = true; deskLanes.issues = true
        phoneLanes.failFirstOnly = true
        phoneLanes.failAfterFrames = 20
        val bytes = data(24 * FileV2.BLOCK_BYTES + 7)
        val future = startSend(share(bytes, "big.bin"), Recorder())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (webrtcSwitches(phone).isEmpty() && System.nanoTime() < deadline) { if (pump(maxMessages = 1) == 0) Thread.sleep(2) }
        phone.now += FileLaneProtocol.RETRY_MS + 1
        phone.tick() // the wait for acknowledgements begins; no new block starts
        phoneLanes.coolingNow = true // and the lane is no longer allowed (the peer is backing off)
        phone.now += 1
        phone.tick()
        assertTrue("the transfer goes on over the data channel", runUntilDone(future))
        assertEquals("no lane was asked for", 1, phone.sent.count { it.action == WebRtcFileAction.OFFER })
        assertArrayEquals(bytes, desk.delivered.single().bytes)
    }

    @Test
    fun aReOfferWithoutALaneGetsNoTokenInItsAccept() {
        deskLanes.issues = true
        val id = java.util.UUID.randomUUID().toString()
        fun offer(lane: FileLaneSpec?) = desk.control(WebRtcFileAction.OFFER, id, "t".repeat(32)) {
            copy(filename = "big.bin", totalSize = 9L * 1024 * 1024, mtime = 1L, blockSize = FileV2.BLOCK_BYTES, maxFrameBytes = FileV2.FRAME_BYTES_PROVEN, lane = lane)
        }
        desk.manager.handleControl(deskWire, offer(FileLaneSpec(v = FileLaneProtocol.V)))
        while (desk.runner.runPending()) Unit
        desk.manager.handleControl(deskWire, offer(null))
        while (desk.runner.runPending()) Unit
        val accepts = desk.sent.filter { it.action == WebRtcFileAction.ACCEPT }
        assertEquals(2, accepts.size)
        assertNotNull("the first offer asked for a lane", accepts[0].lane?.token)
        assertNull("this offer did not: no token is issued", accepts[1].lane)
    }

    @Test
    fun aLaneActionPastTheVerifiedPrefixIsIgnoredNotFatal() {
        phoneLanes.offer = true; deskLanes.issues = true
        val bytes = data(3 * FileV2.BLOCK_BYTES + 1)
        val future = startSend(share(bytes, "big.bin"), Recorder())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (phone.sent.none { it.action == WebRtcFileAction.OFFER } && System.nanoTime() < deadline) pump(maxMessages = 1)
        val offered = phone.sent.first { it.action == WebRtcFileAction.OFFER }
        pump(maxMessages = 3)
        desk.manager.handleControl(deskWire, desk.control(WebRtcFileAction.LANE, offered.transferId, offered.transferToken) { copy(lane = FileLaneSpec(kind = FileLaneSpec.WEBRTC, startBlock = 50, pass = 3)) })
        assertTrue(runUntilDone(future))
        assertArrayEquals(bytes, desk.delivered.single().bytes)
        assertTrue(desk.sent.none { it.action == WebRtcFileAction.ERROR })
    }

    @Test
    fun withoutALaneInTheAcceptOrOnAFailedDialTheDataChannelCarriesTheFile() {
        phoneLanes.offer = true; deskLanes.issues = false
        val a = data(2 * FileV2.BLOCK_BYTES + 5)
        assertTrue(runUntilDone(startSend(share(a, "a.bin"), Recorder())))
        assertEquals(0, phoneLanes.dialed)
        assertTrue(phone.frameCount > 0)
        assertArrayEquals(a, desk.delivered.single().bytes)
        // Accepted but the dial fails (blocked, firewalled): silently the data channel.
        deskLanes.issues = true; phoneLanes.dialOk = false
        val before = phone.frameCount
        val b = data(2 * FileV2.BLOCK_BYTES + 9)
        assertTrue(runUntilDone(startSend(share(b, "b.bin"), Recorder())))
        assertEquals(1, phoneLanes.dialed)
        assertTrue(phone.frameCount > before)
        assertArrayEquals(b, desk.delivered.last().bytes)
        assertTrue("a failed dial changes no lane", phone.sent.none { it.action == WebRtcFileAction.LANE })
    }

    @Test
    fun aLaneThatBreaksMidFileFailsOverToTheDataChannelFromTheFirstUnacknowledgedBlockAndTheFileIsWhole() {
        phoneLanes.offer = true; deskLanes.issues = true
        phoneLanes.failAfterFrames = 20 // about a block and a quarter
        val bytes = data(5 * FileV2.BLOCK_BYTES + 321)
        val recorder = Recorder()
        assertTrue(runUntilDone(startSend(share(bytes, "big.bin"), recorder)))
        val lane = phone.sent.single { it.action == WebRtcFileAction.LANE && it.lane?.kind == FileLaneSpec.WEBRTC }
        assertEquals(FileLaneSpec.WEBRTC, lane.lane?.kind)
        assertEquals("the next pass", 1, lane.lane?.pass)
        assertTrue("never from 0 once blocks were verified: ${lane.lane?.startBlock}", (lane.lane?.startBlock ?: -1) >= 0)
        assertTrue(phone.frameCount > 0)
        assertArrayEquals(bytes, desk.delivered.single().bytes)
        assertEquals(1, recorder.successes.get())
    }

    private fun laneNacks(side: Side) = side.sent.filter { it.action == WebRtcFileAction.BLOCK_NACK && it.reason == FileLaneProtocol.NACK_REASON }
    private fun webrtcSwitches(side: Side) = side.sent.filter { it.action == WebRtcFileAction.LANE && it.lane?.kind == FileLaneSpec.WEBRTC }

    @Test
    fun theReceiverSeeingTheLaneCloseAsksForTheRestAndTheSenderLeavesOnceWithoutCountingARewind() {
        phoneLanes.offer = true; deskLanes.issues = true
        val bytes = data(24 * FileV2.BLOCK_BYTES)
        val future = startSend(share(bytes, "big.bin"), Recorder())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while ((phoneLanes.out?.frames ?: 0) < 40 && System.nanoTime() < deadline) { if (pump(maxMessages = 3) == 0) Thread.sleep(2) }
        val id = phone.sent.first { it.action == WebRtcFileAction.OFFER }.transferId
        assertTrue(webrtcSwitches(phone).isEmpty())
        // The lane ends under the receiver (its reader hits the end): the receiver's own code sends the word, nobody injects it.
        desk.manager.onLaneEnded(id, phoneLanes.out!!.connection)
        assertTrue(runUntilDone(future))
        val switch = webrtcSwitches(phone).single() // the sender left the lane once
        val nacks = laneNacks(desk)
        assertEquals("the receiver's own word, then its answer to the switch", 2, nacks.size)
        assertEquals("the word names the pass the lane was on", (switch.lane!!.pass!! - 1) and 0xff, nacks[0].pass)
        assertEquals("the answer names the new pass", switch.lane!!.pass, nacks[1].pass)
        assertTrue("no damaged block was claimed", desk.sent.none { it.action == WebRtcFileAction.BLOCK_NACK && it.reason == FileCodes.HASH_MISMATCH })
        assertTrue(phoneLanes.out!!.closed)
        assertTrue(phone.frameCount > 0)
        assertArrayEquals(bytes, desk.delivered.single().bytes)
    }

    @Test
    fun aFailoverTheSenderMakesItselfIsNotRepeatedWhenTheReceiversAnswerComesBack() {
        phoneLanes.offer = true; deskLanes.issues = true
        phoneLanes.failAfterFrames = 20
        val bytes = data(5 * FileV2.BLOCK_BYTES + 321)
        assertTrue(runUntilDone(startSend(share(bytes, "big.bin"), Recorder())))
        assertEquals("one failover, however many words come back", 1, webrtcSwitches(phone).size)
        assertEquals("the receiver answered the switch with where it stands", 1, laneNacks(desk).count { it.pass == webrtcSwitches(phone).single().lane?.pass })
        assertArrayEquals(bytes, desk.delivered.single().bytes)
    }

    @Test
    fun theLateEndOfAnOlderLaneNeitherKillsThePromotedLaneNorCountsAsAFailure() {
        phoneLanes.offer = true; deskLanes.issues = true
        phoneLanes.failFirstOnly = true
        phoneLanes.failAfterFrames = 20
        val bytes = data(24 * FileV2.BLOCK_BYTES + 7)
        val future = startSend(share(bytes, "big.bin"), Recorder())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (webrtcSwitches(phone).isEmpty() && System.nanoTime() < deadline) { if (pump(maxMessages = 1) == 0) Thread.sleep(2) }
        phone.now += FileLaneProtocol.RETRY_MS + 1
        phone.tick()
        phone.now += FileLaneProtocol.PROMOTE_DRAIN_MS + 1 // blocks in flight are given up to this long to be acknowledged
        phone.tick()
        while (phoneLanes.dialed < 2 && System.nanoTime() < deadline) { if (pump(maxMessages = 2) == 0) Thread.sleep(2) }
        val id = phone.sent.first { it.action == WebRtcFileAction.OFFER }.transferId
        val older = phoneLanes.outs.first()
        val newer = phoneLanes.outs.last()
        val switches = webrtcSwitches(phone).size
        val failures = phoneLanes.failedCalls
        // The first lane's reader gives up late (30 s of silence on a half-open socket): the receiver is on the second lane now.
        desk.manager.onLaneEnded(id, older.connection)
        pump(maxMessages = 10)
        assertFalse("the promoted lane is alive", newer.closed)
        assertEquals(switches, webrtcSwitches(phone).size)
        assertEquals(failures, phoneLanes.failedCalls)
        assertTrue(runUntilDone(future))
        assertArrayEquals(bytes, desk.delivered.single().bytes)
    }

    @Test
    fun aBlockDamagedTwiceOnTheLaneSendsTheSenderToTheDataChannelWithoutFailingTheFile() {
        phoneLanes.offer = true; deskLanes.issues = true
        phoneLanes.corruptBlock = 1; phoneLanes.corruptTimes = 2
        val bytes = data(4 * FileV2.BLOCK_BYTES + 5)
        val recorder = Recorder()
        val ok = runUntilDone(startSend(share(bytes, "big.bin"), recorder))
        assertTrue("failed: ${recorder.failures} phone=${phone.sent.map { it.action.wireName + ":" + (it.reason ?: it.message ?: "") }.takeLast(14)} desk=${desk.sent.map { it.action.wireName + ":" + (it.reason ?: it.message ?: "") }.takeLast(10)}", ok)
        assertEquals("the first damage is the usual rewind", 1, desk.sent.count { it.action == WebRtcFileAction.BLOCK_NACK && it.reason == FileCodes.HASH_MISMATCH })
        assertEquals("the second damage leaves the lane (and the switch is answered)", 2, laneNacks(desk).size)
        assertEquals(1, webrtcSwitches(phone).size)
        assertTrue(phone.frameCount > 0)
        assertArrayEquals(bytes, desk.delivered.single().bytes)
    }

    @Test
    fun aTransferStopsAskingForALaneAfterThreeFailuresAndThePeerIsToldEachTime() {
        phoneLanes.offer = true; deskLanes.issues = true
        phoneLanes.failAfterFrames = 20 // every lane breaks
        val bytes = data(30 * FileV2.BLOCK_BYTES + 1)
        val future = startSend(share(bytes, "big.bin"), Recorder())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        var seen = 0
        while (!future.isDone && System.nanoTime() < deadline) {
            if (pump(maxMessages = 1) == 0) Thread.sleep(2)
            val switches = webrtcSwitches(phone).size
            if (switches > seen) {
                seen = switches
                phone.now += 400_000; phone.tick() // far past every back-off
                phone.now += FileLaneProtocol.PROMOTE_DRAIN_MS + 1; phone.tick() // and past the wait for blocks in flight
            }
        }
        assertTrue(future.get(5, TimeUnit.SECONDS))
        assertEquals("three lanes, then the data channel for good", 3, phoneLanes.dialed)
        assertEquals(3, phone.sent.count { it.action == WebRtcFileAction.OFFER && it.lane != null })
        assertEquals(3, phoneLanes.failedCalls)
        assertArrayEquals(bytes, desk.delivered.single().bytes)
    }

    @Test
    fun theLaneIsNotOfferedWhileThePeerIsBackingOffAndAFailureFreeLaneTransferClearsIt() {
        phoneLanes.offer = true; deskLanes.issues = true
        phoneLanes.coolingNow = true
        val a = data(2 * FileV2.BLOCK_BYTES + 5)
        assertTrue(runUntilDone(startSend(share(a, "a.bin"), Recorder())))
        assertEquals("no lane offered while cooling", 0, phone.sent.count { it.action == WebRtcFileAction.OFFER && it.lane != null })
        phoneLanes.coolingNow = false
        val b = data(2 * FileV2.BLOCK_BYTES + 9)
        assertTrue(runUntilDone(startSend(share(b, "b.bin"), Recorder())))
        assertEquals("a lane carried it to the end", 1, phoneLanes.cleanCalls)
        assertEquals(0, phoneLanes.failedCalls)
    }

    @Test
    fun aReceiverThatPausesDoesNotMakeTheSenderThinkItsHealthyLaneStalled() {
        phoneLanes.offer = true; deskLanes.issues = true
        phoneLanes.swallow = true // nothing is acknowledged
        val bytes = data(6 * FileV2.BLOCK_BYTES)
        val future = startSend(share(bytes, "big.bin"), Recorder())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while ((phoneLanes.out?.frames ?: 0) < 5 && System.nanoTime() < deadline) { if (pump(maxMessages = 2) == 0) Thread.sleep(2) }
        val offered = phone.sent.first { it.action == WebRtcFileAction.OFFER }
        // A backlog is a short hold: the lane stays (a long one, no space, lets it go: see aTransferPausedForSpace...).
        phone.manager.handleControl(phoneWire, phone.control(WebRtcFileAction.PAUSE, offered.transferId, offered.transferToken) { copy(reason = FileCodes.BACKLOG) })
        pump(maxMessages = 5)
        phone.now += FileLaneProtocol.STALL_MS * 3
        phone.tick()
        pump(maxMessages = 5)
        assertTrue("a paused receiver is not a stalled lane", webrtcSwitches(phone).isEmpty())
        assertFalse(phoneLanes.out!!.closed)
        // Resumed and still nothing acknowledged for 5 s: now it is a stall.
        phone.manager.handleControl(phoneWire, phone.control(WebRtcFileAction.RESUME, offered.transferId, offered.transferToken))
        pump(maxMessages = 5)
        phone.now += FileLaneProtocol.STALL_MS + 1
        phone.tick()
        pump(maxMessages = 5)
        assertEquals(1, webrtcSwitches(phone).size)
        phoneLanes.swallow = false
        assertTrue(runUntilDone(future))
    }

    @Test
    fun cancelOnTheNotificationInterruptsALiveSendAndThatEndsTheTransferToo() {
        val bytes = data(3 * FileV2.BLOCK_BYTES)
        val recorder = Recorder()
        val packet = share(bytes, "live.bin")
        phone.bufferedAmount = Long.MAX_VALUE / 2 // nothing leaves: the send is live and waiting
        val future = startSend(packet, recorder)
        val started = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (phone.sent.none { it.action == WebRtcFileAction.OFFER } && System.nanoTime() < started) { if (pump(maxMessages = 2) == 0) Thread.sleep(5) }
        // What Cancel does to a running job: its thread is interrupted before the packet is marked.
        future.cancel(true)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (phone.sent.none { it.action == WebRtcFileAction.CANCEL } && System.nanoTime() < deadline) { if (pump(maxMessages = 2) == 0) Thread.sleep(5) }
        assertTrue("the computer is told to drop the file: ${phone.sent.map { it.action.wireName }}", phone.sent.any { it.action == WebRtcFileAction.CANCEL })
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
    fun storedSendsStartInOrderTwoAtATimeAndTheThirdWaitsForASlot() {
        val store = FileTransferStore(phone.root)
        val ids = List(3) { UUID.randomUUID().toString() }.sorted()
        ids.forEach { id ->
            val file = store.spool(id)
            file.writeBytes(data(FileV2.BLOCK_BYTES + 10))
            assertTrue(store.save(FileCheckpoint(id, "send", "t".repeat(32), "desk", "$id.bin", file.length(), file.lastModified(), 0, spooled = true)))
        }
        phone.manager.resumeSends(phoneWire)
        pump(maxMessages = 0)
        assertEquals("two offers, never three at once", ids.take(2), phone.sent.filter { it.action == WebRtcFileAction.OFFER }.map { it.transferId })
        pump()
        assertEquals(3, desk.delivered.size)
        assertEquals("none was told busy", 0, desk.sent.count { it.action == WebRtcFileAction.ERROR })
        assertTrue(phone.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun anUnknownCancelCodeEndsAReceiveLikeAnyOtherAndFreesItsSlotAndItsPartialFile() {
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val c = UUID.randomUUID().toString()
        desk.manager.handleControl(deskWire, offer(id = a, size = FileV2.BLOCK_BYTES * 2L))
        desk.manager.handleControl(deskWire, offer(id = b, size = FileV2.BLOCK_BYTES * 2L))
        pump(maxMessages = 0)
        desk.manager.handleControl(deskWire, desk.control(WebRtcFileAction.CANCEL, a) { copy(code = "from-a-newer-build") })
        pump(maxMessages = 0)
        assertEquals("its partial file and checkpoint are gone", null, FileTransferStore(desk.root).load(a))
        desk.manager.handleControl(deskWire, offer(id = c, size = FileV2.BLOCK_BYTES * 2L))
        pump(maxMessages = 0)
        assertEquals("the freed slot takes a new file", 3, desk.sent.count { it.action == WebRtcFileAction.ACCEPT })
        assertEquals(0, desk.sent.count { it.action == WebRtcFileAction.ERROR })
    }

    @Test
    fun aTickThatThrowsDoesNotEndTheClock() {
        desk.manager.handleControl(deskWire, offer(size = FileV2.BLOCK_BYTES * 2L))
        pump(maxMessages = 0)
        val step = checkNotNull(desk.runner.tickStep) { "a live transfer arms the clock" }
        var calls = 0
        desk.manager.tickHook = { calls++; if (calls == 1) error("boom") }
        assertTrue("the loop goes on after a failed tick", step())
        assertTrue(step())
        assertEquals(2, calls)
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
        // The sender may not have got the first accept: a re-offer of a live id is answered again, from where we are.
        assertEquals(2, desk.sent.count { it.action == WebRtcFileAction.ACCEPT })
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
        val byName = desk.delivered.associateBy { it.name }
        assertArrayEquals(a, deliveredBytes(byName.getValue("a.bin")))
        assertArrayEquals(b, deliveredBytes(byName.getValue("b.bin")))
    }
}
