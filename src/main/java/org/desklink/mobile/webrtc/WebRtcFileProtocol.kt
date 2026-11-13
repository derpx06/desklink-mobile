/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Transfers v2 (W3). The wire contract is in stageD-protocol.md, "W3 — transfers v2". */
object FileV2 {
    const val BLOCK_BYTES = 1_048_576
    const val FRAME_BYTES_DEFAULT = 61_440
    const val FRAME_BYTES_MAX = 196_608

    /** The largest frame both builds are proven to carry (raise to [FRAME_BYTES_MAX] after the loopback proof). */
    const val FRAME_BYTES_PROVEN = FRAME_BYTES_DEFAULT
    const val FRAME_HEADER_BYTES = 40
    const val BLOCK_TRAILER_BYTES = 32
    const val MAX_TRANSFER_BYTES = 4L * 1024 * 1024 * 1024
    const val IN_FLIGHT_TARGET = 4L * 1024 * 1024
    const val IN_FLIGHT_LOW = 1L * 1024 * 1024
    const val UNACKED_MAX = 8L * 1024 * 1024
    const val CHECKPOINT_BLOCKS = 8
    const val CHECKPOINT_MS = 2_000L
    const val NO_PROGRESS_MS = 60_000L
    const val ACTIVE_PER_DIRECTION = 2
    const val MAX_REWINDS = 3
    const val MAX_RESTARTS = 2
    const val FILE_QUEUE_MAX_BYTES = 32L * 1024 * 1024
    const val STORAGE_RETRY_MS = 5_000L
    const val BUSY_RETRY_MS = 2_000L

    /** Payload bytes one frame of at most [maxFrameBytes] carries (the trailer space is always reserved). */
    fun payloadCapacity(maxFrameBytes: Int) = maxFrameBytes - FRAME_HEADER_BYTES - BLOCK_TRAILER_BYTES

    fun blockCount(totalSize: Long): Int = ((totalSize + BLOCK_BYTES - 1) / BLOCK_BYTES).toInt()
}

enum class WebRtcFileAction(val wireName: String) {
    OFFER("offer"),
    ACCEPT("accept"),
    BLOCK_ACK("block-ack"),
    BLOCK_NACK("block-nack"),
    PAUSE("pause"),
    RESUME("resume"),
    DONE("done"),
    COMPLETE("complete"),
    CANCEL("cancel"),
    ERROR("error"),

    /** W18: "from now on I send from block n at this pass over this lane" (sender to receiver). */
    LANE("lane");

    companion object {
        fun fromWireName(value: String): WebRtcFileAction =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unknown DeskLink WebRTC file action: $value")
    }
}

/** The `code` of a cancel or an error, and the `reason` of a pause. */
object FileCodes {
    const val USER = "user"
    const val SOURCE_GONE = "source-gone"
    const val SOURCE_CHANGED = "source-changed"
    const val REMOVED = "removed"
    const val REFUSED = "refused"
    const val BUSY = "busy"
    const val TOO_LARGE = "too-large"
    const val BAD_FRAME = "bad-frame"
    const val HASH_MISMATCH = "hash-mismatch"
    const val INTERNAL = "internal"
    const val STORAGE_FULL = "storage-full"
    const val BACKLOG = "backlog"
    const val NO_PROGRESS = "no-progress"
    val PAUSE_REASONS = setOf(STORAGE_FULL, BACKLOG, NO_PROGRESS, USER)
    /** Known codes. Cancel and error ACCEPT any other code (a newer build's) as a generic end; see [pauseReason]. */
    val CANCEL_CODES = setOf(USER, SOURCE_GONE, SOURCE_CHANGED, REMOVED)
    val ERROR_CODES = setOf(REFUSED, BUSY, TOO_LARGE, BAD_FRAME, HASH_MISMATCH, INTERNAL)

    /** An unknown pause reason is a pause by the person: the transfer waits for a `resume`. */
    fun pauseReason(reason: String?): String = reason?.takeIf { it in PAUSE_REASONS } ?: USER
}

