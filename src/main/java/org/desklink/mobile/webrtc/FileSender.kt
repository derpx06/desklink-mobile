/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * One outgoing file (transfers v2). Single-threaded: every call comes from the manager's file
 * thread. It reads the source **in place**, one 1 MiB block at a time, hashing each block as it
 * reads it, and sends frames while the data channel's buffered amount and the unacknowledged bytes
 * are under their limits ([FileV2.IN_FLIGHT_TARGET], [FileV2.UNACKED_MAX]). The source is re-stat'ed
 * before every block and after the last: a change ends this transfer (the manager may start a new
 * one), a missing file fails it. Nothing is ever read twice except to rewind a failed block.
 */
class FileSender(
    val wire: WebRtcWireBinding,
    private val store: FileTransferStore,
    private val link: FileLink,
    private val clock: () -> Long,
    private val listener: Listener,
    val transferId: String,
    val token: String,
    private val source: FileSource,
    val filename: String,
    val totalSize: Long,
    val mtime: Long,
    private val meta: Map<String, String>,
    private val spooled: Boolean,
) {
    interface Listener {
        /** Bytes acknowledged plus the current block's, at most four times a second. */
        fun progress(sender: FileSender, bytesDone: Long)

        fun ended(sender: FileSender, outcome: Outcome, message: String?)
    }

    enum class Outcome { COMPLETED, FAILED, CANCELLED, SUSPENDED, BUSY, SOURCE_CHANGED }

    private enum class State { OFFERED, STREAMING, AWAITING_COMPLETE, ENDED }

    private val uuid: UUID = UUID.fromString(transferId)
    private val blocksTotal = FileV2.blockCount(totalSize)
    private var state = State.OFFERED
    private var frameBytes = FileV2.FRAME_BYTES_PROVEN

    /** Hashes of the blocks read so far, by index; the first [ackedBlocks] are acknowledged. */
    private val hashes = ArrayList<ByteArray>()
    private var ackedBlocks = 0
    private var cursor = 0
    private var pass = 0
    private var stream: InputStream? = null
    private var block: ByteArray? = null
    private var blockLength = 0
    private var blockHash: ByteArray? = null
    private var emitted = 0
    private var peerPaused: String? = null
    private var noProgress = false
    private var rewinds = 0
    private var lastProgressAt = clock()
    private var lastCheckpointAt = clock()
    private var unsavedBlocks = 0
    private var lastReportAt = 0L
    private var ended = false

    fun sourceForRestart(): FileSource = source
    fun metaForRestart(): Map<String, String> = meta
    fun isSpooled(): Boolean = spooled

    val isPaused: String? get() = peerPaused ?: if (noProgress) FileCodes.NO_PROGRESS else null
    val acknowledgedBlocks: Int get() = ackedBlocks

    /** The block hashes read so far: a retry of the same transfer carries them over (the sidecar is never rewritten empty). */
    fun hashesSnapshot(): List<ByteArray> = hashes.toList()

    /** Resume state from a stored checkpoint: the block hashes already acknowledged in an earlier connection. */
    fun restore(acknowledged: List<ByteArray>) {
        hashes.clear()
        hashes.addAll(acknowledged)
    }

    /** The offer; sent on every (re)connection with the same id and token. */
    fun start() {
        saveCheckpoint(force = true)
        send(
            control(WebRtcFileAction.OFFER).copy(
                filename = filename, totalSize = totalSize, mtime = meta["mtime"]?.toLongOrNull() ?: mtime, blockSize = FileV2.BLOCK_BYTES,
                maxFrameBytes = FileV2.FRAME_BYTES_PROVEN,
                clipboard = meta["clipboard"]?.let { true },
                mimeType = meta["mimeType"], captureId = meta["captureId"],
                folder = meta["folder"], relpath = meta["relpath"], contentSha256 = meta["contentSha256"],
            ),
        )
    }

    // ------------------------------------------------------------ control

    fun onControl(message: WebRtcFileControl) {
        if (ended) return
        when (message.action) {
            WebRtcFileAction.ACCEPT -> if (state == State.OFFERED) {
                frameBytes = minOf(requireNotNull(message.maxFrameBytes), FileV2.FRAME_BYTES_PROVEN)
                // Ends the transfer if the source changed while its prefix was being checked.
                if (!restartAt(requireNotNull(message.resumeBlock), requireNotNull(message.prefixSha256), pass)) return
                state = State.STREAMING
                touch()
                pump()
            }
            WebRtcFileAction.BLOCK_ACK -> onAck(requireNotNull(message.blockIndex), requireNotNull(message.sha256))
            WebRtcFileAction.BLOCK_NACK -> onNack(requireNotNull(message.blockIndex), requireNotNull(message.pass))
            // "no-progress" is informational: it never stops the sender.
            WebRtcFileAction.PAUSE -> if (message.reason != FileCodes.NO_PROGRESS) peerPaused = FileCodes.pauseReason(message.reason)
            WebRtcFileAction.RESUME -> {
                peerPaused = null
                touch()
                if (message.resumeBlock != null) {
                    if (!restartAt(message.resumeBlock, requireNotNull(message.prefixSha256), requireNotNull(message.pass))) return
                    if (state == State.AWAITING_COMPLETE) state = State.STREAMING
                }
                pump()
            }
            WebRtcFileAction.COMPLETE -> {
                if (message.fileHash.equals(FileHashes.fileHash(hashes), ignoreCase = true) && ackedBlocks == blocksTotal) {
                    finish(Outcome.COMPLETED, null, tellPeer = null, keep = false)
                } else {
                    finish(Outcome.FAILED, "The computer reported a different file than the one sent.", control(WebRtcFileAction.ERROR).copy(code = FileCodes.HASH_MISMATCH, message = "The file does not match."), keep = false)
                }
            }
            WebRtcFileAction.CANCEL, WebRtcFileAction.ERROR -> when {
                message.action == WebRtcFileAction.ERROR && message.code == FileCodes.BUSY -> finish(Outcome.BUSY, null, null, keep = true)
                else -> finish(
                    if (message.action == WebRtcFileAction.CANCEL) Outcome.CANCELLED else Outcome.FAILED,
                    message.message ?: if (message.action == WebRtcFileAction.CANCEL) "The computer cancelled the transfer." else "The computer refused the file.",
                    null, keep = false,
                )
            }
            else -> Unit
        }
    }

    /**
     * Start (or restart) streaming at the receiver's verified block when our hashes agree with its prefix, else at
     * block 0 under the next pass (the receiver adopts the new pass). Hashes we do not hold (a retry, a lost
     * sidecar) are read again from the source, hashing only, exactly as the desk does. False: the source
     * changed or went while that was done, and the transfer has ended.
     */
    private fun restartAt(resumeBlock: Int, prefix: String, pass: Int): Boolean {
        if (resumeBlock in hashes.size + 1..blocksTotal && !rehashPrefix(resumeBlock)) return false
        val agree = resumeBlock <= hashes.size && resumeBlock <= blocksTotal &&
            FileHashes.prefixHash(hashes, resumeBlock).equals(prefix, ignoreCase = true)
        val start = if (agree) resumeBlock else 0
        rewindTo(start)
        ackedBlocks = start
        this.pass = if (agree) pass else (pass + 1) and 0xff
        return true
    }

    /** Reads blocks [hashes].size until [upto] from the source and hashes them (nothing is sent). */
    private fun rehashPrefix(upto: Int): Boolean {
        if (!sourceUnchanged()) return false
        try {
            source.open(hashes.size.toLong() * FileV2.BLOCK_BYTES).use { input ->
                val buffer = ByteArray(FileV2.BLOCK_BYTES)
                while (hashes.size < upto) {
                    val length = minOf(FileV2.BLOCK_BYTES.toLong(), totalSize - hashes.size.toLong() * FileV2.BLOCK_BYTES).toInt()
                    var read = 0
                    while (read < length) {
                        val n = input.read(buffer, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read < length) {
                        sourceChanged()
                        return false
                    }
                    hashes.add(MessageDigest.getInstance("SHA-256").apply { update(buffer, 0, length) }.digest())
                }
            }
        } catch (error: IOException) {
            fail("The file could not be read.", FileCodes.INTERNAL)
            return false
        }
        return true
    }

    private fun rewindTo(index: Int) {
        while (hashes.size > index) hashes.removeAt(hashes.size - 1)
        cursor = index
        block = null
        blockHash = null
        emitted = 0
        runCatching { stream?.close() }
        stream = null
        if (ackedBlocks > index) ackedBlocks = index
    }

    private fun onAck(index: Int, sha: String) {
        if (state == State.OFFERED || index !in 0 until hashes.size || index + 1 <= ackedBlocks) return
        if (!sha.equals(hashes[index].toHex(), ignoreCase = true)) {
            // The receiver verified a block that is not the one we read: read and send it again under the next pass.
            if (++rewinds > FileV2.MAX_REWINDS) {
                return finish(Outcome.FAILED, "The file got damaged on the way and could not be fixed.", control(WebRtcFileAction.ERROR).copy(code = FileCodes.HASH_MISMATCH, message = "Too many damaged blocks."), keep = false)
            }
            rewindTo(index)
            pass = (pass + 1) and 0xff
            if (state == State.AWAITING_COMPLETE) state = State.STREAMING
            touch()
            return pump()
        }
        unsavedBlocks += index + 1 - ackedBlocks
        ackedBlocks = index + 1
        touch()
        report(force = ackedBlocks == blocksTotal)
        saveCheckpoint(force = false)
        pump()
    }

    private fun onNack(index: Int, newPass: Int) {
        if (state == State.OFFERED || index !in 0 until maxOf(1, hashes.size + 1)) return
        if (++rewinds > FileV2.MAX_REWINDS) {
            return finish(Outcome.FAILED, "The file got damaged on the way and could not be fixed.", control(WebRtcFileAction.ERROR).copy(code = FileCodes.HASH_MISMATCH, message = "Too many damaged blocks."), keep = false)
        }
        rewindTo(index)
        pass = newPass
        if (state == State.AWAITING_COMPLETE) state = State.STREAMING
        touch()
        pump()
    }

    // ------------------------------------------------------------- sending

    /** Sends frames while the window allows; call after any event that may have opened it. */
    fun pump() {
        if (ended || state != State.STREAMING || peerPaused != null) return
        try {
            while (true) {
                if (link.bufferedAmount() >= FileV2.IN_FLIGHT_TARGET) return
                if (sentBytes() - ackedBytes() >= FileV2.UNACKED_MAX) return
                if (cursor >= blocksTotal) {
                    // Everything is read; once it is all acknowledged, one last look at the source before `done`.
                    if (ackedBlocks == blocksTotal) {
                        if (!sourceUnchanged()) return
                        sendDone()
                    }
                    return
                }
                if (block == null && !prepareBlock()) return
                emitFrame()
            }
        } catch (error: Throwable) {
            when {
                linkClosed(error) -> suspend()
                error is IOException -> fail("The file could not be read.", FileCodes.INTERNAL)
                else -> throw error
            }
        }
    }

    private fun sentBytes(): Long = minOf(totalSize, cursor.toLong() * FileV2.BLOCK_BYTES + emitted)

    private fun ackedBytes(): Long = minOf(totalSize, ackedBlocks.toLong() * FileV2.BLOCK_BYTES)

    /** Reads and hashes block [cursor] straight from the source; false if the transfer ended instead. */
    private fun prepareBlock(): Boolean {
        if (!sourceUnchanged()) return false
        val offset = cursor.toLong() * FileV2.BLOCK_BYTES
        val length = minOf(FileV2.BLOCK_BYTES.toLong(), totalSize - offset).toInt()
        val input = stream ?: source.open(offset).also { stream = it }
        val buffer = block?.takeIf { it.size >= length } ?: ByteArray(FileV2.BLOCK_BYTES)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) break
            read += n
        }
        if (read < length) {
            sourceChanged()
            return false
        }
        val digest = MessageDigest.getInstance("SHA-256").apply { update(buffer, 0, length) }.digest()
        block = buffer
        blockLength = length
        blockHash = digest
        if (hashes.size == cursor) hashes.add(digest)
        emitted = 0
        return true
    }

    private fun emitFrame() {
        val data = requireNotNull(block)
        val count = minOf(FileV2.payloadCapacity(frameBytes), blockLength - emitted)
        val last = emitted + count == blockLength
        link.sendFrame(
            FileFrame.encode(uuid, pass, cursor, cursor.toLong() * FileV2.BLOCK_BYTES + emitted, data, emitted, count, if (last) blockHash else null),
        )
        emitted += count
        if (last) {
            cursor++
            block = null
            emitted = 0
        }
        report(force = false)
    }

    private fun sendDone() {
        if (state == State.AWAITING_COMPLETE) return
        state = State.AWAITING_COMPLETE
        send(control(WebRtcFileAction.DONE).copy(fileHash = FileHashes.fileHash(hashes), blocks = blocksTotal, totalSize = totalSize))
    }

    private fun sourceUnchanged(): Boolean {
        val stat = source.stat()
        if (stat == null) {
            finish(Outcome.FAILED, "The file no longer exists.", control(WebRtcFileAction.CANCEL).copy(code = FileCodes.SOURCE_GONE, message = "The file no longer exists."), keep = false)
            return false
        }
        if (stat.size != totalSize || stat.mtime != mtime) {
            sourceChanged()
            return false
        }
        return true
    }

    private fun sourceChanged() = finish(
        Outcome.SOURCE_CHANGED, "The file changed while it was being sent.",
        control(WebRtcFileAction.CANCEL).copy(code = FileCodes.SOURCE_CHANGED, message = "The file changed while it was being sent."), keep = false,
    )

    // -------------------------------------------------------------- clock

    fun tick(now: Long) {
        if (ended) return
        saveCheckpoint(force = false)
        if (peerPaused == null && !noProgress && now - lastProgressAt >= FileV2.NO_PROGRESS_MS) {
            noProgress = true
            send(control(WebRtcFileAction.PAUSE).copy(reason = FileCodes.NO_PROGRESS))
        }
        pump() // a wake-up for a window that opened without an event
    }

    private fun touch() {
        lastProgressAt = clock()
        if (noProgress) {
            noProgress = false
            send(control(WebRtcFileAction.RESUME))
        }
    }

    private fun report(force: Boolean) {
        val now = clock()
        if (!force && now - lastReportAt < REPORT_INTERVAL_MS) return
        lastReportAt = now
        listener.progress(this, minOf(totalSize, ackedBytes() + if (block != null) emitted else 0))
    }

    // ------------------------------------------------------- persistence

    private fun saveCheckpoint(force: Boolean) {
        val now = clock()
        val due = force || unsavedBlocks >= FileV2.CHECKPOINT_BLOCKS || (unsavedBlocks > 0 && now - lastCheckpointAt >= FileV2.CHECKPOINT_MS)
        if (!due) return
        // Every hash read so far, not only the acknowledged ones: the receiver may have verified blocks whose
        // acknowledgement was still on the wire when the link dropped, and a resume must agree with it.
        val read = hashes.toList()
        // Never an empty sidecar over a stored one: a sender that has read nothing yet has nothing to add.
        if ((read.isEmpty() || store.writeBlocks(transferId, read)) &&
            store.save(
                FileCheckpoint(
                    transferId, "send", token, wire.peerDeviceId, filename, totalSize, mtime, ackedBlocks,
                    sourceUri = source.persistentUri, spooled = spooled, meta = meta,
                ),
            )
        ) unsavedBlocks = 0
        lastCheckpointAt = now
    }

    /** The link went away: what is acknowledged is kept (to the block) for the next connection. */
    fun suspend() {
        if (ended) return
        saveCheckpoint(force = true)
        close()
        ended = true
        state = State.ENDED
        listener.ended(this, Outcome.SUSPENDED, null)
    }

    /** The person (or the Bucket) cancelled it. */
    fun cancel(code: String, message: String) =
        finish(Outcome.CANCELLED, message, control(WebRtcFileAction.CANCEL).copy(code = code, message = message), keep = false)

    private fun fail(message: String, code: String) =
        finish(Outcome.FAILED, message, control(WebRtcFileAction.ERROR).copy(code = code, message = message), keep = false)

    private fun finish(outcome: Outcome, message: String?, tellPeer: WebRtcFileControl?, keep: Boolean) {
        if (ended) return
        ended = true
        state = State.ENDED
        close()
        tellPeer?.let { runCatching { link.sendControl(it) } }
        if (!keep) store.remove(transferId)
        listener.ended(this, outcome, message)
    }

    private fun close() {
        runCatching { stream?.close() }
        stream = null
        block = null
    }

    private fun send(control: WebRtcFileControl) {
        try {
            link.sendControl(control)
        } catch (error: Throwable) {
            if (linkClosed(error)) suspend() else throw error
        }
    }

    private fun control(action: WebRtcFileAction) = WebRtcFileControl(
        action = action, transferId = transferId, deviceId = wire.senderDeviceId, sessionId = wire.sessionId,
        connectionGeneration = wire.generation, transferToken = token,
    )

    companion object {
        const val REPORT_INTERVAL_MS = 250L
    }
}
