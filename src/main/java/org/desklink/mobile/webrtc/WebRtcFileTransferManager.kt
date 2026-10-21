/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import android.content.Context
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.helpers.TransferCheckpoint
import org.desklink.mobile.helpers.TransferCheckpointStore
import org.desklink.mobile.plugins.share.TransferHistory
import org.desklink.mobile.plugins.share.TransferProgress
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

sealed interface OutboundWebRtcFileMessage {
    data class Control(val value: WebRtcFileControl) : OutboundWebRtcFileMessage
    data class Chunk(val value: ByteArray) : OutboundWebRtcFileMessage
}

/**
 * Bounded, checkpointed Android side of the DeskLink WebRTC file protocol.
 *
 * Every file this class touches lives at a path derived from a *validated*
 * transfer ID (`.desklink-<id>.part` / `.desklink-<id>.send` in the private
 * transfer root). Neither the peer's filename nor a URI read back from the
 * checkpoint store ever selects where bytes are read, written or deleted.
 */
class WebRtcFileTransferManager(
    context: Context,
    private val device: Device,
    private val send: (OutboundWebRtcFileMessage) -> Unit,
) {
    private val appContext = context.applicationContext
    private val checkpointStore = TransferCheckpointStore(appContext)
    private val transferRoot = File(appContext.filesDir, "webrtc-transfers").apply {
        check(mkdirs() || isDirectory) { "Could not create DeskLink transfer directory" }
    }
    private val active = ConcurrentHashMap<String, ActiveTransfer>()

    /** [DURABLE_EVERY_BYTES], except in crash-recovery tests. */
    @androidx.annotation.VisibleForTesting
    internal var durableEveryBytes = DURABLE_EVERY_BYTES

    /** IDs a `sendPayload` call is still setting up, so two calls cannot share one copy. */
    private val sending = ConcurrentHashMap.newKeySet<String>()

    private sealed interface ActiveTransfer {
        val wire: WebRtcWireBinding
        val checkpoint: TransferCheckpoint

        data class Sender(
            override val wire: WebRtcWireBinding,
            override var checkpoint: TransferCheckpoint,
            val packet: NetworkPacket,
            val callback: Device.SendPacketStatusCallback,
            val completion: CompletableFuture<Boolean> = CompletableFuture(),
            /** Highest byte sent; `checkpoint.offset` is the highest acknowledged. */
            @Volatile var awaitingOffset: Long? = null,
            @Volatile var chunkSize: Int = WebRtcFileControl.LEGACY_CHUNK_BYTES,
        ) : ActiveTransfer

        data class Receiver(
            override val wire: WebRtcWireBinding,
            override var checkpoint: TransferCheckpoint,
        ) : ActiveTransfer {
            /**
             * Running SHA-256 fed in order as chunks land, and how far it got.
             * Re-reading the whole file to verify it blocked the peer-event
             * thread for seconds on large files (long enough for the 15 s
             * liveness check to drop a healthy link). A resumed transfer has
             * none and falls back to one full hash. Mirrors the desktop.
             */
            var hasher: java.security.MessageDigest? = null
            var hashedOffset = 0L
        }
    }

    private fun partialFile(transferId: String) = File(transferRoot, ".desklink-$transferId.part")

    private fun sourceFile(transferId: String) = File(transferRoot, ".desklink-$transferId.send")

    // ------------------------------------------------------------------ send

    /**
     * Blocks until the peer completes, cancels or fails the transfer. Every
     * failure is reported through [callback] exactly once and returns false.
     */
    fun sendPayload(
        wire: WebRtcWireBinding,
        packet: NetworkPacket,
        callback: Device.SendPacketStatusCallback,
    ): Boolean {
        val payload = packet.payload ?: run {
            callback.onFailure(IllegalArgumentException("File packet has no payload"))
            return false
        }
        val (transferId, filename) = try {
            val id = (packet.getStringOrNull("transferId") ?: UUID.randomUUID().toString())
                .also(::validateId)
            id to packet.getString("filename").also(::validateFilename)
        } catch (error: Throwable) {
            payload.close()
            callback.onFailure(error)
            return false
        }
        val reserved = sending.add(transferId)
        try {
            if (!reserved || active.containsKey(transferId) || sourceFile(transferId).exists()) {
                payload.close()
                callback.onFailure(IllegalArgumentException("Transfer ID is already active"))
                return false
            }
            return runSend(wire, packet, callback, payload, transferId, filename)
        } finally {
            if (reserved) sending.remove(transferId)
        }
    }

    private fun runSend(
        wire: WebRtcWireBinding,
        packet: NetworkPacket,
        callback: Device.SendPacketStatusCallback,
        payload: NetworkPacket.Payload,
        transferId: String,
        filename: String,
    ): Boolean {
        val token = UUID.randomUUID().toString()
        val source = sourceFile(transferId)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            FileOutputStream(source).use { output ->
                val buffer = ByteArray(64 * 1024)
                val input = requireNotNull(payload.inputStream) { "File payload stream is unavailable" }
                while (true) {
                    if (packet.isCanceled) error("File transfer was cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                    require(size <= WebRtcFileControl.MAX_TRANSFER_BYTES) {
                        "File exceeds the DeskLink transfer limit"
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        } catch (error: Throwable) {
            source.delete()
            payload.close()
            callback.onFailure(error)
            return false
        }
        payload.close()
        TransferHistory.peekSource(packet)?.let { TransferHistory.rememberResumable(appContext, transferId, it) }
        val checksum = digest.digest().toHex()
        val checkpoint = TransferCheckpoint(
            transferId = transferId,
            uri = source.toURI().toString(),
            offset = 0,
            totalSize = size,
            state = "pending",
            filename = filename,
            deviceId = wire.peerDeviceId,
            sha256 = checksum,
            transferToken = token,
            sessionId = wire.sessionId,
            connectionGeneration = wire.generation,
            direction = "send",
        )
        val sender = ActiveTransfer.Sender(wire, checkpoint, packet, callback)
        try {
            check(active.putIfAbsent(transferId, sender) == null) { "Transfer ID is already active" }
            check(checkpointStore.save(checkpoint)) { "Could not persist transfer checkpoint" }
            send(
                OutboundWebRtcFileMessage.Control(
                    control(
                        wire,
                        WebRtcFileAction.OFFER,
                        checkpoint,
                        filename = filename,
                        totalSize = size,
                        sha256 = checksum,
                    ),
                ),
            )
        } catch (error: Throwable) {
            // The offer never left, so there is nothing to resume: leave no copy behind.
            active.remove(transferId, sender)
            discardSend(transferId)
            callback.onFailure(error)
            return false
        }
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30)
        var result = false
        try {
            while (true) {
                if (packet.isCanceled) {
                    endSender(sender, WebRtcFileAction.CANCEL, "File transfer was cancelled")
                    break
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    endSender(sender, WebRtcFileAction.ERROR, "File transfer timed out")
                    break
                }
                try {
                    result = sender.completion.get(
                        minOf(remaining, TimeUnit.MILLISECONDS.toNanos(250)),
                        TimeUnit.NANOSECONDS,
                    )
                    break
                } catch (_: TimeoutException) {
                    // Check cancellation and the bounded transfer deadline again.
                }
            }
        } finally {
            active.remove(transferId, sender)
        }
        return result
    }

    // --------------------------------------------------------------- inbound

    @Synchronized
    fun handleControl(wire: WebRtcWireBinding, message: WebRtcFileControl) {
        message.validate(wire)
        when (message.action) {
            WebRtcFileAction.OFFER -> answeringRefusalOf(wire, message) { receiveOffer(wire, message) }
            WebRtcFileAction.ACCEPT -> acceptSender(wire, message)
            WebRtcFileAction.ACKNOWLEDGE -> acknowledgeSender(wire, message)
            WebRtcFileAction.COMPLETE -> completeSender(wire, message)
            WebRtcFileAction.CANCEL -> finishRemote(wire, message, cancelled = true)
            WebRtcFileAction.ERROR -> finishRemote(wire, message, cancelled = false)
        }
    }

    @Synchronized
    fun handleChunk(wire: WebRtcWireBinding, encoded: ByteArray) {
        val chunk = WebRtcFileChunk.decode(encoded)
        val receiver = active[chunk.transferId] as? ActiveTransfer.Receiver
            ?: error("WebRTC file chunk has no active offer")
        require(receiver.wire == wire) { "WebRTC file chunk has a stale session binding" }
        require(receiver.checkpoint.transferToken == chunk.transferToken) {
            "WebRTC file chunk has the wrong transfer token"
        }
        // From here the peer has proved it holds this transfer, so a failure is
        // ours to report: the sender is waiting for an acknowledgement that a
        // full disk or a bad chunk means it will never get.
        try {
            writeChunk(wire, receiver, chunk)
        } catch (error: Throwable) {
            failReceive(receiver, error.message ?: "Could not store the received data")
            throw error
        }
    }

    private fun writeChunk(wire: WebRtcWireBinding, receiver: ActiveTransfer.Receiver, chunk: WebRtcFileChunk) {
        require(receiver.checkpoint.offset == chunk.offset) {
            "WebRTC file chunk has the wrong offset"
        }
        val nextOffset = Math.addExact(chunk.offset, chunk.data.size.toLong())
        require(nextOffset <= receiver.checkpoint.totalSize) {
            "WebRTC file chunk exceeds the announced size"
        }
        val partial = partialFile(chunk.transferId)
        require(!Files.isSymbolicLink(partial.toPath())) { "Refusing a symlink transfer path" }
        require(partial.length() == receiver.checkpoint.offset) {
            "Partial file size does not match its checkpoint"
        }
        val updated = receiver.checkpoint.copy(offset = nextOffset, state = "transferring")
        // Durability is batched like the desktop's: sync and commit the
        // checkpoint every DURABLE_EVERY_BYTES (and at the end), not per chunk.
        // A crash costs at most that much, which resume re-sends.
        val durable = crossesDurabilityBoundary(chunk.offset, nextOffset, updated.totalSize)
        try {
            RandomAccessFile(partial, "rw").use { file ->
                file.seek(chunk.offset)
                file.write(chunk.data)
                if (durable) file.fd.sync()
            }
            if (chunk.offset == 0L) {
                receiver.hasher = java.security.MessageDigest.getInstance("SHA-256")
                receiver.hashedOffset = 0L
            }
            if (receiver.hashedOffset == chunk.offset) {
                receiver.hasher?.update(chunk.data)
                receiver.hashedOffset = nextOffset
            } else {
                receiver.hasher = null
            }
            if (durable) check(checkpointStore.save(updated)) { "Could not persist incoming transfer checkpoint" }
        } catch (error: Throwable) {
            // Keep the partial file equal to the checkpoint, or the peer's retry of
            // this very chunk could never be accepted again.
            runCatching {
                RandomAccessFile(partial, "rw").use { it.setLength(receiver.checkpoint.offset) }
            }
            throw error
        }
        receiver.checkpoint = updated
        send(
            OutboundWebRtcFileMessage.Control(
                control(wire, WebRtcFileAction.ACKNOWLEDGE, updated, offset = nextOffset),
            ),
        )
        if (nextOffset == updated.totalSize) finalizeReceiver(receiver)
    }

    /** Ends an incoming transfer that cannot go on, and tells the sender why. */
    private fun failReceive(receiver: ActiveTransfer.Receiver, reason: String) {
        val id = receiver.checkpoint.transferId
        if (!active.remove(id, receiver)) return
        discardReceive(id)
        runCatching {
            send(
                OutboundWebRtcFileMessage.Control(
                    control(receiver.wire, WebRtcFileAction.ERROR, receiver.checkpoint, error = reason.take(256)),
                ),
            )
        }
    }

    /**
     * A refused offer for a transfer with no state here is answered, or the
     * sender waits for an accept that will never come. An offer that disagrees
     * with a checkpoint already stored is a stale or forged message and is not.
     */
    private fun answeringRefusalOf(wire: WebRtcWireBinding, message: WebRtcFileControl, block: () -> Unit) {
        val known = runCatching { checkpointStore.load(message.transferId) != null }.getOrDefault(true)
        try {
            block()
        } catch (error: Throwable) {
            if (!known) {
                runCatching {
                    send(
                        OutboundWebRtcFileMessage.Control(
                            controlFor(
                                wire,
                                WebRtcFileAction.ERROR,
                                message.transferId,
                                message.transferToken,
                                error = (error.message ?: "The file could not be received").take(256),
                            ),
                        ),
                    )
                }
            }
            throw error
        }
    }

    /**
     * Runs a step of an outgoing transfer the peer has proved it holds. If it
     * fails the send ends and the receiver is told, instead of both sides waiting.
     */
    private fun failingSendWith(sender: ActiveTransfer.Sender, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            endSender(sender, WebRtcFileAction.ERROR, error.message ?: "The transfer failed")
            throw error
        }
    }

    /**
     * The session ended. Checkpoints are kept (paused) so the next connection resumes them, and a
     * send's owner hears [TransferResumingException], not a failure: it shows "resuming".
     */
    @Synchronized
    fun close(reason: String) {
        val transfers = active.values.toList()
        active.clear()
        transfers.forEach { transfer ->
            checkpointStore.save(transfer.checkpoint.copy(state = "paused"))
            if (transfer is ActiveTransfer.Sender) {
                transfer.callback.onFailure(TransferResumingException(reason))
                transfer.completion.complete(false)
            }
        }
    }

    @Synchronized
    fun resumeSends(wire: WebRtcWireBinding) {
        checkpointStore.list()
            .filter { it.direction == "send" && it.deviceId == wire.peerDeviceId }
            .forEach { stored ->
                val id = stored.transferId
                if (active.containsKey(id) || runCatching { validateId(id) }.isFailure) return@forEach
                // Only a session that ended under the transfer is resumable. Anything a
                // peer or the user ended (or that failed) is finished: reclaim its copy.
                val source = sourceFile(id)
                val filename = stored.filename
                val checksum = stored.sha256
                if (stored.state !in RESUMABLE_STATES || filename == null || checksum == null ||
                    stored.transferToken == null || !source.isFile ||
                    source.length() != stored.totalSize ||
                    !sha256(source).equals(checksum, ignoreCase = true)
                ) {
                    discardSend(id)
                    return@forEach
                }
                val checkpoint = stored.copy(
                    uri = source.toURI().toString(),
                    offset = 0,
                    state = "pending",
                    sessionId = wire.sessionId,
                    connectionGeneration = wire.generation,
                )
                checkpointStore.save(checkpoint)
                val callback = resumedSendCallback(wire.peerDeviceId, id, filename, checkpoint.totalSize)
                val sender = ActiveTransfer.Sender(
                    wire,
                    checkpoint,
                    NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST),
                    callback,
                )
                active[id] = sender
                try {
                    send(
                        OutboundWebRtcFileMessage.Control(
                            control(
                                wire,
                                WebRtcFileAction.OFFER,
                                checkpoint,
                                filename = filename,
                                totalSize = checkpoint.totalSize,
                                sha256 = checksum,
                            ),
                        ),
                    )
                } catch (error: Throwable) {
                    active.remove(id, sender) // stays "pending": the next handover retries
                    throw error
                }
            }
    }

    // --------------------------------------------------------------- receive

    private fun receiveOffer(wire: WebRtcWireBinding, message: WebRtcFileControl) {
        val id = message.transferId
        val filename = requireNotNull(message.filename)
        val totalSize = requireNotNull(message.totalSize)
        val checksum = requireNotNull(message.sha256)
        val stored = checkpointStore.load(id)
        require(stored?.direction != "send") { "Incoming file offer collides with an outgoing transfer" }
        if (active[id] !is ActiveTransfer.Receiver &&
            active.values.count { it is ActiveTransfer.Receiver } >= MAX_INCOMING_TRANSFERS
        ) {
            val reason = "Too many simultaneous incoming transfers"
            // Tell the peer, or it waits for an accept that will never come.
            runCatching {
                send(
                    OutboundWebRtcFileMessage.Control(
                        controlFor(wire, WebRtcFileAction.ERROR, id, message.transferToken, error = reason),
                    ),
                )
            }
            throw IllegalArgumentException(reason)
        }
        // Within this process the live checkpoint runs ahead of the batched
        // stored one, and the partial file really holds those bytes.
        val live = (active[id] as? ActiveTransfer.Receiver)?.checkpoint
            ?.takeIf { it.transferToken == message.transferToken }
        val previous = live ?: stored?.takeIf { it.isUsableReceive() }
        if (previous != null) {
            require(
                previous.deviceId == wire.peerDeviceId &&
                    previous.filename == filename &&
                    previous.totalSize == totalSize &&
                    previous.sha256.equals(checksum, ignoreCase = true) &&
                    previous.transferToken == message.transferToken,
            ) { "Incoming file offer does not match its checkpoint" }
        }
        val partial = partialFile(id)
        val offset = if (previous != null) {
            verifiedOffset(partial, previous.offset)
        } else {
            // No checkpoint, or one too damaged to trust: start over. A partial file
            // with no checkpoint entry at all is never overwritten.
            if (checkpointStore.exists(id)) discardReceive(id)
            require(partial.createNewFile()) { "Could not create incoming partial file" }
            FileOutputStream(partial).use { it.fd.sync() }
            0L
        }
        val checkpoint = TransferCheckpoint(
            transferId = id,
            uri = partial.toURI().toString(),
            offset = offset,
            totalSize = totalSize,
            state = "transferring",
            filename = filename,
            deviceId = wire.peerDeviceId,
            sha256 = checksum,
            transferToken = message.transferToken,
            sessionId = wire.sessionId,
            connectionGeneration = wire.generation,
            direction = "receive",
        )
        try {
            check(checkpointStore.save(checkpoint)) { "Could not persist incoming transfer" }
        } catch (error: Throwable) {
            if (previous == null) partial.delete()
            throw error
        }
        val receiver = ActiveTransfer.Receiver(wire, checkpoint)
        active[id] = receiver
        if (offset == totalSize) {
            // Empty file, or everything arrived before an earlier finalisation was
            // interrupted: there are no chunks left to wait for.
            finalizeReceiver(receiver)
        } else {
            send(
                OutboundWebRtcFileMessage.Control(
                    control(wire, WebRtcFileAction.ACCEPT, checkpoint, offset = offset).copy(
                        // Only a sender that offered the upgrade is told more.
                        maxChunkSize = message.maxChunkSize?.coerceAtMost(WebRtcFileControl.MAX_CHUNK_BYTES),
                    ),
                ),
            )
        }
    }

    /**
     * The checkpoint is authoritative for what the peer has been acknowledged:
     * bytes written after it (a crash between the chunk's fsync and its checkpoint
     * commit) are dropped, and a partial file that lost acknowledged bytes restarts
     * from zero.
     */
    private fun verifiedOffset(partial: File, checkpointOffset: Long): Long {
        require(!Files.isSymbolicLink(partial.toPath())) { "Refusing a symlink transfer path" }
        if (!partial.exists()) {
            require(partial.createNewFile()) { "Could not create incoming partial file" }
            return 0
        }
        require(partial.isFile) { "Incoming partial file is not a regular file" }
        val length = partial.length()
        val keep = if (length >= checkpointOffset) checkpointOffset else 0L
        if (length != keep) {
            RandomAccessFile(partial, "rw").use {
                it.setLength(keep)
                it.fd.sync()
            }
        }
        return keep
    }

    private fun TransferCheckpoint.isUsableReceive() =
        direction == "receive" && deviceId != null && filename != null && sha256 != null &&
            transferToken != null && totalSize in 0..WebRtcFileControl.MAX_TRANSFER_BYTES &&
            offset in 0..totalSize

    private fun finalizeReceiver(receiver: ActiveTransfer.Receiver) {
        val checkpoint = receiver.checkpoint
        val partial = partialFile(checkpoint.transferId)
        val problem = runCatching {
            when {
                partial.length() != checkpoint.totalSize -> "Received file has wrong size"
                !(
                    receiver.hasher
                        ?.takeIf { receiver.hashedOffset == checkpoint.totalSize }
                        ?.digest()?.toHex()
                        ?: sha256(partial)
                    ).equals(checkpoint.sha256, ignoreCase = true) ->
                    "Received file checksum does not match the sender"
                else -> null
            }
        }.getOrDefault("Could not verify the received file")
        if (problem != null) {
            // Never publish it, and never leave it to wedge the transfer ID.
            active.remove(checkpoint.transferId, receiver)
            discardReceive(checkpoint.transferId)
            runCatching {
                send(
                    OutboundWebRtcFileMessage.Control(
                        control(receiver.wire, WebRtcFileAction.ERROR, checkpoint, error = problem),
                    ),
                )
            }
            throw IllegalArgumentException(problem)
        }
        val packet = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
            this["filename"] = requireNotNull(checkpoint.filename)
            this["sha256"] = requireNotNull(checkpoint.sha256)
            payload = NetworkPacket.Payload(
                FileInputStream(partial),
                checkpoint.totalSize,
            ) { partial.delete() }
        }
        receiver.checkpoint = checkpoint.copy(offset = checkpoint.totalSize, state = "completed")
        checkpointStore.remove(checkpoint.transferId)
        active.remove(checkpoint.transferId, receiver)
        try {
            device.onWebRtcPacketReceived(packet)
        } catch (error: Throwable) {
            packet.payload?.close() // deletes the partial file
            runCatching {
                send(
                    OutboundWebRtcFileMessage.Control(
                        control(
                            receiver.wire,
                            WebRtcFileAction.ERROR,
                            checkpoint,
                            error = "Could not deliver the received file",
                        ),
                    ),
                )
            }
            throw error
        }
        send(
            OutboundWebRtcFileMessage.Control(
                control(
                    receiver.wire,
                    WebRtcFileAction.COMPLETE,
                    receiver.checkpoint,
                    sha256 = checkpoint.sha256,
                    offset = checkpoint.totalSize,
                ),
            ),
        )
    }

    private fun discardReceive(transferId: String) {
        partialFile(transferId).delete()
        checkpointStore.remove(transferId)
    }

    // ---------------------------------------------------- sender state machine

    private fun acceptSender(wire: WebRtcWireBinding, message: WebRtcFileControl) {
        val sender = senderFor(wire, message)
        failingSendWith(sender) {
            require(message.offset in 0..sender.checkpoint.totalSize) { "Invalid resume offset" }
            sender.checkpoint = sender.checkpoint.copy(offset = message.offset, state = "transferring")
            sender.awaitingOffset = null
            sender.chunkSize = message.maxChunkSize
                ?.coerceIn(WebRtcFileControl.LEGACY_CHUNK_BYTES, WebRtcFileControl.MAX_CHUNK_BYTES)
                ?: WebRtcFileControl.LEGACY_CHUNK_BYTES
            check(checkpointStore.save(sender.checkpoint)) { "Could not save sender checkpoint" }
            fillSendWindow(sender)
        }
    }

    private fun acknowledgeSender(wire: WebRtcWireBinding, message: WebRtcFileControl) {
        val sender = senderFor(wire, message)
        failingSendWith(sender) {
            // Cumulative: anything between what was acknowledged and what was
            // sent. An ack already covered by a later one is ignored, not fatal.
            val sent = sender.awaitingOffset ?: sender.checkpoint.offset
            require(message.offset <= sent) { "Receiver acknowledgement does not match the sent chunk" }
            if (message.offset <= sender.checkpoint.offset) return@failingSendWith
            val previous = sender.checkpoint.offset
            sender.checkpoint = sender.checkpoint.copy(offset = message.offset, state = "transferring")
            // On resume the receiver's own offset decides; this only needs to
            // be roughly current.
            if (crossesDurabilityBoundary(previous, message.offset, sender.checkpoint.totalSize)) {
                check(checkpointStore.save(sender.checkpoint)) { "Could not save sender checkpoint" }
            }
            sender.callback.onPayloadProgressChanged(
                if (sender.checkpoint.totalSize == 0L) 100
                else ((message.offset * 100) / sender.checkpoint.totalSize).toInt(),
            )
            fillSendWindow(sender)
        }
    }

    /**
     * Keeps up to SEND_WINDOW_BYTES unacknowledged. Stop-and-wait (one chunk
     * per round trip plus the receiver's syncs) capped transfers near 1 MB/s.
     * Older receivers handle this unchanged: they take the ordered channel one
     * chunk at a time and acknowledge each.
     */
    private fun fillSendWindow(sender: ActiveTransfer.Sender) {
        val checkpoint = sender.checkpoint
        val source = sourceFile(checkpoint.transferId)
        try {
            require(source.isFile && source.length() == checkpoint.totalSize) {
                "Shared file changed during transfer"
            }
            RandomAccessFile(source, "r").use { file ->
                while (true) {
                    val sent = sender.awaitingOffset ?: checkpoint.offset
                    if (sent >= checkpoint.totalSize || sent - checkpoint.offset >= SEND_WINDOW_BYTES) break
                    val count = minOf(sender.chunkSize.toLong(), checkpoint.totalSize - sent).toInt()
                    val data = ByteArray(count)
                    file.seek(sent)
                    file.readFully(data)
                    send(
                        OutboundWebRtcFileMessage.Chunk(
                            WebRtcFileChunk(
                                checkpoint.transferId,
                                requireNotNull(checkpoint.transferToken),
                                sent,
                                data,
                            ).encode(),
                        ),
                    )
                    sender.awaitingOffset = sent + count
                }
            }
        } catch (error: Throwable) {
            // Fail now and tell the peer; waiting out the 30-minute deadline helps no one.
            endSender(
                sender,
                WebRtcFileAction.ERROR,
                error.message ?: "Shared file changed during transfer",
            )
        }
    }

    private fun completeSender(wire: WebRtcWireBinding, message: WebRtcFileControl) {
        val sender = senderFor(wire, message)
        failingSendWith(sender) {
            require(message.offset == sender.checkpoint.totalSize) { "Completion has wrong size" }
            require(message.sha256.equals(sender.checkpoint.sha256, ignoreCase = true)) {
                "Completion has wrong checksum"
            }
        }
        if (active.remove(message.transferId, sender)) {
            discardSend(message.transferId)
            sender.callback.onPayloadProgressChanged(100)
            sender.callback.onSuccess()
            sender.completion.complete(true)
        }
    }

    /** The peer ended the transfer (Cancel or Error); nothing is resumed afterwards. */
    private fun finishRemote(wire: WebRtcWireBinding, message: WebRtcFileControl, cancelled: Boolean) {
        val id = message.transferId
        val transfer = active[id]
        if (transfer == null) {
            // Not live here, but a checkpoint from an earlier session may still
            // be waiting to resume; the peer's word ends it too.
            val stored = checkpointStore.load(id) ?: return
            if (stored.deviceId != wire.peerDeviceId) return
            require(stored.transferToken == message.transferToken) { "WebRTC transfer message has the wrong token" }
            if (stored.direction == "send") discardSend(id) else discardReceive(id)
            return
        }
        require(transfer.wire == wire) { "WebRTC transfer message has a stale binding" }
        require(transfer.checkpoint.transferToken == message.transferToken) {
            "WebRTC transfer message has the wrong token"
        }
        if (!active.remove(id, transfer)) return
        if (transfer is ActiveTransfer.Sender) {
            discardSend(id)
            transfer.callback.onFailure(
                IllegalStateException(
                    message.error ?: if (cancelled) "Peer cancelled file transfer" else "Peer rejected file transfer",
                ),
            )
            transfer.completion.complete(false)
        } else {
            discardReceive(id)
        }
    }

    private fun senderFor(
        wire: WebRtcWireBinding,
        message: WebRtcFileControl,
    ): ActiveTransfer.Sender {
        val sender = active[message.transferId] as? ActiveTransfer.Sender
            ?: error("WebRTC transfer response has no active sender")
        require(sender.wire == wire) { "WebRTC transfer response has a stale binding" }
        require(sender.checkpoint.transferToken == message.transferToken) {
            "WebRTC transfer response has the wrong token"
        }
        return sender
    }

    /** Ends a send this side can no longer continue, and tells the peer why. */
    private fun endSender(sender: ActiveTransfer.Sender, action: WebRtcFileAction, reason: String) {
        val id = sender.checkpoint.transferId
        if (!active.remove(id, sender)) return
        runCatching {
            send(
                OutboundWebRtcFileMessage.Control(
                    control(sender.wire, action, sender.checkpoint, error = reason.take(4096)),
                ),
            )
        }
        discardSend(id)
        sender.callback.onFailure(IllegalStateException(reason))
        sender.completion.complete(false)
    }

    private fun discardSend(transferId: String) {
        sourceFile(transferId).delete()
        checkpointStore.remove(transferId)
        TransferHistory.forgetResumable(appContext, transferId)
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

    private fun control(
        wire: WebRtcWireBinding,
        action: WebRtcFileAction,
        checkpoint: TransferCheckpoint,
        filename: String? = null,
        totalSize: Long? = null,
        sha256: String? = null,
        offset: Long = checkpoint.offset,
        error: String? = null,
    ) = controlFor(
        wire,
        action,
        checkpoint.transferId,
        requireNotNull(checkpoint.transferToken),
        offset,
        filename,
        totalSize,
        sha256,
        error,
    )

    private fun controlFor(
        wire: WebRtcWireBinding,
        action: WebRtcFileAction,
        transferId: String,
        token: String,
        offset: Long = 0,
        filename: String? = null,
        totalSize: Long? = null,
        sha256: String? = null,
        error: String? = null,
    ) = WebRtcFileControl(
        protocolVersion = WebRtcFileControl.VERSION,
        action = action,
        transferId = transferId,
        deviceId = wire.senderDeviceId,
        sessionId = wire.sessionId,
        connectionGeneration = wire.generation,
        transferToken = token,
        filename = filename,
        totalSize = totalSize,
        sha256 = sha256,
        offset = offset,
        chunkSize = WebRtcFileControl.LEGACY_CHUNK_BYTES,
        error = error,
        maxChunkSize = if (action == WebRtcFileAction.OFFER) WebRtcFileControl.MAX_CHUNK_BYTES else null,
    )

    private fun crossesDurabilityBoundary(from: Long, to: Long, total: Long): Boolean =
        to == total || from / durableEveryBytes != to / durableEveryBytes

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        /** Job ids for resumed sends, apart from the share plugin's own. */
        private const val RESUMED_JOB_BASE = 1L shl 40

        /** Most incoming transfers one peer may hold open at once. The desktop
         * uses its own bound; this one only has to stop a peer from making the
         * phone allocate a partial file per invented transfer ID. */
        const val MAX_INCOMING_TRANSFERS = 8
        /** Unacknowledged bytes in flight; matches the desktop. */
        const val SEND_WINDOW_BYTES = 1024L * 1024
        /** Sync + checkpoint interval; matches the desktop. */
        const val DURABLE_EVERY_BYTES = 8L * 1024 * 1024

        /** Sender states left by a session that ended under the transfer. */
        private val RESUMABLE_STATES = setOf("pending", "transferring", "paused")
    }
}

/** The link went away mid-send; the checkpoint is kept and the next connection resumes it. */
class TransferResumingException(reason: String) : IllegalStateException(reason)
