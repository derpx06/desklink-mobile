/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class WebRtcFileAction(val wireName: String) {
    OFFER("offer"),
    ACCEPT("accept"),
    ACKNOWLEDGE("acknowledge"),
    COMPLETE("complete"),
    CANCEL("cancel"),
    ERROR("error");

    companion object {
        fun fromWireName(value: String): WebRtcFileAction =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unknown DeskLink WebRTC file action: $value")
    }
}

data class WebRtcFileControl(
    val protocolVersion: Int,
    val action: WebRtcFileAction,
    val transferId: String,
    val deviceId: String,
    val sessionId: Long,
    val connectionGeneration: Long,
    val transferToken: String,
    val filename: String? = null,
    val totalSize: Long? = null,
    val sha256: String? = null,
    val offset: Long,
    /** Always [LEGACY_CHUNK_BYTES] on the wire, so older peers validate. */
    val chunkSize: Int,
    val error: String? = null,
    /**
     * Chunk-size upgrade, ignored by older peers (mirrors the desktop's
     * `max_chunk_size`): the Offer carries the largest chunk the sender can
     * send, and a receiver that understands it echoes what it accepts.
     */
    val maxChunkSize: Int? = null,
    /**
     * Share fields on an Offer (contract C3), ignored by older peers: `clipboard: true` with
     * `mimeType: "image/png"` puts the file on the receiver's clipboard instead of Downloads.
     */
    val clipboard: Boolean? = null,
    val mimeType: String? = null,
) {
    fun validate(wire: WebRtcWireBinding) {
        require(protocolVersion == VERSION) { "Unsupported DeskLink WebRTC file version" }
        require(
            deviceId == wire.peerDeviceId &&
                sessionId == wire.sessionId &&
                connectionGeneration == wire.generation
        ) { "DeskLink WebRTC file binding mismatch" }
        validateId(transferId)
        validateToken(transferToken)
        require(chunkSize in 1..MAX_CHUNK_BYTES) { "Invalid DeskLink WebRTC file chunk size" }
        require(maxChunkSize == null || maxChunkSize in 1..MAX_CHUNK_BYTES) {
            "Invalid DeskLink WebRTC file chunk size"
        }
        // The desktop's `offset` is a u64: a negative one is never legitimate,
        // whether or not the record announces a size.
        require(offset >= 0) { "Invalid DeskLink WebRTC transfer size or offset" }
        totalSize?.let {
            require(it in 0..MAX_TRANSFER_BYTES && offset in 0..it) {
                "Invalid DeskLink WebRTC transfer size or offset"
            }
        }
        sha256?.let {
            require(it.length == 64 && it.all(Char::isHexDigit)) {
                "Invalid DeskLink WebRTC file checksum"
            }
        }
        if (action == WebRtcFileAction.OFFER) {
            validateFilename(requireNotNull(filename))
            require(totalSize != null && sha256 != null && offset == 0L) {
                "Malformed DeskLink WebRTC file offer"
            }
        }
        require(error == null || error.length <= 4096) { "Oversized DeskLink WebRTC file error" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("protocolVersion", protocolVersion)
        .put("action", action.wireName)
        .put("transferId", transferId)
        .put("deviceId", deviceId)
        .put("sessionId", sessionId)
        .put("connectionGeneration", connectionGeneration)
        .put("transferToken", transferToken)
        .put("offset", offset)
        .put("chunkSize", chunkSize)
        .also { value ->
            filename?.let { value.put("filename", it) }
            totalSize?.let { value.put("totalSize", it) }
            sha256?.let { value.put("sha256", it) }
            error?.let { value.put("error", it) }
            maxChunkSize?.let { value.put("maxChunkSize", it) }
            clipboard?.let { value.put("clipboard", it) }
            mimeType?.let { value.put("mimeType", it) }
        }

    companion object {
        const val VERSION = 1
        const val CONTROL_MESSAGE_TYPE = "desklink.file.control.v1"
        const val CHUNK_MESSAGE_TYPE = "desklink.file.chunk.v1"
        // Matches the desktop's MAX_FILE_CHUNK_BYTES: the whole message (DLF1
        // header, base64, JSON envelope) must fit webrtcbin's default 64 KiB
        // data-channel maximum. Builds before the upgrade only take 16 KiB.
        const val MAX_CHUNK_BYTES = 45 * 1024
        const val LEGACY_CHUNK_BYTES = 16 * 1024
        const val MAX_TRANSFER_BYTES = 4L * 1024 * 1024 * 1024

        fun fromJson(value: JSONObject): WebRtcFileControl = WebRtcFileControl(
            protocolVersion = value.getInt("protocolVersion"),
            action = WebRtcFileAction.fromWireName(value.getString("action")),
            transferId = value.getString("transferId"),
            deviceId = value.getString("deviceId"),
            sessionId = value.getLong("sessionId"),
            connectionGeneration = value.getLong("connectionGeneration"),
            transferToken = value.getString("transferToken"),
            filename = value.optString("filename").takeIf(String::isNotEmpty),
            totalSize = value.optLong("totalSize").takeIf { value.has("totalSize") },
            sha256 = value.optString("sha256").takeIf(String::isNotEmpty),
            offset = value.getLong("offset"),
            chunkSize = value.getInt("chunkSize"),
            error = value.optString("error").takeIf(String::isNotEmpty),
            maxChunkSize = value.optInt("maxChunkSize").takeIf { value.has("maxChunkSize") },
            clipboard = value.optBoolean("clipboard").takeIf { value.has("clipboard") },
            mimeType = value.optString("mimeType").takeIf(String::isNotEmpty)?.take(128),
        )
    }
}

data class WebRtcFileChunk(
    val transferId: String,
    val transferToken: String,
    val offset: Long,
    val data: ByteArray,
) {
    fun encode(): ByteArray {
        validateId(transferId)
        validateToken(transferToken)
        require(data.isNotEmpty() && data.size <= WebRtcFileControl.MAX_CHUNK_BYTES) {
            "Invalid DeskLink WebRTC file chunk size"
        }
        val id = transferId.toByteArray(StandardCharsets.UTF_8)
        val token = transferToken.toByteArray(StandardCharsets.UTF_8)
        require(id.size <= UShort.MAX_VALUE.toInt() && token.size <= UShort.MAX_VALUE.toInt())
        return ByteBuffer.allocate(52 + id.size + token.size + data.size)
            .put(MAGIC)
            .putShort(id.size.toShort())
            .putShort(token.size.toShort())
            .putLong(offset)
            .putInt(data.size)
            .put(MessageDigest.getInstance("SHA-256").digest(data))
            .put(id)
            .put(token)
            .put(data)
            .array()
    }

    override fun equals(other: Any?): Boolean = other is WebRtcFileChunk &&
        transferId == other.transferId && transferToken == other.transferToken &&
        offset == other.offset && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * (31 * (31 * transferId.hashCode() + transferToken.hashCode()) + offset.hashCode()) + data.contentHashCode()

    companion object {
        private val MAGIC = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())

        fun decode(encoded: ByteArray): WebRtcFileChunk {
            require(encoded.size >= 52) { "Malformed DeskLink WebRTC file chunk" }
            val buffer = ByteBuffer.wrap(encoded)
            val magic = ByteArray(4).also(buffer::get)
            require(magic.contentEquals(MAGIC)) { "Malformed DeskLink WebRTC file chunk" }
            val idLength = buffer.short.toInt() and 0xffff
            val tokenLength = buffer.short.toInt() and 0xffff
            val offset = buffer.long
            val dataLength = buffer.int
            require(dataLength in 1..WebRtcFileControl.MAX_CHUNK_BYTES) {
                "Invalid DeskLink WebRTC file chunk size"
            }
            val digest = ByteArray(32).also(buffer::get)
            val expected = 52L + idLength + tokenLength + dataLength
            require(expected == encoded.size.toLong()) { "Malformed DeskLink WebRTC file chunk" }
            val transferId = String(ByteArray(idLength).also(buffer::get), StandardCharsets.UTF_8)
            val token = String(ByteArray(tokenLength).also(buffer::get), StandardCharsets.UTF_8)
            val data = ByteArray(dataLength).also(buffer::get)
            validateId(transferId)
            validateToken(token)
            require(MessageDigest.getInstance("SHA-256").digest(data).contentEquals(digest)) {
                "DeskLink WebRTC file chunk checksum mismatch"
            }
            return WebRtcFileChunk(transferId, token, offset, data)
        }
    }
}

/** ASCII `[A-Za-z0-9_-]{1,128}`, exactly what the desktop accepts. IDs name files, so no Unicode letters. */
internal fun validateId(value: String) {
    require(value.isNotEmpty() && value.length <= 128 && value.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
    }) { "Invalid DeskLink transfer ID" }
}

private fun validateToken(value: String) {
    require(value.isNotEmpty() && value.length <= 256 && value.all(Char::isAscii)) {
        "Invalid DeskLink transfer token"
    }
}

/** One path component, at most 255 UTF-8 *bytes* (the desktop counts bytes, not characters). */
internal fun validateFilename(value: String) {
    require(
        value.isNotEmpty() && value.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
            value != "." && value != ".." &&
            '/' !in value && '\\' !in value && '\u0000' !in value
    ) { "Unsafe DeskLink transfer filename" }
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
private fun Char.isAscii(): Boolean = code in 0..0x7f