data class WebRtcFileControl(
    val action: WebRtcFileAction,
    val transferId: String,
    val deviceId: String,
    val sessionId: Long,
    val connectionGeneration: Long,
    val transferToken: String,
    val protocolVersion: Int = VERSION,
    val filename: String? = null,
    val totalSize: Long? = null,
    val mtime: Long? = null,
    val blockSize: Int? = null,
    val maxFrameBytes: Int? = null,
    val resumeBlock: Int? = null,
    val prefixSha256: String? = null,
    val blockIndex: Int? = null,
    val sha256: String? = null,
    val pass: Int? = null,
    val reason: String? = null,
    val fileHash: String? = null,
    val blocks: Int? = null,
    val code: String? = null,
    val message: String? = null,
    /** Share fields on an offer (contract C3, F3): a clipboard image, or the file answering a capture. */
    val clipboard: Boolean? = null,
    /** W12: a new screenshot or photo the phone sends on its own (additive; carries no folder, clipboard or capture id). */
    val gallery: Boolean? = null,
    val mimeType: String? = null,
    val captureId: String? = null,
    /** A Bucket file on an offer: the pair id, its path, and the manifest's whole-file SHA-256. */
    val folder: String? = null,
    val relpath: String? = null,
    val contentSha256: String? = null,
    /** W18: the fast lane. An offer carries `{v}`, an accept `{v, port, token, ttlMs}`, a `lane` action `{kind, startBlock, pass}`. */
    val lane: FileLaneSpec? = null,
) {
    fun validate(wire: WebRtcWireBinding) {
        require(protocolVersion == VERSION) { "Unsupported DeskLink WebRTC file version" }
        require(
            deviceId == wire.peerDeviceId && sessionId == wire.sessionId && connectionGeneration == wire.generation,
        ) { "DeskLink WebRTC file binding mismatch" }
        validateTransferId(transferId)
        validateToken(transferToken)
        totalSize?.let { require(it in 0..FileV2.MAX_TRANSFER_BYTES) { "Invalid DeskLink WebRTC transfer size" } }
        maxFrameBytes?.let {
            require(it in FileV2.FRAME_HEADER_BYTES + FileV2.BLOCK_TRAILER_BYTES + 1..FileV2.FRAME_BYTES_MAX) { "Invalid DeskLink WebRTC frame size" }
        }
        listOfNotNull(prefixSha256, sha256, fileHash, contentSha256).forEach {
            require(it.length == 64 && it.all(Char::isHexDigit)) { "Invalid DeskLink WebRTC file checksum" }
        }
        resumeBlock?.let { require(it in 0..MAX_BLOCKS) { "Invalid DeskLink WebRTC resume block" } }
        blockIndex?.let { require(it in 0 until MAX_BLOCKS) { "Invalid DeskLink WebRTC block index" } }
        blocks?.let { require(it in 0..MAX_BLOCKS) { "Invalid DeskLink WebRTC block count" } }
        pass?.let { require(it in 0..255) { "Invalid DeskLink WebRTC pass" } }
        require((reason?.length ?: 0) <= 64 && (code?.length ?: 0) <= 32) { "Oversized DeskLink WebRTC file code" }
        require((message?.length ?: 0) <= 4096) { "Oversized DeskLink WebRTC file message" }
        when (action) {
            WebRtcFileAction.OFFER -> {
                validateFilename(requireNotNull(filename) { "Malformed DeskLink WebRTC file offer" })
                require(totalSize != null && mtime != null && blockSize == FileV2.BLOCK_BYTES && maxFrameBytes != null) {
                    "Malformed DeskLink WebRTC file offer"
                }
                if (folder != null || relpath != null) {
                    require(
                        folder != null && folder.length in 1..64 && folder.all { it.isLetterOrDigit() && it.code < 128 || it == '-' } &&
                            relpath != null && org.desklink.mobile.plugins.folders.FolderSync.validRelpath(relpath) &&
                            relpath.substringAfterLast('/') == filename &&
                            contentSha256 != null,
                    ) { "Malformed DeskLink synced file offer" }
                }
            }
            WebRtcFileAction.ACCEPT -> require(resumeBlock != null && prefixSha256 != null && maxFrameBytes != null) { "Malformed DeskLink WebRTC accept" }
            WebRtcFileAction.BLOCK_ACK -> require(blockIndex != null && sha256 != null) { "Malformed DeskLink WebRTC block ack" }
            WebRtcFileAction.BLOCK_NACK -> require(blockIndex != null && pass != null) { "Malformed DeskLink WebRTC block nack" }
            WebRtcFileAction.PAUSE -> Unit // any reason: an unknown one is treated as `user`
            WebRtcFileAction.RESUME -> require((resumeBlock == null) == (prefixSha256 == null) && (resumeBlock == null) == (pass == null)) { "Malformed DeskLink WebRTC resume" }
            WebRtcFileAction.DONE -> require(fileHash != null && blocks != null && totalSize != null) { "Malformed DeskLink WebRTC done" }
            WebRtcFileAction.COMPLETE -> require(fileHash != null) { "Malformed DeskLink WebRTC complete" }
            // Any code: an unknown one ends the transfer like any other, and frees its slot and partial file.
            WebRtcFileAction.CANCEL, WebRtcFileAction.ERROR -> Unit
            WebRtcFileAction.LANE -> require(lane?.kind in FileLaneSpec.KINDS && lane?.startBlock != null && lane.pass != null) { "Malformed DeskLink file lane" }
        }
        lane?.validate()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("protocolVersion", protocolVersion)
        .put("action", action.wireName)
        .put("transferId", transferId)
        .put("deviceId", deviceId)
        .put("sessionId", sessionId)
        .put("connectionGeneration", connectionGeneration)
        .put("transferToken", transferToken)
        .also { value ->
            filename?.let { value.put("filename", it) }
            totalSize?.let { value.put("totalSize", it) }
            mtime?.let { value.put("mtime", it) }
            blockSize?.let { value.put("blockSize", it) }
            maxFrameBytes?.let { value.put("maxFrameBytes", it) }
            resumeBlock?.let { value.put("resumeBlock", it) }
            prefixSha256?.let { value.put("prefixSha256", it) }
            blockIndex?.let { value.put("blockIndex", it) }
            sha256?.let { value.put("sha256", it) }
            pass?.let { value.put("pass", it) }
            reason?.let { value.put("reason", it) }
            fileHash?.let { value.put("fileHash", it) }
            blocks?.let { value.put("blocks", it) }
            code?.let { value.put("code", it) }
            message?.let { value.put("message", it) }
            clipboard?.let { value.put("clipboard", it) }
            gallery?.let { value.put("gallery", it) }
            mimeType?.let { value.put("mimeType", it) }
            captureId?.let { value.put("captureId", it) }
            folder?.let { value.put("folder", it) }
            relpath?.let { value.put("relpath", it) }
            contentSha256?.let { value.put("contentSha256", it) }
            lane?.let { value.put("lane", it.toJson()) }
        }

    companion object {
        const val VERSION = 2
        const val CONTROL_MESSAGE_TYPE = "desklink.file.control.v2"

        /** A file of [FileV2.MAX_TRANSFER_BYTES] is 4096 blocks. */
        const val MAX_BLOCKS = 4096

        fun fromJson(value: JSONObject): WebRtcFileControl {
            fun str(key: String) = value.optString(key).takeIf { value.has(key) && it.isNotEmpty() }
            fun int(key: String) = if (value.has(key)) value.getInt(key) else null
            fun long(key: String) = if (value.has(key)) value.getLong(key) else null
            // A peer's name is cleaned once, here, so a re-offer's matching, the checkpoint, the row and the saved file all see
            // the same name (a Bucket file keeps its name: it must equal its path's last segment).
            val folder = str("folder")
            val relpath = str("relpath")
            val filename = str("filename")?.let { if (folder == null && relpath == null) sanitizeFilename(it) else it }
            val action = WebRtcFileAction.fromWireName(value.getString("action"))
            // Forward compatibility: a lane this build cannot use (a newer version, kind or token) in an offer or an
            // accept is dropped and the file goes over WebRTC; refusing the whole message left a newer desk's transfer
            // unanswered. A `lane` switch to an unknown kind is still refused: it cannot be followed.
            val lane = value.optJSONObject("lane")?.let(FileLaneSpec::fromJson)?.takeUnless { spec ->
                (action == WebRtcFileAction.OFFER || action == WebRtcFileAction.ACCEPT) &&
                    (spec.v != 1 || runCatching { spec.validate() }.isFailure)
            }
            return WebRtcFileControl(
                protocolVersion = value.getInt("protocolVersion"),
                action = action,
                transferId = value.getString("transferId"),
                deviceId = value.getString("deviceId"),
                sessionId = value.getLong("sessionId"),
                connectionGeneration = value.getLong("connectionGeneration"),
                transferToken = value.getString("transferToken"),
                filename = filename,
                totalSize = long("totalSize"),
                mtime = long("mtime"),
                blockSize = int("blockSize"),
                maxFrameBytes = int("maxFrameBytes"),
                resumeBlock = int("resumeBlock"),
                prefixSha256 = str("prefixSha256"),
                blockIndex = int("blockIndex"),
                sha256 = str("sha256"),
                pass = int("pass"),
                reason = str("reason"),
                fileHash = str("fileHash"),
                blocks = int("blocks"),
                code = str("code"),
                message = str("message"),
                clipboard = if (value.has("clipboard")) value.getBoolean("clipboard") else null,
                gallery = if (value.has("gallery")) value.getBoolean("gallery") else null,
                mimeType = str("mimeType")?.take(128),
                captureId = str("captureId")?.take(64),
                folder = folder,
                relpath = relpath,
                contentSha256 = str("contentSha256"),
                lane = lane,
            )
        }
    }
}

/**
 * The `lane` field of a file control message (W18). Offer: [v]. Accept: [v], [port], [token], [ttlMs]. A `lane` action: [kind],
 * [startBlock], [pass]. Every field is optional on the wire and bounded here.
 */
data class FileLaneSpec(
    val v: Int? = null, val port: Int? = null, val token: String? = null, val ttlMs: Long? = null,
    val kind: String? = null, val startBlock: Int? = null, val pass: Int? = null,
) {
    fun validate() {
        require(v == null || v == 1) { "Unsupported DeskLink file lane version" }
        require(port == null || port in 1..65535) { "Invalid DeskLink file lane port" }
        require(token == null || FileLaneProtocol.validToken(token)) { "Invalid DeskLink file lane token" }
        require(ttlMs == null || ttlMs in 1..FileLaneProtocol.TOKEN_TTL_MS) { "Invalid DeskLink file lane lifetime" }
        require(kind == null || kind in KINDS) { "Invalid DeskLink file lane kind" }
        require(startBlock == null || startBlock in 0..WebRtcFileControl.MAX_BLOCKS) { "Invalid DeskLink file lane start" }
        require(pass == null || pass in 0..255) { "Invalid DeskLink file lane pass" }
    }

    fun toJson(): JSONObject = JSONObject().also { o ->
        v?.let { o.put("v", it) }; port?.let { o.put("port", it) }; token?.let { o.put("token", it) }; ttlMs?.let { o.put("ttlMs", it) }
        kind?.let { o.put("kind", it) }; startBlock?.let { o.put("startBlock", it) }; pass?.let { o.put("pass", it) }
    }

    companion object {
        const val TCP = "tcp"
        const val WEBRTC = "webrtc"
        val KINDS = setOf(TCP, WEBRTC)

        fun fromJson(o: JSONObject) = FileLaneSpec(
            v = if (o.has("v")) o.getInt("v") else null, port = if (o.has("port")) o.getInt("port") else null,
            token = if (o.has("token")) o.getString("token") else null, ttlMs = if (o.has("ttlMs")) o.getLong("ttlMs") else null,
            kind = if (o.has("kind")) o.getString("kind") else null, startBlock = if (o.has("startBlock")) o.getInt("startBlock") else null,
            pass = if (o.has("pass")) o.getInt("pass") else null,
        )
    }
}

/**
 * One binary data-channel message of `desklink-file-data-v2`: a 40-byte header, [length] payload
 * bytes and, on the last frame of a block, that block's SHA-256. [bytes] is the whole message;
 * nothing is copied until [payloadCopy].
 */
class FileFrame private constructor(
    val transferId: UUID,
    val pass: Int,
    val blockIndex: Int,
    val offset: Long,
    val length: Int,
    val lastOfBlock: Boolean,
    private val bytes: ByteArray,
) {
    val payloadOffset: Int get() = FileV2.FRAME_HEADER_BYTES

    fun payloadCopy(): ByteArray = bytes.copyOfRange(payloadOffset, payloadOffset + length)

    /** Feeds the payload into [digest] without copying it. */
    fun updateDigest(digest: MessageDigest) = digest.update(bytes, payloadOffset, length)

    fun writeTo(file: java.io.RandomAccessFile) {
        file.seek(offset)
        file.write(bytes, payloadOffset, length)
    }

    /** The block hash the sender put after the payload, on a last-of-block frame; null otherwise. */
    fun trailer(): ByteArray? =
        if (lastOfBlock) bytes.copyOfRange(payloadOffset + length, payloadOffset + length + FileV2.BLOCK_TRAILER_BYTES) else null

    companion object {
        private val MAGIC = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), '2'.code.toByte())
        const val FLAG_LAST = 0x01

        /** [data] is `source[from, from+length)`; [blockHash] must be given iff [last]. */
        fun encode(
            transferId: UUID,
            pass: Int,
            blockIndex: Int,
            offset: Long,
            source: ByteArray,
            from: Int,
            length: Int,
            blockHash: ByteArray?,
        ): ByteArray {
            require(length >= 1 && pass in 0..255 && blockIndex >= 0 && offset >= 0) { "Invalid DeskLink file frame" }
            val last = blockHash != null
            require(blockHash == null || blockHash.size == FileV2.BLOCK_TRAILER_BYTES) { "Invalid DeskLink file frame hash" }
            val buffer = ByteBuffer.allocate(FileV2.FRAME_HEADER_BYTES + length + if (last) FileV2.BLOCK_TRAILER_BYTES else 0)
            buffer.put(MAGIC)
            buffer.put((if (last) FLAG_LAST else 0).toByte())
            buffer.put(pass.toByte())
            buffer.putShort(0)
            buffer.putLong(transferId.mostSignificantBits)
            buffer.putLong(transferId.leastSignificantBits)
            buffer.putInt(blockIndex)
            buffer.putLong(offset)
            buffer.putInt(length)
            buffer.put(source, from, length)
            blockHash?.let(buffer::put)
            return buffer.array()
        }

        /**
         * Parses and bound-checks a message (everything that does not need the transfer's size).
         * Throws [IllegalArgumentException] for anything malformed; the caller drops it.
         */
        fun decode(message: ByteArray, maxFrameBytes: Int = FileV2.FRAME_BYTES_MAX): FileFrame {
            require(message.size in FileV2.FRAME_HEADER_BYTES + 1..maxFrameBytes) { "Malformed DeskLink file frame" }
            val buffer = ByteBuffer.wrap(message)
            val magic = ByteArray(4).also(buffer::get)
            require(magic.contentEquals(MAGIC)) { "Malformed DeskLink file frame" }
            val flags = buffer.get().toInt() and 0xff
            require(flags and FLAG_LAST.inv() == 0) { "Malformed DeskLink file frame flags" }
            val pass = buffer.get().toInt() and 0xff
            require(buffer.short.toInt() == 0) { "Malformed DeskLink file frame" }
            val id = UUID(buffer.long, buffer.long)
            val blockIndex = buffer.int
            val offset = buffer.long
            val length = buffer.int
            val last = flags and FLAG_LAST != 0
            require(length >= 1 && blockIndex >= 0 && offset >= 0) { "Malformed DeskLink file frame" }
            val expected = FileV2.FRAME_HEADER_BYTES.toLong() + length + if (last) FileV2.BLOCK_TRAILER_BYTES else 0
            require(expected == message.size.toLong()) { "Malformed DeskLink file frame length" }
            require(blockIndex.toLong() == offset / FileV2.BLOCK_BYTES) { "DeskLink file frame block does not match its offset" }
            val end = offset + length
            val blockEnd = (blockIndex.toLong() + 1) * FileV2.BLOCK_BYTES
            require(end <= blockEnd) { "DeskLink file frame crosses a block" }
            // Last-of-block is exact for a full block; at the file's end the receiver checks it against totalSize.
            require(!(end == blockEnd) || last) { "DeskLink file frame must close its block" }
            return FileFrame(id, pass, blockIndex, offset, length, last, message)
        }
    }
}

