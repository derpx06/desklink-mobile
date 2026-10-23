/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** What an engine needs from the connection. Both send calls throw a CLOSED `TransportError` when it is not open. */
interface FileLink {
    fun sendControl(control: WebRtcFileControl)
    fun sendFrame(message: ByteArray)
    fun bufferedAmount(): Long
}

/**
 * One incoming file (transfers v2). Single-threaded: every call comes from the manager's file
 * thread. It writes frames at their offsets, hashes each 1 MiB block as it lands and checks it
 * against the sender's trailer, acknowledges blocks (not frames), checkpoints every
 * [FileV2.CHECKPOINT_BLOCKS] blocks or [FileV2.CHECKPOINT_MS], and never re-reads the file: the
 * file hash is derived from the block hashes.
 */
class FileReceiver(
    val wire: WebRtcWireBinding,
    private val store: FileTransferStore,
    private val link: FileLink,
    private val clock: () -> Long,
    private val freeSpace: () -> Long,
    private val listener: Listener,
    val offer: WebRtcFileControl,
    /** Runs a task on the thread this receiver lives on (the file thread); inline when a test does not care. */
    private val post: (() -> Unit) -> Unit = { it() },
) {
    interface Listener {
        /** Bytes of this file in place so far (verified blocks plus the current one). */
        fun progress(receiver: FileReceiver, bytesDone: Long)

        /** Hands the verified file on (to the destination, the clipboard or the Bucket). Throws if it cannot be delivered. */
        fun deliver(receiver: FileReceiver, partial: File, fileHash: String)

        /**
         * Starts [deliver] where it may take a long time (a copy of several GiB) without holding up the file thread, and
         * calls [finished] with null, or the error, from any thread. The default delivers at once.
         */
        fun deliverAsync(receiver: FileReceiver, partial: File, fileHash: String, finished: (Throwable?) -> Unit) {
            finished(runCatching { deliver(receiver, partial, fileHash) }.exceptionOrNull())
        }

        /**
         * [deliver] threw: keeps the verified [partial] for a retry (moving it out of the transfer directory) and says
         * so; true if it was kept. Otherwise it is deleted with the rest.
         */
        fun keepUndelivered(receiver: FileReceiver, partial: File, error: Throwable): Boolean = false

        /** The transfer is over (delivered, failed or cancelled); the manager forgets it. */
        fun ended(receiver: FileReceiver, outcome: Outcome, message: String?)
    }

    enum class Outcome { DELIVERED, FAILED, CANCELLED, SUSPENDED }

    val id: String = offer.transferId
    private val uuid: UUID = UUID.fromString(id)
    val totalSize: Long = requireNotNull(offer.totalSize)
    val filename: String = requireNotNull(offer.filename)
    private val blocksTotal = FileV2.blockCount(totalSize)
    private val frameMax = minOf(requireNotNull(offer.maxFrameBytes), FileV2.FRAME_BYTES_PROVEN)

    private val hashes = ArrayList<ByteArray>()
    private var file: RandomAccessFile? = null
    private var digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    private var expectedPass = 0

    /**
     * Set after an accept (ours, or the answer to a re-offer): the next frame at a block boundary we have verified
     * up to starts the stream, whatever its pass; its pass becomes ours. Not after our own resume: frames of the
     * old pass still in flight must be dropped (transcript S3); the sender's reply to a resume is the next pass.
     */
    private var adoptAny = true
    private var awaitingStart = true
    private var nextOffset = 0L
    private var rewinds = 0
    private var restarts = 0
    private var pausedFor: String? = null
    private var pausedAt = 0L
    private var peerPaused = false
    private var noProgress = false
    private var lastActivityAt = clock()
    private var lastCheckpointAt = clock()
    private var unsavedBlocks = 0
    private var ended = false

    /** The verified file is being put in place (on another thread): frames, a restart or a peer's end change nothing now. */
    private var publishing = false

    /** Set when the file arrived whole but could not be saved: what went wrong, for the notification and the Files tab. */
    @Volatile var saveFailure: Throwable? = null
        private set

    /** Where a frame's bytes go; replaced by tests to simulate a full disk. */
    @androidx.annotation.VisibleForTesting
    internal var writeFrame: (FileFrame, RandomAccessFile) -> Unit = { frame, raf -> frame.writeTo(raf) }

    /** Where the time of this receive went, in nanoseconds of the file thread (see [summary]). */
    private var writeNs = 0L
    private var hashNs = 0L
    private var checkpointNs = 0L
    private var ackNs = 0L
    private var frames = 0L
    private val startedAt = System.nanoTime()

    /**
     * One line for the debug log at the end of a receive: bytes, seconds, MB/s and how much of that time this thread
     * spent hashing, writing, checkpointing (fsync) and answering. The rest was spent waiting for frames: the network or
     * the sender, not this phone.
     */
    fun summary(): String {
        val seconds = (System.nanoTime() - startedAt) / 1e9
        fun ms(ns: Long) = ns / 1_000_000
        val busy = writeNs + hashNs + checkpointNs + ackNs
        return "receive-stats $filename bytes=$totalSize seconds=${"%.1f".format(seconds)} MBps=${"%.1f".format(totalSize / 1e6 / seconds.coerceAtLeast(0.001))} " +
            "frames=$frames hashMs=${ms(hashNs)} writeMs=${ms(writeNs)} checkpointMs=${ms(checkpointNs)} ackMs=${ms(ackNs)} " +
            "waitingMs=${(seconds * 1000).toLong() - ms(busy)}"
    }

    /** The verified block hashes (a copy), for the record kept of a delivered file. */
    fun blockHashes(): List<ByteArray> = hashes.toList()

    val verifiedBlocks: Int get() = hashes.size
    val isPaused: String? get() = pausedFor ?: if (noProgress) FileCodes.NO_PROGRESS else null

    /** Bytes in place: verified blocks plus the part of the current one. */
    fun bytesDone(): Long = minOf(totalSize, hashes.size.toLong() * FileV2.BLOCK_BYTES) +
        if (awaitingStart) 0 else (nextOffset - hashes.size.toLong() * FileV2.BLOCK_BYTES).coerceAtLeast(0)

    /**
     * Opens the partial file (resuming a matching checkpoint, else starting at block 0) and answers the offer.
     * [resumed] is trusted only if it describes this very transfer; anything else is deleted.
     */
    fun start(resumed: FileCheckpoint?) {
        val usable = resumed?.takeIf {
            it.direction == "receive" && it.token == offer.transferToken && it.totalSize == totalSize &&
                it.mtime == offer.mtime && it.filename == filename && it.deviceId == wire.peerDeviceId
        }
        if (usable == null && (resumed != null || store.exists(id))) store.remove(id)
        val partial = store.partial(id)
        require(!java.nio.file.Files.isSymbolicLink(partial.toPath())) { "Refusing a symlink transfer path" }
        var verified = 0
        if (usable != null) {
            val sidecar = store.readBlocks(id)
            verified = minOf(usable.blocksDone, sidecar.size, blocksTotal)
            // The checkpoint never claims more than the partial file proves.
            while (verified > 0 && partial.length() < minOf(totalSize, verified.toLong() * FileV2.BLOCK_BYTES)) verified--
            hashes.addAll(sidecar.take(verified))
        } else if (partial.exists()) {
            partial.delete() // a partial with no checkpoint is never trusted
        }
        val raf = RandomAccessFile(partial, "rw")
        file = raf
        raf.setLength(minOf(totalSize, verified.toLong() * FileV2.BLOCK_BYTES))
        raf.fd.sync()
        nextOffset = raf.length()
        if (!store.save(checkpoint())) {
            close()
            throw IOException("Could not persist the incoming transfer")
        }
        awaitingStart = true
        expectedPass = 0
        adoptAny = true
        lastActivityAt = clock()
        listener.progress(this, bytesDone())
        sendAccept()
    }

    private fun sendAccept() = send(
        control(WebRtcFileAction.ACCEPT).copy(
            resumeBlock = hashes.size,
            prefixSha256 = FileHashes.prefixHash(hashes, hashes.size),
            maxFrameBytes = frameMax,
        ),
    )

    /** The same transfer offered again while it is live (the sender lost our accept): answered from where we are. */
    fun onReoffer() {
        if (ended || publishing || file == null) return
        pausedFor = null
        truncateTo(hashes.size)
        awaitingStart = true
        adoptAny = true
        lastActivityAt = clock()
        sendAccept()
    }

    // ------------------------------------------------------------ frames

    fun onFrame(frame: FileFrame) {
        if (ended || publishing || pausedFor != null || file == null) return
        val end = frame.offset + frame.length
        if (end > totalSize) return
        if (frame.pass != expectedPass) {
            // A pass we do not expect starts a new one only from a block boundary at or before our verified end,
            // right after an accept or our own resume (any pass) or as the sender's own rewind (the pass after ours).
            val aligned = frame.offset % FileV2.BLOCK_BYTES == 0L && frame.blockIndex <= hashes.size
            if (!aligned || !(adoptAny || frame.pass == ((expectedPass + 1) and 0xff))) return
            truncateTo(frame.blockIndex)
            expectedPass = frame.pass
            awaitingStart = false
        }
        if (frame.pass == expectedPass && frame.offset >= nextOffset) adoptAny = false
        if (awaitingStart) {
            // The first frame after an accept, a nack or a resume: a block boundary the receiver has verified up to.
            // A frame of the right pass that starts anywhere else is a sender we cannot follow.
            if (frame.offset % FileV2.BLOCK_BYTES != 0L || frame.blockIndex > hashes.size) {
                return fail(FileCodes.BAD_FRAME, "The computer sent the file out of order.")
            }
            truncateTo(frame.blockIndex)
            awaitingStart = false
        }
        if (frame.offset < nextOffset) return // a duplicate
        if (frame.offset > nextOffset) return fail(FileCodes.BAD_FRAME, "The computer sent the file out of order.")
        val blockEnd = minOf(totalSize, (frame.blockIndex.toLong() + 1) * FileV2.BLOCK_BYTES)
        if (frame.lastOfBlock != (end == blockEnd)) return fail(FileCodes.BAD_FRAME, "The computer sent a damaged part of the file.")
        lastActivityAt = clock()
        if (noProgress) {
            // Data is flowing again: the sender is told, with a bare `resume` (no restart point).
            noProgress = false
            send(control(WebRtcFileAction.RESUME))
        }
        val t0 = System.nanoTime()
        try {
            writeFrame(frame, requireNotNull(file))
        } catch (error: IOException) {
            return onWriteFailure(error)
        }
        val t1 = System.nanoTime()
        frame.updateDigest(digest)
        frames++
        writeNs += t1 - t0
        hashNs += System.nanoTime() - t1
        nextOffset = end
        if (!frame.lastOfBlock) return
        val t2 = System.nanoTime()
        val computed = digest.digest()
        hashNs += System.nanoTime() - t2
        digest = MessageDigest.getInstance("SHA-256")
        if (computed.contentEquals(frame.trailer())) {
            hashes.add(computed)
            unsavedBlocks++
            val t3 = System.nanoTime()
            send(control(WebRtcFileAction.BLOCK_ACK).copy(blockIndex = frame.blockIndex, sha256 = computed.toHex()))
            listener.progress(this, bytesDone())
            val t4 = System.nanoTime()
            maybeCheckpoint(force = false)
            ackNs += t4 - t3
            checkpointNs += System.nanoTime() - t4
        } else {
            rewind(frame.blockIndex)
        }
    }

    /** The block failed its hash: drop it and ask for it again under a new pass. */
    private fun rewind(blockIndex: Int) {
        if (++rewinds > FileV2.MAX_REWINDS) return fail(FileCodes.HASH_MISMATCH, "The file got damaged on the way and could not be fixed.")
        truncateTo(blockIndex)
        expectedPass = (expectedPass + 1) and 0xff
        awaitingStart = true
        send(control(WebRtcFileAction.BLOCK_NACK).copy(blockIndex = blockIndex, pass = expectedPass, reason = FileCodes.HASH_MISMATCH))
    }

    private fun truncateTo(block: Int) {
        while (hashes.size > block) hashes.removeAt(hashes.size - 1)
        val raf = requireNotNull(file)
        runCatching { raf.setLength(minOf(totalSize, block.toLong() * FileV2.BLOCK_BYTES)) }
        nextOffset = minOf(totalSize, block.toLong() * FileV2.BLOCK_BYTES)
        digest = MessageDigest.getInstance("SHA-256")
        unsavedBlocks = minOf(unsavedBlocks, hashes.size)
    }

    private fun onWriteFailure(error: IOException) {
        val full = generateSequence<Throwable>(error) { it.cause }.any { "ENOSPC" in it.message.orEmpty() || "No space left" in it.message.orEmpty() }
        if (!full) return fail(FileCodes.INTERNAL, "The phone could not store the file.")
        // Keep what is verified; drop the half block; wait for room.
        runCatching { requireNotNull(file).setLength(minOf(totalSize, hashes.size.toLong() * FileV2.BLOCK_BYTES)) }
        nextOffset = minOf(totalSize, hashes.size.toLong() * FileV2.BLOCK_BYTES)
        digest = MessageDigest.getInstance("SHA-256")
        maybeCheckpoint(force = true)
        pause(FileCodes.STORAGE_FULL)
    }

    // ------------------------------------------------------------ control

    /** The sender's `done`: verify the derived file hash, deliver, answer `complete`. */
    fun onDone(message: WebRtcFileControl) {
        if (ended) return
        val derived = FileHashes.fileHash(hashes)
        val consistent = hashes.size == blocksTotal && message.blocks == blocksTotal && message.totalSize == totalSize &&
            message.fileHash.equals(derived, ignoreCase = true)
        if (!consistent) {
            if (++restarts > 1 || ++rewinds > FileV2.MAX_REWINDS) return fail(FileCodes.HASH_MISMATCH, "The file got damaged on the way and could not be fixed.")
            // Start again from nothing under the next pass: one form on the wire, `resume {0, SHA-256(""), pass+1}`.
            truncateTo(0)
            expectedPass = (expectedPass + 1) and 0xff
            awaitingStart = true
            send(control(WebRtcFileAction.RESUME).copy(resumeBlock = 0, prefixSha256 = FileHashes.prefixHash(hashes, 0), pass = expectedPass))
            return
        }
        if (publishing) return // a repeated `done` while the file is being put in place
        val raf = requireNotNull(file)
        try {
            raf.fd.sync()
            raf.close()
        } catch (error: Throwable) {
            runCatching { raf.close() }
            file = null
            return undelivered(error, derived)
        }
        file = null
        publishing = true
        listener.deliverAsync(this, store.partial(id), derived) { error -> post { afterDelivery(error, derived) } }
    }

    /** The file is in place, or is not: only now does the sender hear `complete`, or `error`. On the receiver's own thread. */
    private fun afterDelivery(error: Throwable?, derived: String) {
        publishing = false
        if (error != null) return undelivered(error, derived)
        val alreadyOver = ended // the link went while the file was being put in place: it is there, the sender asks again
        ended = true
        if (!alreadyOver) runCatching { send(control(WebRtcFileAction.COMPLETE).copy(fileHash = derived)) }
        store.removeMetadata(id)
        if (!alreadyOver) listener.ended(this, Outcome.DELIVERED, null)
    }

    /** Verified but not in place: never `complete`. The sender is told it failed and the file is kept for a retry. */
    private fun undelivered(error: Throwable, derived: String) {
        saveFailure = error
        val kept = runCatching { listener.keepUndelivered(this, store.partial(id), error) }.getOrDefault(false)
        val full = isStorageFull(error)
        val message = when {
            full -> "Not enough space on the phone" + if (kept) "; the file is kept to try again." else "."
            kept -> "The phone could not save the file; it is kept to try again."
            else -> "The phone could not save the file."
        }
        if (ended) {
            store.remove(id)
            return
        }
        finish(
            Outcome.FAILED, message,
            tellPeer = control(WebRtcFileAction.ERROR).copy(code = if (full) FileCodes.STORAGE_FULL else FileCodes.INTERNAL, message = message),
        )
    }

    fun onPeerPause(reason: String? = null) {
        // "no-progress" is informational (the sender noticing a stall); only a real pause quiets the clock.
        if (reason != FileCodes.NO_PROGRESS) peerPaused = true
    }

    fun onPeerResume() {
        peerPaused = false
        lastActivityAt = clock()
    }

    /** The peer ended it (cancel or error): everything kept is deleted. */
    fun onPeerEnded(cancelled: Boolean, message: String?) {
        if (publishing) return // it arrived whole: the sender's end changes nothing now
        finish(if (cancelled) Outcome.CANCELLED else Outcome.FAILED, message, tellPeer = null)
    }

    /** This side ends it (the person removed the file): the peer is told and both clean up. */
    fun cancel(code: String, message: String) {
        if (publishing) return
        finish(Outcome.CANCELLED, message, tellPeer = control(WebRtcFileAction.CANCEL).copy(code = code, message = message))
    }

    /** The manager is overloaded (a queue over its cap): stop the sender until it drains. */
    fun pauseBacklog() = pause(FileCodes.BACKLOG)

    fun resumeBacklog() {
        if (pausedFor == FileCodes.BACKLOG) resume()
    }

    private fun pause(reason: String) {
        if (ended || pausedFor != null) return
        pausedFor = reason
        pausedAt = clock()
        send(control(WebRtcFileAction.PAUSE).copy(reason = reason))
    }

    /** Back to receiving: the sender restarts exactly at the last verified block under a new pass. */
    private fun resume() {
        pausedFor = null
        truncateTo(hashes.size)
        expectedPass = (expectedPass + 1) and 0xff
        awaitingStart = true
        lastActivityAt = clock()
        send(
            control(WebRtcFileAction.RESUME).copy(
                resumeBlock = hashes.size,
                prefixSha256 = FileHashes.prefixHash(hashes, hashes.size),
                pass = expectedPass,
            ),
        )
    }

    // -------------------------------------------------------------- clock

    fun tick(now: Long) {
        if (ended || file == null) return
        maybeCheckpoint(force = false)
        when (pausedFor) {
            FileCodes.STORAGE_FULL -> if (now - pausedAt >= FileV2.STORAGE_RETRY_MS) {
                pausedAt = now
                val needed = minOf(FileV2.BLOCK_BYTES.toLong(), totalSize - hashes.size.toLong() * FileV2.BLOCK_BYTES)
                if (freeSpace() >= needed + MIN_FREE_BYTES) resume()
            }
            null -> if (!peerPaused && !noProgress && hashes.size < blocksTotal && now - lastActivityAt >= FileV2.NO_PROGRESS_MS) {
                noProgress = true
                send(control(WebRtcFileAction.PAUSE).copy(reason = FileCodes.NO_PROGRESS))
            }
        }
    }

    // ------------------------------------------------------- persistence

    private fun checkpoint() = FileCheckpoint(
        transferId = id, direction = "receive", token = offer.transferToken, deviceId = wire.peerDeviceId,
        filename = filename, totalSize = totalSize, mtime = requireNotNull(offer.mtime), blocksDone = hashes.size,
        meta = offerMeta(offer),
    )

    private fun maybeCheckpoint(force: Boolean) {
        val now = clock()
        val due = force || unsavedBlocks >= FileV2.CHECKPOINT_BLOCKS || (unsavedBlocks > 0 && now - lastCheckpointAt >= FileV2.CHECKPOINT_MS)
        if (!due) return
        val raf = file ?: return
        // The partial file first, then the hash list, then the checkpoint that names them.
        val ok = runCatching { raf.fd.sync() }.isSuccess && store.writeBlocks(id, hashes) && store.save(checkpoint())
        if (ok) unsavedBlocks = 0
        lastCheckpointAt = now
    }

    /** The link went away: keep everything verified so far (to the block) for the next connection. */
    fun suspend() {
        if (ended) return
        maybeCheckpoint(force = true)
        close()
        ended = true
        listener.ended(this, Outcome.SUSPENDED, null)
    }

    private fun fail(code: String, message: String) = finish(
        Outcome.FAILED, message, tellPeer = control(WebRtcFileAction.ERROR).copy(code = code, message = message),
    )

    private fun finish(outcome: Outcome, message: String?, tellPeer: WebRtcFileControl?) {
        if (ended) return
        ended = true
        close()
        store.remove(id)
        tellPeer?.let { runCatching { link.sendControl(it) } }
        listener.ended(this, outcome, message)
    }

    private fun close() {
        runCatching { file?.close() }
        file = null
    }

    private fun send(control: WebRtcFileControl) {
        try {
            link.sendControl(control)
        } catch (error: Throwable) {
            if (linkClosed(error)) suspend() else throw error
        }
    }

    private fun control(action: WebRtcFileAction) = WebRtcFileControl(
        action = action, transferId = id, deviceId = wire.senderDeviceId, sessionId = wire.sessionId,
        connectionGeneration = wire.generation, transferToken = offer.transferToken,
    )

    companion object {
        /** Room kept free beyond the block being written. */
        const val MIN_FREE_BYTES = 16L * 1024 * 1024

        fun offerMeta(offer: WebRtcFileControl): Map<String, String> = buildMap {
            if (offer.clipboard == true) put("clipboard", "true")
            offer.mimeType?.let { put("mimeType", it) }
            offer.captureId?.let { put("captureId", it) }
            offer.folder?.let { put("folder", it) }
            offer.relpath?.let { put("relpath", it) }
            offer.contentSha256?.let { put("contentSha256", it) }
        }
    }
}
