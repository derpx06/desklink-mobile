/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc.terminal

import org.desklink.mobile.webrtc.WebRtcWireBinding
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * Wire format of the `desklink-terminal-v1` data channel.
 *
 * Mirror of `DeskLink/src/device_links/webrtc/terminal_protocol.rs`, which is
 * the contract. Everything that arrives from the peer is validated here before
 * any other code sees it: the peer is authenticated but not trusted to be
 * well-formed.
 */
object TerminalProtocol {
    const val VERSION = 1
    const val CONTROL_MESSAGE_TYPE = "desklink.terminal.control.v1"
    const val CHUNK_MESSAGE_TYPE = "desklink.terminal.chunk.v1"

    /** Payload bytes per frame; the desktop splits larger writes to this. */
    const val MAX_CHUNK_BYTES = 16 * 1024
    const val CHUNK_HEADER_BYTES = 19
    const val MAX_DIMENSION = 1000
    const val MAX_COMMAND_ARGS = 64
    const val MAX_COMMAND_ARG_BYTES = 4 * 1024
    const val MAX_ERROR_BYTES = 1024
    const val MAX_TERMINAL_ID_LENGTH = 128

    /** Local bound: the wire has none, a phone list screen should not either grow forever. */
    const val MAX_SESSIONS = 256
    const val MAX_TITLE_CHARS = 256

    /** Sent as `error` when the desktop user has not enabled terminal access for this phone. */
    const val ERROR_NOT_ALLOWED = "terminal-not-allowed"

    /** Sent as `error` (with the terminal id) when an attach names a session that does not exist. */
    const val ERROR_NO_SUCH_TERMINAL = "No such DeskLink terminal"
}

enum class TerminalAction(val wire: String, val hostAuthored: Boolean) {
    LIST("list", false),
    SESSIONS("sessions", true),
    CREATE("create", false),
    CREATED("created", true),
    ATTACH("attach", false),
    ATTACHED("attached", true),
    DETACH("detach", false),
    CLOSE("close", false),
    CLOSED("closed", true),
    RESIZE("resize", false),
    ERROR("error", true),
    ;

    companion object {
        fun fromWire(value: String): TerminalAction =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("Unknown DeskLink terminal action")
    }
}

data class TerminalSessionInfo(
    val terminalId: String,
    val title: String,
    val rows: Int,
    val cols: Int,
    val exitStatus: Long? = null,
)

/**
 * A `desklink.terminal.control.v1` message.
 *
 * Optional fields are **omitted, never emitted as null or empty**, exactly as
 * serde does on the desktop; [toJson] writes fields in the struct's declaration
 * order so the bytes match the Rust encoder for the same values.
 */
