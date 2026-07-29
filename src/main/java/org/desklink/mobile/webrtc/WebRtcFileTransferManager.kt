/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import android.content.Context
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.helpers.TransferCheckpoint
import org.desklink.mobile.helpers.TransferCheckpointStore
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
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

/** Bounded, checkpointed Android side of the DeskLink WebRTC file protocol. */
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

    private sealed interface ActiveTransfer {
        val wire: WebRtcWireBinding
        val checkpoint: TransferCheckpoint

        data class Sender(
            override val wire: WebRtcWireBinding,
            override var checkpoint: TransferCheckpoint,
            val packet: NetworkPacket,
            val callback: Device.SendPacketStatusCallback,
            val completion: CompletableFuture<Boolean> = CompletableFuture(),
            @Volatile var awaitingOffset: Long? = null,
        ) : ActiveTransfer

        data class Receiver(
            override val wire: WebRtcWireBinding,
            override var checkpoint: TransferCheckpoint,
        ) : ActiveTransfer
    }

    fun sendPayload(
        wire: WebRtcWireBinding,
        packet: NetworkPacket,
        callback: Device.SendPacketStatusCallback,
    ): Boolean {
        val payload = requireNotNull(packet.payload) { "File packet has no payload" }
        val transferId = packet.getStringOrNull("transferId") ?: UUID.randomUUID().toString()
        val filename = packet.getString("filename").also(::validateFilename)
        val token = UUID.randomUUID().toString()
        val source = File(transferRoot, ".desklink-$transferId.send")
        require(!source.exists()) { "Transfer ID is already active" }
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
        check(checkpointStore.save(checkpoint)) { "Could not persist transfer checkpoint" }
        val sender = ActiveTransfer.Sender(wire, checkpoint, packet, callback)
        check(active.putIfAbsent(transferId, sender) == null) { "Transfer ID is already active" }
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
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30)
        var result = false
        try {
            while (true) {
                if (packet.isCanceled) {
                    cancel(sender, "File transfer was cancelled")
                    break
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    fail(sender, "File transfer timed out")
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

    @Synchronized
    fun handleControl(wire: WebRtcWireBinding, message: WebRtcFileControl) {
        message.validate(wire)
        when (message.action) {
            WebRtcFileAction.OFFER -> receiveOffer(wire, message)
            WebRtcFileAction.ACCEPT -> acceptSender(wire, message)
            WebRtcFileAction.ACKNOWLEDGE -> acknowledgeSender(wire, message)
            WebRtcFileAction.COMPLETE -> completeSender(wire, message)
            WebRtcFileAction.CANCEL -> finishRemote(message, cancelled = true)
            WebRtcFileAction.ERROR -> finishRemote(message, cancelled = false)
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
        require(receiver.checkpoint.offset == chunk.offset) {
            "WebRTC file chunk has the wrong offset"
        }
        val nextOffset = Math.addExact(chunk.offset, chunk.data.size.toLong())
        require(nextOffset <= receiver.checkpoint.totalSize) {
            "WebRTC file chunk exceeds the announced size"
        }
        val partial = File(java.net.URI(receiver.checkpoint.uri))
        require(!java.nio.file.Files.isSymbolicLink(partial.toPath())) {
            "Refusing a symlink transfer path"
        }
        require(partial.length() == receiver.checkpoint.offset) {
            "Partial file size does not match its checkpoint"
        }
        RandomAccessFile(partial, "rw").use { file ->
            file.seek(chunk.offset)
            file.write(chunk.data)
            file.fd.sync()
        }
        receiver.checkpoint = receiver.checkpoint.copy(
            offset = nextOffset,
            state = "transferring",
        )
        check(checkpointStore.save(receiver.checkpoint)) {
            "Could not persist incoming transfer checkpoint"
        }
        send(
            OutboundWebRtcFileMessage.Control(
                control(
                    wire,
                    WebRtcFileAction.ACKNOWLEDGE,
                    receiver.checkpoint,
                    offset = nextOffset,
                ),
            ),
        )
        if (nextOffset == receiver.checkpoint.totalSize) finalizeReceiver(receiver, partial)
    }

    @Synchronized
    fun close(reason: String) {
        active.values.forEach { transfer ->
            checkpointStore.save(transfer.checkpoint.copy(state = "paused"))
            if (transfer is ActiveTransfer.Sender) {
                transfer.callback.onFailure(IllegalStateException(reason))
                transfer.completion.complete(false)
            }
        }
        active.clear()
    }

    @Synchronized
    fun resumeSends(wire: WebRtcWireBinding) {
        checkpointStore.list()
            .filter {
                it.direction == "send" &&
                    it.deviceId == wire.peerDeviceId &&
                    it.state !in setOf("completed", "cancelled")
            }
            .forEach { stored ->
                if (active.containsKey(stored.transferId)) return@forEach
                val source = runCatching { File(java.net.URI(stored.uri)) }.getOrNull()
                if (source == null || !source.isFile || source.length() != stored.totalSize ||
                    !sha256(source).equals(stored.sha256, ignoreCase = true)
                ) {
                    checkpointStore.save(stored.copy(state = "failed"))
                    return@forEach
                }
                val checkpoint = stored.copy(
                    offset = 0,
                    state = "pending",
                    sessionId = wire.sessionId,
                    connectionGeneration = wire.generation,
                )
                checkpointStore.save(checkpoint)
                val placeholder = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST)
                val callback = object : Device.SendPacketStatusCallback() {
                    override fun onSuccess() = Unit
                    override fun onFailure(e: Throwable) = Unit
                }
                active[checkpoint.transferId] = ActiveTransfer.Sender(
                    wire,
                    checkpoint,
                    placeholder,
                    callback,
                )
                send(
                    OutboundWebRtcFileMessage.Control(
                        control(
                            wire,
                            WebRtcFileAction.OFFER,
                            checkpoint,
                            filename = requireNotNull(checkpoint.filename),
                            totalSize = checkpoint.totalSize,
                            sha256 = requireNotNull(checkpoint.sha256),
                        ),
                    ),
                )
            }
    }

    private fun receiveOffer(wire: WebRtcWireBinding, message: WebRtcFileControl) {
        val filename = requireNotNull(message.filename).also(::validateFilename)
        val totalSize = requireNotNull(message.totalSize)
        val checksum = requireNotNull(message.sha256)
        val previous = checkpointStore.load(message.transferId)
        val checkpoint = if (previous != null) {
            require(
                previous.deviceId == wire.peerDeviceId &&
                    previous.filename == filename &&
                    previous.totalSize == totalSize &&
                    previous.sha256.equals(checksum, ignoreCase = true) &&
                    previous.transferToken == message.transferToken &&
                    previous.direction == "receive"
            ) { "Incoming file offer does not match its checkpoint" }
            val partial = File(java.net.URI(previous.uri))
            require(partial.isFile && partial.length() == previous.offset) {
                "Incoming partial file does not match its checkpoint"
            }
            previous.copy(
                state = "transferring",
                sessionId = wire.sessionId,
                connectionGeneration = wire.generation,
            )
        } else {
            val partial = File(transferRoot, ".desklink-${message.transferId}.part")
            require(partial.createNewFile()) { "Could not create incoming partial file" }
            FileOutputStream(partial).use { it.fd.sync() }
            TransferCheckpoint(
                transferId = message.transferId,
                uri = partial.toURI().toString(),
                offset = 0,
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
        }
        check(checkpointStore.save(checkpoint)) { "Could not persist incoming transfer" }
        val receiver = ActiveTransfer.Receiver(wire, checkpoint)
        active[message.transferId] = receiver
        if (totalSize == 0L) {
            finalizeReceiver(receiver, File(java.net.URI(checkpoint.uri)))
        } else {
            send(
                OutboundWebRtcFileMessage.Control(
                    control(
                        wire,
                        WebRtcFileAction.ACCEPT,
                        checkpoint,
                        offset = checkpoint.offset,
                    ),
                ),
            )
        }
    }

    private fun acceptSender(wire: WebRtcWireBinding, message: WebRtcFileControl) {
        val sender = senderFor(wire, message)
        require(message.offset in 0..sender.checkpoint.totalSize) { "Invalid resume offset" }
        sender.checkpoint = sender.checkpoint.copy(offset = message.offset, state = "transferring")
        check(checkpointStore.save(sender.checkpoint)) { "Could not save sender checkpoint" }
        sendNext(sender)
    }

    private fun acknowledgeSender(wire: WebRtcWireBinding, message: WebRtcFileControl) {
        val sender = senderFor(wire, message)
        require(sender.awaitingOffset == message.offset) {
            "Receiver acknowledgement does not match the sent chunk"
        }
        sender.awaitingOffset = null
        sender.checkpoint = sender.checkpoint.copy(offset = message.offset, state = "transferring")
        check(checkpointStore.save(sender.checkpoint)) { "Could not save sender checkpoint" }
        sender.callback.onPayloadProgressChanged(
            if (sender.checkpoint.totalSize == 0L) 100
            else ((message.offset * 100) / sender.checkpoint.totalSize).toInt(),
        )
        sendNext(sender)
    }

    private fun sendNext(sender: ActiveTransfer.Sender) {
        val checkpoint = sender.checkpoint
        if (checkpoint.offset == checkpoint.totalSize) return
        val source = File(java.net.URI(checkpoint.uri))
        require(source.isFile && source.length() == checkpoint.totalSize) {
            "Shared file changed during transfer"
        }
        val count = minOf(
            WebRtcFileControl.MAX_CHUNK_BYTES.toLong(),
            checkpoint.totalSize - checkpoint.offset,
        ).toInt()
        val data = ByteArray(count)
        RandomAccessFile(source, "r").use { file ->
            file.seek(checkpoint.offset)
            file.readFully(data)
        }
        sender.awaitingOffset = checkpoint.offset + count
        send(
            OutboundWebRtcFileMessage.Chunk(
                WebRtcFileChunk(
                    checkpoint.transferId,
                    requireNotNull(checkpoint.transferToken),
                    checkpoint.offset,
                    data,
                ).encode(),
            ),
        )
    }

    private fun completeSender(wire: WebRtcWireBinding, message: WebRtcFileControl) {
        val sender = senderFor(wire, message)
        require(message.offset == sender.checkpoint.totalSize) { "Completion has wrong size" }
        require(message.sha256.equals(sender.checkpoint.sha256, ignoreCase = true)) {
            "Completion has wrong checksum"
        }
        sender.checkpoint = sender.checkpoint.copy(
            offset = sender.checkpoint.totalSize,
            state = "completed",
        )
        checkpointStore.remove(message.transferId)
        File(java.net.URI(sender.checkpoint.uri)).delete()
        sender.callback.onPayloadProgressChanged(100)
        sender.callback.onSuccess()
        sender.completion.complete(true)
    }

    private fun finishRemote(message: WebRtcFileControl, cancelled: Boolean) {
        val transfer = active.remove(message.transferId) ?: return
        checkpointStore.save(
            transfer.checkpoint.copy(state = if (cancelled) "cancelled" else "failed"),
        )
        if (transfer is ActiveTransfer.Sender) {
            val error = IllegalStateException(
                message.error ?: if (cancelled) "Peer cancelled file transfer" else "Peer rejected file transfer",
            )
            transfer.callback.onFailure(error)
            transfer.completion.complete(false)
        }
    }

    private fun finalizeReceiver(receiver: ActiveTransfer.Receiver, partial: File) {
        require(partial.length() == receiver.checkpoint.totalSize) { "Received file has wrong size" }
        require(sha256(partial).equals(receiver.checkpoint.sha256, ignoreCase = true)) {
            "Received file checksum does not match the sender"
        }
        val packet = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
            this["filename"] = requireNotNull(receiver.checkpoint.filename)
            this["sha256"] = requireNotNull(receiver.checkpoint.sha256)
            payload = NetworkPacket.Payload(
                FileInputStream(partial),
                receiver.checkpoint.totalSize,
            ) { partial.delete() }
        }
        receiver.checkpoint = receiver.checkpoint.copy(
            offset = receiver.checkpoint.totalSize,
            state = "completed",
        )
        checkpointStore.remove(receiver.checkpoint.transferId)
        active.remove(receiver.checkpoint.transferId, receiver)
        device.onWebRtcPacketReceived(packet)
        send(
            OutboundWebRtcFileMessage.Control(
                control(
                    receiver.wire,
                    WebRtcFileAction.COMPLETE,
                    receiver.checkpoint,
                    sha256 = receiver.checkpoint.sha256,
                    offset = receiver.checkpoint.totalSize,
                ),
            ),
        )
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

    private fun cancel(sender: ActiveTransfer.Sender, reason: String) {
        send(
            OutboundWebRtcFileMessage.Control(
                control(
                    sender.wire,
                    WebRtcFileAction.CANCEL,
                    sender.checkpoint,
                    offset = sender.checkpoint.offset,
                ),
            ),
        )
        checkpointStore.save(sender.checkpoint.copy(state = "cancelled"))
        sender.callback.onFailure(IllegalStateException(reason))
        sender.completion.complete(false)
    }

    private fun fail(sender: ActiveTransfer.Sender, reason: String) {
        checkpointStore.save(sender.checkpoint.copy(state = "failed"))
        sender.callback.onFailure(IllegalStateException(reason))
        sender.completion.complete(false)
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
    ) = WebRtcFileControl(
        protocolVersion = WebRtcFileControl.VERSION,
        action = action,
        transferId = checkpoint.transferId,
        deviceId = wire.senderDeviceId,
        sessionId = wire.sessionId,
        connectionGeneration = wire.generation,
        transferToken = requireNotNull(checkpoint.transferToken),
        filename = filename,
        totalSize = totalSize,
        sha256 = sha256,
        offset = offset,
        chunkSize = WebRtcFileControl.MAX_CHUNK_BYTES,
        error = error,
    )

    private fun validateFilename(value: String) {
        require(
            value.isNotEmpty() && value.length <= 255 && value != "." && value != ".." &&
                '/' !in value && '\\' !in value && '\u0000' !in value
        ) { "Unsafe DeskLink transfer filename" }
    }

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
}
