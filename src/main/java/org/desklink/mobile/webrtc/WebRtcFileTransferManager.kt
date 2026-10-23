/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.plugins.folders.BucketEngine
import org.desklink.mobile.plugins.folders.FolderRef
import org.desklink.mobile.plugins.share.TransferHistory
import org.desklink.mobile.plugins.share.TransferProgress
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Where file work runs: one dedicated thread per manager, so nothing on libwebrtc's callback thread touches a file. */
interface FileRunner {
    fun post(task: Runnable)
    fun postDelayed(delayMs: Long, task: Runnable)
    fun every(periodMs: Long, task: Runnable)

    /** Runs [step] every [periodMs] for as long as it returns true, then stops (nothing ticks while idle). */
    fun tickWhile(periodMs: Long, step: () -> Boolean) = Unit
}

class ThreadFileRunner : FileRunner {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "DeskLink-file").apply { isDaemon = true }
    }

    override fun post(task: Runnable) {
        runCatching { executor.execute(task) }
    }

    override fun postDelayed(delayMs: Long, task: Runnable) {
        runCatching { executor.schedule(task, delayMs, TimeUnit.MILLISECONDS) }
    }

    override fun every(periodMs: Long, task: Runnable) {
        runCatching { executor.scheduleWithFixedDelay(task, periodMs, periodMs, TimeUnit.MILLISECONDS) }
    }

    override fun tickWhile(periodMs: Long, step: () -> Boolean) {
        val loop = object : Runnable {
            override fun run() {
                if (runCatching(step).getOrDefault(false)) runCatching { executor.schedule(this, periodMs, TimeUnit.MILLISECONDS) }
            }
        }
        runCatching { executor.schedule(loop, periodMs, TimeUnit.MILLISECONDS) }
    }

    fun shutdown() = executor.shutdownNow()
}

/**
 * Transfers v2 (W3): 1 MiB blocks, per-block SHA-256, the file read in place (no copy), an
 * adaptive window, checkpoints, and resume from the last verified block. This class only routes:
 * [FileSender] and [FileReceiver] hold each transfer's state, all on [FileRunner]'s thread.
 *
 * Every file this class touches lives at a path derived from a *validated* transfer UUID in the
 * private transfer root. Neither the peer's filename nor a URI read back from a checkpoint ever
 * selects where bytes are read, written or deleted.
 */