/** File-hash helpers: the file hash is SHA-256 over the concatenated 32-byte block hashes. */
object FileHashes {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest()
    }

    fun fileHash(blockHashes: List<ByteArray>): String = prefixHash(blockHashes, blockHashes.size)

    /** SHA-256 of the first [count] block hashes (the empty-input hash for 0). */
    fun prefixHash(blockHashes: List<ByteArray>, count: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (i in 0 until count) digest.update(blockHashes[i])
        return digest.digest().toHex()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** A transfer id is a lowercase canonical UUID: its 16 bytes are what every data frame carries. */
internal fun validateTransferId(value: String) {
    require(value.length == 36 && runCatching { UUID.fromString(value) }.isSuccess && value == value.lowercase()) {
        "Invalid DeskLink transfer ID"
    }
}

internal fun validateToken(value: String) {
    require(value.isNotEmpty() && value.length <= 256 && value.all(Char::isAscii)) {
        "Invalid DeskLink transfer token"
    }
}

/**
 * A received name as it is kept and shown: control characters and the bidi marks and overrides (`photo<U+202E>gpj.exe` reads as
 * `photoexe.jpg`) are dropped, an empty result, `.` or `..` becomes `file`, and at most 255 UTF-8 bytes stay. Mirrors the desktop's
 * `sanitize_filename`; both pass `shared/filenames.json`.
 */
internal fun sanitizeFilename(raw: String): String {
    val kept = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val cp = raw.codePointAt(i)
        i += Character.charCount(cp)
        if (Character.getType(cp) == Character.CONTROL.toInt() || isBidiMark(cp)) continue
        kept.appendCodePoint(cp)
    }
    var name = kept.toString()
    if (name.toByteArray(StandardCharsets.UTF_8).size > 255) name = truncateUtf8(name, 255)
    return if (name.isEmpty() || name == "." || name == "..") "file" else name
}

private fun isBidiMark(cp: Int) =
    cp == 0x200E || cp == 0x200F || cp == 0x061C || cp == 0x2028 || cp == 0x2029 || cp in 0x202A..0x202E || cp in 0x2066..0x2069

/** The longest prefix of [value] that is at most [maxBytes] UTF-8 bytes, cut on a character boundary. */
internal fun truncateUtf8(value: String, maxBytes: Int): String {
    var bytes = 0
    var i = 0
    while (i < value.length) {
        val cp = value.codePointAt(i)
        val n = String(Character.toChars(cp)).toByteArray(StandardCharsets.UTF_8).size
        if (bytes + n > maxBytes) break
        bytes += n
        i += Character.charCount(cp)
    }
    return value.substring(0, i)
}

/** One path component, at most 255 UTF-8 *bytes* (the desktop counts bytes, not characters). */
internal fun validateFilename(value: String) {
    require(
        value.isNotEmpty() && value.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
            value != "." && value != ".." &&
            '/' !in value && '\\' !in value && '\u0000' !in value,
    ) { "Unsafe DeskLink transfer filename" }
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
private fun Char.isAscii(): Boolean = code in 0..0x7f