data class TerminalControl(
    val action: TerminalAction,
    val deviceId: String,
    val sessionId: Long,
    val connectionGeneration: Long,
    val protocolVersion: Int = TerminalProtocol.VERSION,
    val terminalId: String? = null,
    val rows: Int? = null,
    val cols: Int? = null,
    val offset: Long? = null,
    val command: List<String>? = null,
    val sessions: List<TerminalSessionInfo>? = null,
    val exitStatus: Long? = null,
    val error: String? = null,
) {
    fun toJson(): ByteArray {
        val out = StringBuilder(160)
        out.append("{\"protocolVersion\":").append(protocolVersion)
        out.append(",\"action\":").append(quote(action.wire))
        out.append(",\"deviceId\":").append(quote(deviceId))
        out.append(",\"sessionId\":").append(sessionId)
        out.append(",\"connectionGeneration\":").append(connectionGeneration)
        terminalId?.let { out.append(",\"terminalId\":").append(quote(it)) }
        rows?.let { out.append(",\"rows\":").append(it) }
        cols?.let { out.append(",\"cols\":").append(it) }
        offset?.let { out.append(",\"offset\":").append(it) }
        command?.let { argv ->
            out.append(",\"command\":[").append(argv.joinToString(",") { quote(it) }).append(']')
        }
        sessions?.let { list ->
            out.append(",\"sessions\":[")
            out.append(
                list.joinToString(",") { info ->
                    buildString {
                        append("{\"terminalId\":").append(quote(info.terminalId))
                        append(",\"title\":").append(quote(info.title))
                        append(",\"rows\":").append(info.rows)
                        append(",\"cols\":").append(info.cols)
                        info.exitStatus?.let { append(",\"exitStatus\":").append(it) }
                        append('}')
                    }
                },
            )
            out.append(']')
        }
        exitStatus?.let { out.append(",\"exitStatus\":").append(it) }
        error?.let { out.append(",\"error\":").append(quote(it)) }
        out.append('}')
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Everything a message from the desktop must satisfy before it is acted on.
     *
     * Dispatch is by [action], never by which fields happen to be present, and
     * a client-authored action arriving from the host is refused: it would be
     * the host asking the phone to do something the protocol never lets it ask.
     */
    fun validateFromHost(wire: WebRtcWireBinding) {
        require(protocolVersion == TerminalProtocol.VERSION) { "Unsupported DeskLink WebRTC terminal version" }
        require(
            deviceId == wire.peerDeviceId &&
                sessionId == wire.sessionId &&
                connectionGeneration == wire.generation,
        ) { "DeskLink WebRTC terminal binding mismatch" }
        require(action.hostAuthored) { "DeskLink terminal action ${action.wire} is client-authored" }
        terminalId?.let(::validateTerminalId)
        validateDimensions(rows)
        validateDimensions(cols)
        command?.let(::validateCommand)
        exitStatus?.let { require(it in 0..MAX_U32) { "DeskLink terminal exit status is out of range" } }
        error?.let {
            require(it.toByteArray(Charsets.UTF_8).size <= TerminalProtocol.MAX_ERROR_BYTES) {
                "DeskLink terminal error is too long"
            }
        }
        sessions?.let { list ->
            require(list.size <= TerminalProtocol.MAX_SESSIONS) { "DeskLink terminal session list is too long" }
            list.forEach { info ->
                validateTerminalId(info.terminalId)
                validateDimensions(info.rows)
                validateDimensions(info.cols)
                info.exitStatus?.let { require(it in 0..MAX_U32) { "DeskLink terminal exit status is out of range" } }
            }
        }
        when (action) {
            TerminalAction.SESSIONS -> require(sessions != null) { "DeskLink terminal session list is missing" }
            TerminalAction.CREATED, TerminalAction.CLOSED ->
                require(terminalId != null) { "DeskLink terminal reply needs a terminal id" }
            TerminalAction.ATTACHED -> require(terminalId != null && offset != null) {
                "DeskLink terminal attach reply needs a terminal id and offset"
            }
            else -> Unit
        }
    }

    companion object {
        private const val MAX_U32 = 0xFFFF_FFFFL

        fun create(action: TerminalAction, wire: WebRtcWireBinding) = TerminalControl(
            action = action,
            deviceId = wire.senderDeviceId,
            sessionId = wire.sessionId,
            connectionGeneration = wire.generation,
        )

        /** Syntactic parse only. Call [validateFromHost] before acting on the result. */
        fun fromJson(input: ByteArray): TerminalControl {
            val json = try {
                JSONObject(String(input, Charsets.UTF_8))
            } catch (error: org.json.JSONException) {
                throw IllegalArgumentException("Malformed DeskLink terminal control", error)
            }
            return TerminalControl(
                action = TerminalAction.fromWire(json.requiredString("action")),
                deviceId = json.requiredString("deviceId"),
                sessionId = json.requiredInteger("sessionId"),
                connectionGeneration = json.requiredInteger("connectionGeneration"),
                protocolVersion = narrow(json.requiredInteger("protocolVersion")),
                terminalId = json.optionalString("terminalId"),
                rows = json.optionalInteger("rows")?.let(::narrow),
                cols = json.optionalInteger("cols")?.let(::narrow),
                offset = json.optionalInteger("offset"),
                command = json.optionalArray("command")?.let { array ->
                    (0 until array.length()).map { index ->
                        array.opt(index) as? String
                            ?: throw IllegalArgumentException("Malformed DeskLink terminal command")
                    }
                },
                sessions = json.optionalArray("sessions")?.let { array ->
                    require(array.length() <= TerminalProtocol.MAX_SESSIONS) {
                        "DeskLink terminal session list is too long"
                    }
                    (0 until array.length()).map { index ->
                        val item = array.opt(index) as? JSONObject
                            ?: throw IllegalArgumentException("Malformed DeskLink terminal session")
                        TerminalSessionInfo(
                            terminalId = item.requiredString("terminalId"),
                            title = item.requiredString("title").take(TerminalProtocol.MAX_TITLE_CHARS),
                            rows = narrow(item.requiredInteger("rows")),
                            cols = narrow(item.requiredInteger("cols")),
                            exitStatus = item.optionalInteger("exitStatus"),
                        )
                    }
                },
                exitStatus = json.optionalInteger("exitStatus"),
                error = json.optionalString("error"),
            )
        }

        private fun narrow(value: Long): Int {
            require(value in 0..Int.MAX_VALUE) { "DeskLink terminal number is out of range" }
            return value.toInt()
        }

        /** A JSON null counts as absent, like serde's `Option`. */
        private fun JSONObject.present(key: String): Any? = opt(key).takeIf { it != null && it != JSONObject.NULL }

        private fun bad(key: String): Nothing =
            throw IllegalArgumentException("Malformed DeskLink terminal control: $key")

        private fun JSONObject.optionalString(key: String): String? =
            present(key)?.let { it as? String ?: bad(key) }

        private fun JSONObject.requiredString(key: String): String = optionalString(key) ?: bad(key)

        /** Integral and non-negative. Doubles and numeric strings are refused, not coerced. */
        private fun JSONObject.optionalInteger(key: String): Long? = present(key)?.let {
            val number = (it as? Int)?.toLong() ?: it as? Long ?: bad(key)
            if (number >= 0) number else bad(key)
        }

        private fun JSONObject.requiredInteger(key: String): Long = optionalInteger(key) ?: bad(key)

        private fun JSONObject.optionalArray(key: String): JSONArray? =
            present(key)?.let { it as? JSONArray ?: bad(key) }
    }
}

enum class ChunkKind(val wire: Int, val hostAuthored: Boolean) {
    /** Host to phone: bytes the program wrote. */
    OUTPUT(0, true),

    /** Phone to host: keystrokes. */
    INPUT(1, false),

    /** Host to phone: escape codes reproducing the current screen. */
    SNAPSHOT(2, true),
    ;

    companion object {
        fun fromWire(value: Int): ChunkKind =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("Unknown DeskLink terminal chunk kind")
    }
}

/**
 * Terminal bytes on the wire: `"DLT1" | kind u8 | idLen u16 | offset u64 |
 * dataLen u32 | id | data`, big-endian, 19 header bytes.
 *
 * [data] is opaque bytes, never text: it may contain NULs and a UTF-8
 * sequence split across frames.
 */
class TerminalChunk(
    val kind: ChunkKind,
    val terminalId: String,
    val offset: Long,
    val data: ByteArray,
) {
    fun encode(): ByteArray {
        validateTerminalId(terminalId)
        require(data.size <= TerminalProtocol.MAX_CHUNK_BYTES) { "DeskLink terminal chunk is too large" }
        require(offset >= 0) { "DeskLink terminal offset is negative" }
        val id = terminalId.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(TerminalProtocol.CHUNK_HEADER_BYTES + id.size + data.size)
            .put(MAGIC)
            .put(kind.wire.toByte())
            .putShort(id.size.toShort())
            .putLong(offset)
            .putInt(data.size)
            .put(id)
            .put(data)
            .array()
    }

    override fun equals(other: Any?): Boolean =
        other is TerminalChunk &&
            kind == other.kind &&
            terminalId == other.terminalId &&
            offset == other.offset &&
            data.contentEquals(other.data)

    override fun hashCode(): Int = ((kind.hashCode() * 31 + terminalId.hashCode()) * 31 + offset.hashCode()) * 31 +
        data.contentHashCode()

    override fun toString(): String = "TerminalChunk($kind, $terminalId, offset=$offset, ${data.size} bytes)"

    companion object {
        private val MAGIC = "DLT1".toByteArray(Charsets.US_ASCII)

        /**
         * Parses a frame from the desktop. Refuses everything malformed and any
         * client-authored kind: an INPUT frame from the host is a forged
         * keystroke aimed at nothing, not a message.
         */
        fun decodeFromHost(encoded: ByteArray): TerminalChunk =
            decode(encoded).also {
                require(it.kind.hostAuthored) { "DeskLink terminal chunk kind ${it.kind} is client-authored" }
            }

        fun decode(encoded: ByteArray): TerminalChunk {
            val malformed = "Malformed DeskLink WebRTC terminal chunk"
            require(encoded.size >= TerminalProtocol.CHUNK_HEADER_BYTES) { malformed }
            for (index in MAGIC.indices) require(encoded[index] == MAGIC[index]) { malformed }
            val buffer = ByteBuffer.wrap(encoded)
            buffer.position(4)
            val kind = ChunkKind.fromWire(buffer.get().toInt() and 0xFF)
            val idLength = buffer.short.toInt() and 0xFFFF
            val offset = buffer.long
            val dataLength = buffer.int.toLong() and 0xFFFF_FFFFL
            require(offset >= 0) { malformed }
            require(dataLength <= TerminalProtocol.MAX_CHUNK_BYTES) { "DeskLink terminal chunk is too large" }
            // Checked against what the frame really carries before anything is sliced.
            require(TerminalProtocol.CHUNK_HEADER_BYTES.toLong() + idLength + dataLength == encoded.size.toLong()) {
                malformed
            }
            val idBytes = ByteArray(idLength).also { buffer.get(it) }
            val id = String(idBytes, Charsets.US_ASCII)
            require(idBytes.all { it in 0..127 }) { "Invalid DeskLink terminal id" }
            validateTerminalId(id)
            val data = ByteArray(dataLength.toInt()).also { buffer.get(it) }
            return TerminalChunk(kind, id, offset, data)
        }
    }
}

internal fun validateTerminalId(value: String) {
    require(
        value.isNotEmpty() &&
            value.length <= TerminalProtocol.MAX_TERMINAL_ID_LENGTH &&
            value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' },
    ) { "Invalid DeskLink terminal id" }
}

internal fun validateDimensions(value: Int?) {
    if (value != null) {
        require(value in 1..TerminalProtocol.MAX_DIMENSION) { "DeskLink terminal size is out of range" }
    }
}

private fun validateCommand(command: List<String>) {
    require(command.isNotEmpty()) { "DeskLink terminal command is empty" }
    require(command.size <= TerminalProtocol.MAX_COMMAND_ARGS) { "DeskLink terminal command has too many arguments" }
    require(command.none { it.toByteArray(Charsets.UTF_8).size > TerminalProtocol.MAX_COMMAND_ARG_BYTES }) {
        "DeskLink terminal command argument is too long"
    }
}

/** JSON string with serde_json's escaping: only `"`, `\` and control characters. */
internal fun quote(value: String): String {
    val out = StringBuilder(value.length + 2).append('"')
    for (char in value) {
        when {
            char == '"' -> out.append("\\\"")
            char == '\\' -> out.append("\\\\")
            char == '\n' -> out.append("\\n")
            char == '\r' -> out.append("\\r")
            char == '\t' -> out.append("\\t")
            char == '\b' -> out.append("\\b")
            char == '\u000C' -> out.append("\\f")
            char < ' ' -> out.append(String.format("\\u%04x", char.code))
            else -> out.append(char)
        }
    }
    return out.append('"').toString()
}