@Suppress("UsableSpace") // a plain free-space check on the private transfer root: the quota APIs do not apply to it
class WebRtcFileTransferManager(
    context: Context,
    private val device: Device,
    private val link: FileLink,
    private val runner: FileRunner = ThreadFileRunner(),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val freeSpace: () -> Long = { File(context.applicationContext.filesDir.path).usableSpace },
) {
    private val appContext = context.applicationContext
    private val store = FileTransferStore(File(appContext.filesDir, "webrtc-transfers"))

    private class SendEntry(
        var sender: FileSender,
        val callback: Device.SendPacketStatusCallback,
        val completion: CompletableFuture<Boolean>,
        val packet: NetworkPacket?,
        var restarts: Int = 0,
    )

    // File thread only.
    private val sends = HashMap<String, SendEntry>()
    private val receives = HashMap<String, FileReceiver>()
    private var pausedForBacklog = false
    private val folderOffers = HashMap<String, FolderRef>()
    private val clipboardOffers = HashSet<String>()

    private val queuedBytes = AtomicLong()
    private val backlogged = AtomicBoolean(false)
    private val pumpPending = AtomicBoolean(false)
    @Volatile private var currentWire: WebRtcWireBinding? = null

    init {
        store.purgeLegacy()
    }

    /** The clock (stalls, checkpoints) runs only while a transfer is live: an idle phone has no timer here. */
    private var ticking = false

    private fun armTick() {
        if (ticking) return
        ticking = true
        runner.tickWhile(TICK_MS) {
            // A tick that throws must not end the clock for good (the 60 s pause and the checkpoints depend on it).
            runCatching { tick() }.onFailure { Log.w(TAG, "a transfer tick failed", it) }
            (sends.isNotEmpty() || receives.isNotEmpty()).also { if (!it) ticking = false }
        }
    }

    // ------------------------------------------------------------------ send

    /**
     * Blocks until the peer has the file, the person cancels, or it fails; every failure is reported
     * through [callback] exactly once. There is no deadline: a stalled transfer pauses and goes on.
     */
    fun sendPayload(wire: WebRtcWireBinding, packet: NetworkPacket, callback: Device.SendPacketStatusCallback): Boolean {
        val payload = packet.payload ?: run {
            callback.onFailure(IllegalArgumentException("File packet has no payload"))
            return false
        }
        val filename = try {
            packet.getString("filename").also(::validateFilename)
        } catch (error: Throwable) {
            payload.close()
            callback.onFailure(error)
            return false
        }
        val id = UUID.randomUUID().toString()
        val source: FileSource
        val spooled: Boolean
        try {
            val given = payload.source
            spooled = given == null
            source = given ?: spool(id, payload)
        } catch (error: Throwable) {
            store.remove(id)
            payload.close()
            callback.onFailure(error)
            return false
        }
        val stat = source.stat()
        if (stat == null || stat.size > FileV2.MAX_TRANSFER_BYTES) {
            if (spooled) store.remove(id)
            payload.close()
            callback.onFailure(IllegalArgumentException(if (stat == null) "The file no longer exists." else "File exceeds the DeskLink transfer limit"))
            return false
        }
        payload.close() // the stream is not used: the source is read in place
        val meta = buildMap {
            if (packet.getBoolean("clipboard", false)) {
                put("clipboard", "true")
                packet.getStringOrNull("mimeType")?.let { put("mimeType", it) }
            }
            packet.getStringOrNull("captureId")?.let {
                put("captureId", it)
                packet.getStringOrNull("mimeType")?.let { m -> put("mimeType", m) }
            }
            packet.getStringOrNull("folder")?.let { put("folder", it) }
            packet.getStringOrNull("relpath")?.let { put("relpath", it) }
            packet.getStringOrNull("contentSha256")?.let { put("contentSha256", it) }
            // The time the offer advertises (a Bucket entry's, a share's lastModified); the source's own is only for change detection.
            if (packet.has("mtime")) put("mtime", packet.getLong("mtime", 0).toString())
            else if (packet.has("lastModified")) put("mtime", packet.getLong("lastModified", 0).toString())
        }
        val completion = CompletableFuture<Boolean>()
        runner.post {
            val sender = newSender(wire, id, randomToken(), source, filename, stat.size, stat.mtime, meta, spooled, resumedHashes = emptyList())
            val entry = SendEntry(sender, callback, completion, packet)
            sends[id] = entry
            armTick()
            TransferHistory.peekSource(packet)?.let { TransferHistory.rememberResumable(appContext, id, it) }
            startSender(entry)
        }
        while (true) {
            if (packet.isCanceled) {
                runner.post { sends.values.firstOrNull { it.packet === packet }?.sender?.cancel(FileCodes.USER, "Cancelled.") }
            }
            try {
                return completion.get(250, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                // Check cancellation again. There is deliberately no deadline.
            }
        }
    }

    private fun spool(id: String, payload: NetworkPacket.Payload): FileSource {
        val input = requireNotNull(payload.inputStream) { "File payload stream is unavailable" }
        val file = store.spool(id)
        var size = 0L
        java.io.FileOutputStream(file).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                size += n
                require(size <= FileV2.MAX_TRANSFER_BYTES) { "File exceeds the DeskLink transfer limit" }
                output.write(buffer, 0, n)
            }
            output.fd.sync()
        }
        return PlainFileSource(file)
    }

    private fun newSender(
        wire: WebRtcWireBinding, id: String, token: String, source: FileSource, filename: String, size: Long,
        mtime: Long, meta: Map<String, String>, spooled: Boolean, resumedHashes: List<ByteArray>,
    ) = FileSender(wire, store, link, clock, senderListener, id, token, source, filename, size, mtime, meta, spooled).also {
        it.restore(resumedHashes)
        currentWire = wire
    }

    private fun startSender(entry: SendEntry) {
        try {
            entry.sender.start()
        } catch (error: Throwable) {
            if (linkClosed(error)) entry.sender.suspend() else {
                entry.sender.cancel(FileCodes.USER, "The file could not be sent.")
                throw error
            }
        }
    }

    private val senderListener: FileSender.Listener = object : FileSender.Listener {
        override fun progress(sender: FileSender, bytesDone: Long) {
            val entry = sends[sender.transferId] ?: return
            val percent = if (sender.totalSize == 0L) 100 else (bytesDone * 100 / sender.totalSize).toInt().coerceIn(0, 100)
            entry.callback.onPayloadProgressChanged(percent)
        }

        override fun ended(sender: FileSender, outcome: FileSender.Outcome, message: String?) {
            val id = sender.transferId
            val entry = sends[id] ?: return
            if (entry.sender !== sender) return
            sends.remove(id)
            if (outcome != FileSender.Outcome.SUSPENDED && outcome != FileSender.Outcome.BUSY) {
                // A slot is free: the next stored send, if any, starts (after this one's own bookkeeping below).
                runner.post { currentWire?.let(::startStoredSends) }
            }
            when (outcome) {
                FileSender.Outcome.COMPLETED -> {
                    cleanupSend(id, sender)
                    entry.callback.onPayloadProgressChanged(100)
                    entry.callback.onSuccess()
                    entry.completion.complete(true)
                }
                FileSender.Outcome.SUSPENDED -> {
                    // The link went away: the checkpoint stays, the owner shows "resuming", resumeSends continues it.
                    entry.callback.onFailure(TransferResumingException(message ?: "The connection closed"))
                    entry.completion.complete(false)
                }
                FileSender.Outcome.BUSY -> {
                    // The receiver is full: stay queued, offer again shortly. No deadline.
                    sends[id] = entry
                    armTick()
                    runner.postDelayed(FileV2.BUSY_RETRY_MS) {
                        if (sends[id] === entry) {
                            entry.sender = cloneSender(entry.sender)
                            startSender(entry)
                        }
                    }
                }
                FileSender.Outcome.SOURCE_CHANGED -> {
                    cleanupSend(id, sender, keepSource = true)
                    if (entry.restarts >= FileV2.MAX_RESTARTS) {
                        cleanupSource(sender)
                        entry.callback.onFailure(IllegalStateException("The file keeps changing, so it was not sent."))
                        entry.completion.complete(false)
                        return
                    }
                    val source = sender.sourceForRestart()
                    val stat = source.stat()
                    if (stat == null) {
                        cleanupSource(sender)
                        entry.callback.onFailure(IllegalStateException("The file no longer exists."))
                        entry.completion.complete(false)
                        return
                    }
                    entry.restarts++
                    val fresh = newSender(sender.wire, UUID.randomUUID().toString(), randomToken(), source, sender.filename, stat.size, stat.mtime, sender.metaForRestart(), sender.isSpooled(), emptyList())
                    entry.sender = fresh
                    sends[fresh.transferId] = entry
                    armTick()
                    startSender(entry)
                }
                FileSender.Outcome.FAILED, FileSender.Outcome.CANCELLED -> {
                    cleanupSend(id, sender)
                    entry.callback.onFailure(IllegalStateException(message ?: "The file could not be sent."))
                    entry.completion.complete(false)
                }
            }
        }
    }

    /** The same transfer offered again (a busy receiver): the hashes already read go with it, so nothing stored is lost. */
    private fun cloneSender(old: FileSender) = newSender(
        old.wire, old.transferId, old.token, old.sourceForRestart(), old.filename, old.totalSize, old.mtime, old.metaForRestart(), old.isSpooled(), old.hashesSnapshot(),
    )

    private fun cleanupSend(id: String, sender: FileSender, keepSource: Boolean = false) {
        store.remove(id).also { if (keepSource && sender.isSpooled()) Unit }
        TransferHistory.forgetResumable(appContext, id)
        if (store.list().none { it.direction == "send" && it.deviceId == device.deviceId }) TransferProgress.dropResuming(device.deviceId)
    }

    private fun cleanupSource(sender: FileSender) {
        if (sender.isSpooled()) store.spool(sender.transferId).delete()
    }

    // --------------------------------------------------------------- inbound

    fun handleControl(wire: WebRtcWireBinding, message: WebRtcFileControl) {
        runner.post {
            try {
                message.validate(wire)
                currentWire = wire
                route(wire, message)
            } catch (error: Throwable) {
                Log.w(TAG, "Rejected a file control message: ${error.message}")
                if (message.action == WebRtcFileAction.OFFER && !sends.containsKey(message.transferId) && !receives.containsKey(message.transferId)) {
                    runCatching {
                        link.sendControl(
                            reply(wire, message.transferId, message.transferToken, WebRtcFileAction.ERROR).copy(
                                code = FileCodes.REFUSED, message = peerReason(error, "The phone could not accept the file"),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun route(wire: WebRtcWireBinding, message: WebRtcFileControl) {
        val id = message.transferId
        when (message.action) {
            WebRtcFileAction.OFFER -> receiveOffer(wire, message)
            WebRtcFileAction.ACCEPT, WebRtcFileAction.BLOCK_ACK, WebRtcFileAction.BLOCK_NACK, WebRtcFileAction.COMPLETE -> {
                sendEntry(wire, message)?.sender?.onControl(message)
            }
            WebRtcFileAction.DONE -> receiveEntry(wire, message)?.onDone(message)
            WebRtcFileAction.PAUSE, WebRtcFileAction.RESUME -> {
                val receiver = receives[id]?.takeIf { it.wire == wire && it.offer.transferToken == message.transferToken }
                if (receiver != null) {
                    if (message.action == WebRtcFileAction.PAUSE) receiver.onPeerPause(message.reason) else receiver.onPeerResume()
                } else {
                    sendEntry(wire, message)?.sender?.onControl(message)
                }
            }
            WebRtcFileAction.CANCEL, WebRtcFileAction.ERROR -> {
                val receiver = receives[id]?.takeIf { it.wire == wire && it.offer.transferToken == message.transferToken }
                when {
                    receiver != null -> receiver.onPeerEnded(message.action == WebRtcFileAction.CANCEL, message.message)
                    sends.containsKey(id) -> sendEntry(wire, message)?.sender?.onControl(message)
                    else -> {
                        // Not live, but a checkpoint from an earlier connection may be waiting: the peer's word ends it.
                        val stored = store.load(id) ?: return
                        if (stored.deviceId == wire.peerDeviceId && stored.token == message.transferToken) store.remove(id)
                    }
                }
            }
        }
    }

    private fun sendEntry(wire: WebRtcWireBinding, message: WebRtcFileControl): SendEntry? {
        val entry = sends[message.transferId] ?: return null
        require(entry.sender.wire == wire) { "WebRTC transfer response has a stale binding" }
        require(entry.sender.token == message.transferToken) { "WebRTC transfer response has the wrong token" }
        return entry
    }

    private fun receiveEntry(wire: WebRtcWireBinding, message: WebRtcFileControl): FileReceiver? {
        val receiver = receives[message.transferId] ?: return null
        require(receiver.wire == wire) { "WebRTC transfer message has a stale binding" }
        require(receiver.offer.transferToken == message.transferToken) { "WebRTC transfer message has the wrong token" }
        return receiver
    }

    private fun receiveOffer(wire: WebRtcWireBinding, message: WebRtcFileControl) {
        val id = message.transferId
        val stored = store.load(id)
        require(stored?.direction != "send" && !sends.containsKey(id)) { "Incoming file offer collides with an outgoing transfer" }
        val totalSize = requireNotNull(message.totalSize)
        // A synced file is taken only when this phone asked for it, else refused: before anything else, as the desk does,
        // so an unrequested file is never answered "busy" (which would make its sender offer it again).
        var ref: FolderRef? = null
        if (message.folder != null) {
            ref = FolderRef(message.folder, requireNotNull(message.relpath), message.mtime)
            if (!BucketEngine.accepts(wire.peerDeviceId, ref, requireNotNull(message.contentSha256), totalSize)) {
                link.sendControl(reply(wire, id, message.transferToken, WebRtcFileAction.ERROR).copy(code = FileCodes.REFUSED, message = "folder-refused"))
                return // answered once: nothing was requested, nothing is stored
            }
        }
        val existing = receives[id]
        if (existing != null) {
            if (existing.offer.transferToken != message.transferToken || existing.totalSize != totalSize || existing.filename != message.filename) {
                link.sendControl(reply(wire, id, message.transferToken, WebRtcFileAction.ERROR).copy(code = FileCodes.REFUSED, message = "That transfer does not match"))
                return
            }
            existing.onReoffer() // the sender did not get our accept: answered again from where we are
            return
        }
        if (receives.size >= FileV2.ACTIVE_PER_DIRECTION) {
            link.sendControl(reply(wire, id, message.transferToken, WebRtcFileAction.ERROR).copy(code = FileCodes.BUSY, message = "The phone is receiving other files."))
            return
        }
        val receiver = FileReceiver(wire, store, link, clock, freeSpace, receiverListener, message)
        if (ref != null) folderOffers[id] = ref
        if (message.clipboard == true && message.mimeType == org.desklink.mobile.plugins.clipboard.ClipboardImages.MIME &&
            totalSize <= org.desklink.mobile.plugins.clipboard.ClipboardImages.MAX_BYTES
        ) clipboardOffers.add(id)
        receives[id] = receiver
        armTick()
        try {
            receiver.start(stored)
        } catch (error: Throwable) {
            receives.remove(id)
            folderOffers.remove(id)
            clipboardOffers.remove(id)
            store.remove(id)
            if (linkClosed(error)) return
            throw error
        }
    }

    private val receiverListener: FileReceiver.Listener = object : FileReceiver.Listener {
        override fun progress(receiver: FileReceiver, bytesDone: Long) {
            val ref = folderOffers[receiver.id]
            if (ref != null) {
                BucketEngine.progress(receiver.wire.peerDeviceId, ref.relpath, bytesDone, receiver.totalSize, sending = false)
            } else {
                // A received share shows on Home and keeps the phone awake while it moves.
                val percent = if (receiver.totalSize == 0L) 100 else (bytesDone * 100 / receiver.totalSize).toInt().coerceIn(0, 100)
                TransferProgress.update(receiver.wire.peerDeviceId, receiveJob(receiver.id), false, receiver.filename, percent, receiver.totalSize)
            }
        }

        override fun deliver(receiver: FileReceiver, partial: File, fileHash: String) {
            val offer = receiver.offer
            folderOffers.remove(receiver.id)?.let { ref ->
                // F8: placed into the synced folder (the engine takes the file), never a share.
                BucketEngine.arrived(receiver.wire.peerDeviceId, ref, partial, requireNotNull(offer.contentSha256), receiver.totalSize)
                return
            }
            val packet = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
                this["filename"] = receiver.filename
                this["sha256"] = fileHash
                offer.mtime?.takeIf { it > 0 }?.let { this["lastModified"] = it }
                if (clipboardOffers.remove(receiver.id)) {
                    this["clipboard"] = true
                    this["mimeType"] = org.desklink.mobile.plugins.clipboard.ClipboardImages.MIME
                }
                payload = NetworkPacket.Payload(java.io.FileInputStream(partial), receiver.totalSize) { partial.delete() }
            }
            try {
                device.onWebRtcPacketReceived(packet)
            } catch (error: Throwable) {
                packet.payload?.close() // deletes the partial file
                throw error
            }
        }

        override fun ended(receiver: FileReceiver, outcome: FileReceiver.Outcome, message: String?) {
            if (!folderOffers.containsKey(receiver.id)) {
                val job = receiveJob(receiver.id)
                when (outcome) {
                    FileReceiver.Outcome.FAILED -> TransferProgress.finish(job, receiver.filename, true)
                    // Delivered: the share plugin's own job reports "received"; cancelled or suspended: nothing to show.
                    else -> TransferProgress.cancel(job)
                }
            }
            if (receives[receiver.id] === receiver) receives.remove(receiver.id)
            folderOffers.remove(receiver.id)
            clipboardOffers.remove(receiver.id)
        }
    }

    /**
     * One binary frame from the data channel, on libwebrtc's thread: bound-checked here (cheap,
     * no I/O) and queued to the file thread. Past [FileV2.FILE_QUEUE_MAX_BYTES] the receivers are
     * paused (`backlog`) instead of anything being dropped silently; they restart from their last
     * verified block when the queue has drained.
     */
    fun handleFrame(message: ByteArray) {
        val frame = try {
            FileFrame.decode(message, FileV2.FRAME_BYTES_PROVEN)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Dropped a malformed file frame")
            return
        }
        val queued = queuedBytes.addAndGet(message.size.toLong())
        if (queued > FileV2.FILE_QUEUE_MAX_BYTES) {
            queuedBytes.addAndGet(-message.size.toLong())
            if (backlogged.compareAndSet(false, true)) runner.post {
                pausedForBacklog = true
                receives.values.toList().forEach(FileReceiver::pauseBacklog)
                resumeIfDrained() // the queue may have emptied while this task waited its turn
            }
            return
        }
        runner.post {
            queuedBytes.addAndGet(-message.size.toLong())
            receives[frame.transferId.toString()]?.onFrame(frame)
            resumeIfDrained()
        }
    }

    private fun resumeIfDrained() {
        if (pausedForBacklog && queuedBytes.get() < FileV2.FILE_QUEUE_MAX_BYTES / 2) {
            pausedForBacklog = false
            backlogged.set(false)
            receives.values.toList().forEach(FileReceiver::resumeBacklog)
        }
    }

    /** Tests: the receive queue overflows (`on`) or drains, as if frames had piled up. */
    @androidx.annotation.VisibleForTesting
    internal fun testBacklog(on: Boolean) {
        runner.post {
            pausedForBacklog = on
            backlogged.set(on)
            receives.values.toList().forEach { if (on) it.pauseBacklog() else it.resumeBacklog() }
        }
    }

    /** The data channel's buffered amount fell under its low mark: send more. */
    fun onBufferedAmountLow() {
        if (pumpPending.compareAndSet(false, true)) runner.post {
            pumpPending.set(false)
            sends.values.toList().forEach { it.sender.pump() }
        }
    }

    // ------------------------------------------------------------- lifecycle

    /** Tests: runs inside every tick, to make one fail. */
    @androidx.annotation.VisibleForTesting
    internal var tickHook: (() -> Unit)? = null

    private fun tick() {
        tickHook?.invoke()
        val now = clock()
        sends.values.toList().forEach { it.sender.tick(now) }
        receives.values.toList().forEach { it.tick(now) }
    }

    @androidx.annotation.VisibleForTesting
    internal fun tickNow() = runner.post { tick() }

    /**
     * The session ended. Checkpoints are kept (to the last verified block) so the next connection
     * resumes them, and a send's owner hears [TransferResumingException], not a failure.
     */
    fun close(reason: String) {
        runner.post {
            sends.values.toList().forEach { it.sender.suspend() }
            receives.values.toList().forEach(FileReceiver::suspend)
            queuedBytes.set(0)
            backlogged.set(false)
            pausedForBacklog = false
        }
    }

    /** A Bucket item removed while it arrives: its transfer ends on both sides. */
    fun cancelFolderReceive(relpath: String) {
        runner.post {
            val id = folderOffers.entries.firstOrNull { it.value.relpath == relpath }?.key ?: return@post
            receives[id]?.cancel(FileCodes.REMOVED, "The file was removed.")
        }
    }

    /** A new connection: sends that were paused by a lost link offer themselves again from their checkpoints. */
    fun resumeSends(wire: WebRtcWireBinding) {
        runner.post {
            currentWire = wire
            startStoredSends(wire)
        }
    }

    /**
     * Starts stored sends in order, never more than [FileV2.ACTIVE_PER_DIRECTION] at once (a third would be told busy);
     * the rest wait their turn and start as these end.
     */
    private fun startStoredSends(wire: WebRtcWireBinding) {
        store.list().filter { it.direction == "send" && it.deviceId == wire.peerDeviceId }.sortedBy { it.transferId }.forEach { stored ->
            val id = stored.transferId
            if (sends.containsKey(id)) return@forEach
            val source = sourceOf(stored)
            val stat = source?.stat()
            if (source == null || stat == null || stat.size != stored.totalSize || stat.mtime != stored.mtime) {
                // Gone or changed since: nothing to resume. (The person sends it again.)
                store.remove(id)
                TransferHistory.forgetResumable(appContext, id)
                return@forEach
            }
            if (sends.size >= FileV2.ACTIVE_PER_DIRECTION) return@forEach
            val hashes = store.readBlocks(id)
            val callback = resumedSendCallback(wire.peerDeviceId, id, stored.filename, stored.totalSize)
            val sender = newSender(wire, id, stored.token, source, stored.filename, stored.totalSize, stored.mtime, stored.meta, stored.spooled, hashes)
            val entry = SendEntry(sender, callback, CompletableFuture(), null)
            sends[id] = entry
            armTick()
            startSender(entry)
        }
    }

    private fun sourceOf(stored: FileCheckpoint): FileSource? {
        if (stored.spooled) return PlainFileSource(store.spool(stored.transferId))
        val uri = stored.sourceUri?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() } ?: return null
        return when (uri.scheme) {
            "file" -> PlainFileSource(File(uri.path ?: return null)).takeIf { stored.sourceUri != null }
            "content" -> UriFileSource(appContext, uri)
            else -> null
        }
    }

    /** A resumed send reports like the original job did: Home's transfer row and the history. */
    private fun resumedSendCallback(deviceId: String, transferId: String, filename: String, size: Long) =
        object : Device.SendPacketStatusCallback() {
            private val jobId = RESUMED_JOB_BASE + (transferId.hashCode().toLong() and 0xffffffffL)

            override fun onPayloadProgressChanged(percent: Int) =
                TransferProgress.update(deviceId, jobId, true, filename, percent, size)

            override fun onSuccess() {
                TransferProgress.update(deviceId, jobId, true, filename, 100, size)
                TransferProgress.finish(jobId, filename, false)
                TransferHistory.recordResumedSent(appContext, deviceId, transferId, filename, size)
            }

            override fun onFailure(e: Throwable) {
                if (e is TransferResumingException) TransferProgress.resuming(jobId) else TransferProgress.finish(jobId, filename, true)
            }
        }

    private fun reply(wire: WebRtcWireBinding, id: String, token: String, action: WebRtcFileAction) = WebRtcFileControl(
        action = action, transferId = id, deviceId = wire.senderDeviceId, sessionId = wire.sessionId,
        connectionGeneration = wire.generation, transferToken = token,
    )

    private fun receiveJob(transferId: String) = RECEIVE_JOB_BASE + (transferId.hashCode().toLong() and 0xffffffffL)

    private fun randomToken(): String = UUID.randomUUID().toString().replace("-", "")

    companion object {
        private const val TAG = "DeskLink/Files"
        private const val TICK_MS = 500L

        /** Job ids for resumed sends, apart from the share plugin's own. */
        private const val RESUMED_JOB_BASE = 1L shl 40

        /** Job ids for transfers being received, apart from the share plugin's and the resumed sends'. */
        private const val RECEIVE_JOB_BASE = 1L shl 41
    }
}

/** The link went away mid-send; the checkpoint is kept and the next connection resumes it. */
class TransferResumingException(reason: String) : IllegalStateException(reason)

/** The link under a transfer closed or is not open yet: pause it, never fail it. */
internal fun linkClosed(error: Throwable): Boolean =
    error is org.desklink.mobile.transport.TransportError &&
        error.code == org.desklink.mobile.transport.TransportErrorCode.CLOSED

/**
 * What the peer is told when a transfer ends here: short human text, never a class name or a
 * raw platform message (those reached the desk as "org.desklink…TransportError: …").
 */
internal fun peerReason(error: Throwable, fallback: String): String {
    val message = error.message.orEmpty()
    return when {
        generateSequence(error) { it.cause }.any { "ENOSPC" in it.message.orEmpty() || "No space left" in it.message.orEmpty() } ->
            "Not enough space on the phone"
        // Our own checks (require/check) say what went wrong in plain words.
        (error is IllegalArgumentException || error is IllegalStateException) && message.isNotBlank() &&
            !Regex("""Exception|Error:|org\.|java\.|android\.|/""").containsMatchIn(message) -> message.take(256)
        else -> fallback
    }
}
