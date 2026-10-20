/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc.terminal

import org.desklink.mobile.webrtc.WebRtcWireBinding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The GOLDEN_* constants were produced by the desktop's own
 * `terminal_protocol.rs` (serde_json + `TerminalChunk::encode`) from a scratch
 * crate that compiles that file unchanged, so passing here means the two
 * encoders agree byte for byte. Desktop side: pin the same constants in
 * `terminal_protocol.rs` tests.
 *
 * Bindings: the desktop's messages carry deviceId `desktop-1` (it is the sender),
 * the phone's carry `phone-1`; session 7, generation 1.
 */
class TerminalProtocolTest {
    private val wire = WebRtcWireBinding("phone-1", "desktop-1", 7, 1)

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun toHex(value: ByteArray) = value.joinToString("") { "%02x".format(it) }

    private fun rejected(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { block() }
    }

    // ---- golden bytes ---------------------------------------------------------------------

    @Test
    fun chunksEncodeToTheDesktopBytes() {
        assertEquals(
            GOLDEN_CHUNK_OUTPUT,
            toHex(TerminalChunk(ChunkKind.OUTPUT, "t1", 258, "hi".toByteArray()).encode()),
        )
        assertEquals(
            GOLDEN_CHUNK_SNAPSHOT,
            toHex(TerminalChunk(ChunkKind.SNAPSHOT, "abc-DEF_9", 4_294_967_296, GOLDEN_SNAPSHOT_DATA).encode()),
        )
        assertEquals(
            GOLDEN_CHUNK_INPUT,
            toHex(TerminalChunk(ChunkKind.INPUT, "t1", 0, byteArrayOf(0x03, 0x7f, 0x0d)).encode()),
        )
    }

    @Test
    fun chunksDecodeFromTheDesktopBytes() {
        assertEquals(
            TerminalChunk(ChunkKind.OUTPUT, "t1", 258, "hi".toByteArray()),
            TerminalChunk.decodeFromHost(hex(GOLDEN_CHUNK_OUTPUT)),
        )
        val snapshot = TerminalChunk.decodeFromHost(hex(GOLDEN_CHUNK_SNAPSHOT))
        assertEquals(ChunkKind.SNAPSHOT, snapshot.kind)
        assertEquals("abc-DEF_9", snapshot.terminalId)
        assertEquals(4_294_967_296, snapshot.offset)
        // A snapshot begins with RIS so the screen is reset before it is painted.
        assertEquals(0x1b.toByte(), snapshot.data[0])
        assertEquals('c'.code.toByte(), snapshot.data[1])
    }

    @Test
    fun headerLayoutIsBigEndianAnd19Bytes() {
        val encoded = hex(GOLDEN_CHUNK_OUTPUT)
        assertEquals(TerminalProtocol.CHUNK_HEADER_BYTES + 2 + 2, encoded.size)
        val buffer = ByteBuffer.wrap(encoded)
        assertEquals(0x444c5431, buffer.int) // "DLT1"
        assertEquals(0, buffer.get().toInt())
        assertEquals(2, buffer.short.toInt())
        assertEquals(258L, buffer.long)
        assertEquals(2, buffer.int)
    }

    @Test
    fun controlMessagesEncodeToTheDesktopBytes() {
        fun json(control: TerminalControl) = String(control.toJson(), Charsets.UTF_8)
        val host = WebRtcWireBinding("desktop-1", "phone-1", 7, 1)

        assertEquals(
            GOLDEN_ATTACHED,
            json(
                TerminalControl.create(TerminalAction.ATTACHED, host)
                    .copy(terminalId = "t1", offset = 258, rows = 24, cols = 80),
            ),
        )
        assertEquals(
            GOLDEN_SESSIONS,
            json(
                TerminalControl.create(TerminalAction.SESSIONS, host).copy(
                    sessions = listOf(
                        TerminalSessionInfo("t1", "bash", 24, 80),
                        TerminalSessionInfo("t2", "vim \"x\"", 50, 132, exitStatus = 1),
                    ),
                ),
            ),
        )
        assertEquals(
            GOLDEN_ERROR,
            json(TerminalControl.create(TerminalAction.ERROR, host).copy(error = "terminal-not-allowed")),
        )
        assertEquals(
            GOLDEN_CLOSED,
            json(TerminalControl.create(TerminalAction.CLOSED, host).copy(terminalId = "t1", exitStatus = 0)),
        )
        assertEquals(GOLDEN_LIST, json(TerminalControl.create(TerminalAction.LIST, wire)))
        assertEquals(
            GOLDEN_CREATE,
            json(
                TerminalControl.create(TerminalAction.CREATE, wire)
                    .copy(rows = 24, cols = 80, command = listOf("tmux", "attach")),
            ),
        )
        assertEquals(
            GOLDEN_ATTACH,
            json(TerminalControl.create(TerminalAction.ATTACH, wire).copy(terminalId = "t1", offset = 258)),
        )
        assertEquals(
            GOLDEN_RESIZE,
            json(TerminalControl.create(TerminalAction.RESIZE, wire).copy(terminalId = "t1", rows = 40, cols = 100)),
        )
    }

    @Test
    fun controlMessagesParseFromTheDesktopBytes() {
        val attached = TerminalControl.fromJson(GOLDEN_ATTACHED.toByteArray())
        attached.validateFromHost(wire)
        assertEquals(TerminalAction.ATTACHED, attached.action)
        assertEquals("t1", attached.terminalId)
        assertEquals(258L, attached.offset)
        assertEquals(24, attached.rows)
        assertEquals(80, attached.cols)

        val sessions = TerminalControl.fromJson(GOLDEN_SESSIONS.toByteArray())
        sessions.validateFromHost(wire)
        assertEquals(2, sessions.sessions.orEmpty().size)
        assertNull(sessions.sessions.orEmpty()[0].exitStatus)
        assertEquals(1L, sessions.sessions.orEmpty()[1].exitStatus)
        assertEquals("vim \"x\"", sessions.sessions.orEmpty()[1].title)

        val error = TerminalControl.fromJson(GOLDEN_ERROR.toByteArray())
        error.validateFromHost(wire)
        assertEquals(TerminalProtocol.ERROR_NOT_ALLOWED, error.error)

        val closed = TerminalControl.fromJson(GOLDEN_CLOSED.toByteArray())
        closed.validateFromHost(wire)
        assertEquals(0L, closed.exitStatus)
    }

    @Test
    fun absentOptionalFieldsAreOmittedNeverNull() {
        val json = String(TerminalControl.create(TerminalAction.LIST, wire).toJson())
        for (absent in listOf("terminalId", "rows", "cols", "offset", "command", "sessions", "exitStatus", "error")) {
            assertFalse("$absent should be omitted", json.contains(absent))
        }
        assertFalse(json.contains("null"))
    }

    @Test
    fun controlCharactersAreEscapedLikeSerde() {
        val json = String(TerminalControl.create(TerminalAction.ERROR, wire).copy(error = "a\"b\\c\n\u0001/").toJson())
        assertTrue(json, json.endsWith("\"error\":\"a\\\"b\\\\c\\n\\u0001/\"}"))
        val parsed = TerminalControl.fromJson(json.toByteArray())
        assertEquals("a\"b\\c\n\u0001/", parsed.error)
    }

    // ---- hostile control messages ----------------------------------------------------------

    private fun fromHost(json: String) = TerminalControl.fromJson(json.toByteArray()).validateFromHost(wire)

    private fun host(body: String, action: String = "sessions", device: String = "desktop-1", session: Long = 7, generation: Long = 1) =
        "{\"protocolVersion\":1,\"action\":\"$action\",\"deviceId\":\"$device\",\"sessionId\":$session," +
            "\"connectionGeneration\":$generation$body}"

    @Test
    fun aStaleOrForeignBindingIsRefused() {
        rejected { fromHost(host(",\"sessions\":[]", session = 8)) }
        rejected { fromHost(host(",\"sessions\":[]", generation = 2)) }
        rejected { fromHost(host(",\"sessions\":[]", device = "someone-else")) }
        // The phone's own id is not the desktop's: an echo of our own message is refused.
        rejected { fromHost(host(",\"sessions\":[]", device = "phone-1")) }
    }

    @Test
    fun anUnsupportedVersionIsRefused() {
        rejected {
            fromHost(
                "{\"protocolVersion\":2,\"action\":\"sessions\",\"deviceId\":\"desktop-1\",\"sessionId\":7," +
                    "\"connectionGeneration\":1,\"sessions\":[]}",
            )
        }
    }

    @Test
    fun clientAuthoredActionsFromTheHostAreRefused() {
        for (action in listOf("list", "create", "attach", "detach", "close", "resize")) {
            rejected { fromHost(host(",\"terminalId\":\"t1\",\"rows\":24,\"cols\":80", action = action)) }
        }
    }

    @Test
    fun anUnknownActionIsRefused() {
        rejected { fromHost(host("", action = "exec")) }
        rejected { fromHost(host("", action = "LIST")) }
    }

    @Test
    fun repliesMissingTheirRequiredFieldsAreRefused() {
        rejected { fromHost(host("", action = "sessions")) }
        rejected { fromHost(host("", action = "created")) }
        rejected { fromHost(host("", action = "closed")) }
        rejected { fromHost(host(",\"terminalId\":\"t1\"", action = "attached")) }
        rejected { fromHost(host(",\"offset\":1", action = "attached")) }
    }

    @Test
    fun badTerminalIdsAreRefused() {
        for (id in listOf("", "has space", "../etc/passwd", "x".repeat(129), "café", "a;b")) {
            rejected { fromHost(host(",\"terminalId\":\"$id\"", action = "created")) }
        }
        fromHost(host(",\"terminalId\":\"${"x".repeat(128)}\"", action = "created"))
    }

    @Test
    fun hostileDimensionsAreRefused() {
        rejected { fromHost(host(",\"terminalId\":\"t1\",\"rows\":0,\"cols\":80", action = "created")) }
        rejected { fromHost(host(",\"terminalId\":\"t1\",\"rows\":24,\"cols\":1001", action = "created")) }
        rejected { fromHost(host(",\"terminalId\":\"t1\",\"rows\":24,\"cols\":65535", action = "created")) }
        fromHost(host(",\"terminalId\":\"t1\",\"rows\":1000,\"cols\":1", action = "created"))
        rejected {
            fromHost(host(",\"sessions\":[{\"terminalId\":\"t1\",\"title\":\"x\",\"rows\":0,\"cols\":80}]"))
        }
    }

    @Test
    fun mistypedFieldsAreRefusedNotCoerced() {
        // org.json would coerce all of these; the desktop's serde would not.
        rejected { fromHost(host(",\"terminalId\":\"t1\",\"offset\":\"5\"", action = "attached")) }
        rejected { fromHost(host(",\"terminalId\":\"t1\",\"offset\":1.5", action = "attached")) }
        rejected { fromHost(host(",\"terminalId\":\"t1\",\"offset\":-1", action = "attached")) }
        rejected { fromHost(host(",\"terminalId\":\"t1\",\"offset\":18446744073709551615", action = "attached")) }
        rejected { fromHost(host(",\"terminalId\":\"t1\",\"rows\":\"24\"", action = "created")) }
        rejected { fromHost(host(",\"terminalId\":5", action = "created")) }
        rejected { fromHost(host(",\"sessions\":{}")) }
        rejected { fromHost(host(",\"sessions\":[5]")) }
        rejected { fromHost(host(",\"error\":5", action = "error")) }
    }

    @Test
    fun oversizedFieldsAreRefused() {
        rejected { fromHost(host(",\"error\":\"${"e".repeat(1025)}\"", action = "error")) }
        fromHost(host(",\"error\":\"${"e".repeat(1024)}\"", action = "error"))
        val many = (0..TerminalProtocol.MAX_SESSIONS).joinToString(",", "[", "]") {
            "{\"terminalId\":\"t$it\",\"title\":\"x\",\"rows\":24,\"cols\":80}"
        }
        rejected { fromHost(host(",\"sessions\":$many")) }
        rejected { fromHost(host(",\"terminalId\":\"t1\",\"exitStatus\":4294967296", action = "closed")) }
        fromHost(host(",\"terminalId\":\"t1\",\"exitStatus\":4294967295", action = "closed"))
    }

    @Test
    fun deskWindowIsReadWhenPresentAndFalseWhenAbsent() {
        fun parse(extra: String) = TerminalControl.fromJson(
            host(",\"sessions\":[{\"terminalId\":\"t1\",\"title\":\"x\",\"rows\":24,\"cols\":80$extra}]").toByteArray(),
        ).also { it.validateFromHost(wire) }.sessions!!.single()
        assertFalse(parse("").deskWindow)
        assertTrue(parse(",\"deskWindow\":true").deskWindow)
        assertFalse(parse(",\"deskWindow\":false").deskWindow)
        rejected { parse(",\"deskWindow\":\"true\"") }
        // Round trip, and omitted when false like serde's skip_serializing_if.
        val listed = listOf(TerminalSessionInfo("t1", "x", 24, 80), TerminalSessionInfo("t2", "y", 24, 80, deskWindow = true))
        val json = String(TerminalControl.create(TerminalAction.SESSIONS, wire).copy(sessions = listed).toJson())
        assertEquals(1, Regex("deskWindow").findAll(json).count())
        assertEquals(listed, TerminalControl.fromJson(json.toByteArray()).sessions)
    }

    @Test
    fun aTitleIsClippedNotTrusted() {
        val control = TerminalControl.fromJson(
            host(",\"sessions\":[{\"terminalId\":\"t1\",\"title\":\"${"x".repeat(5000)}\",\"rows\":24,\"cols\":80}]").toByteArray(),
        )
        assertEquals(TerminalProtocol.MAX_TITLE_CHARS, control.sessions!![0].title.length)
    }

    // ---- launchers -------------------------------------------------------------------------

    private fun launchers(body: String) = TerminalControl.fromJson(host(",\"sessions\":[]$body").toByteArray())
        .also { it.validateFromHost(wire) }.launchers

    @Test
    fun launchersAreOptional() {
        assertNull(launchers(""))
        assertEquals(listOf("claude", "codex"), launchers(",\"launchers\":[\"claude\",\"codex\"]"))
        assertEquals(emptyList<String>(), launchers(",\"launchers\":[]"))
    }

    @Test
    fun unknownLaunchersAreDroppedAndKnownOnesKeepAFixedOrder() {
        assertEquals(
            listOf("claude", "codex"),
            launchers(",\"launchers\":[\"codex\",\"aider\",\"claude\",\"Claude\",\"codex\"]"),
        )
    }

    @Test
    fun hostileLauncherListsAreRefused() {
        val nine = (1..TerminalProtocol.MAX_LAUNCHERS + 1).joinToString(",", "[", "]") { "\"claude\"" }
        rejected { launchers(",\"launchers\":$nine") }
        rejected { launchers(",\"launchers\":[\"${"x".repeat(TerminalProtocol.MAX_LAUNCHER_CHARS + 1)}\"]") }
        rejected { launchers(",\"launchers\":[5]") }
        rejected { launchers(",\"launchers\":\"claude\"") }
    }

    @Test
    fun launchersRoundTrip() {
        val control = TerminalControl.create(TerminalAction.SESSIONS, WebRtcWireBinding("desktop-1", "phone-1", 7, 1))
            .copy(sessions = emptyList(), launchers = listOf("claude", "codex"))
        val json = String(control.toJson())
        assertTrue(json, json.endsWith(",\"sessions\":[],\"launchers\":[\"claude\",\"codex\"]}"))
        assertEquals(control, TerminalControl.fromJson(json.toByteArray()))
    }

    @Test
    fun aPasswordRoundTripsInTheDesktopFieldOrder() {
        val control = TerminalControl.create(TerminalAction.ATTACH, wire)
            .copy(terminalId = "t1", offset = 0, password = "pässwörd \"1\"")
        val json = String(control.toJson())
        assertTrue(json, json.endsWith(",\"terminalId\":\"t1\",\"offset\":0,\"password\":\"pässwörd \\\"1\\\"\"}"))
        assertEquals(control, TerminalControl.fromJson(json.toByteArray()))
        assertFalse(String(TerminalControl.create(TerminalAction.LIST, wire).toJson()).contains("password"))
    }

    @Test
    fun aPasswordIsBoundedInUtf8BytesAndNeverEmpty() {
        val list = TerminalControl.create(TerminalAction.LIST, wire)
        // 128 two-byte characters: 256 bytes, the desktop's limit.
        list.copy(password = "é".repeat(128)).toJson()
        rejected { list.copy(password = "é".repeat(128) + "x").toJson() }
        rejected { list.copy(password = "").toJson() }
    }

    @Test
    fun malformedJsonIsRefused() {
        rejected { TerminalControl.fromJson("not json".toByteArray()) }
        rejected { TerminalControl.fromJson("[]".toByteArray()) }
        rejected { TerminalControl.fromJson("{}".toByteArray()) }
        rejected { TerminalControl.fromJson(byteArrayOf(0xff.toByte(), 0xfe.toByte())) }
    }

    @Test
    fun aCommandIsBounded() {
        rejected { fromHost(host(",\"command\":[]", action = "created")) }
        rejected { fromHost(host(",\"command\":[${List(65) { "\"x\"" }.joinToString(",")}]", action = "created")) }
        rejected { fromHost(host(",\"command\":[\"${"x".repeat(4097)}\"]", action = "created")) }
    }

    // ---- chunks ------------------------------------------------------------------------------

    @Test
    fun everyChunkKindRoundTripsArbitraryBytes() {
        val data = ByteArray(256) { it.toByte() }
        for (kind in ChunkKind.entries) {
            val chunk = TerminalChunk(kind, "0123abcd-ef", 4_294_967_296, data)
            assertEquals(chunk, TerminalChunk.decode(chunk.encode()))
        }
    }

    @Test
    fun aFrameOfExactlyTheMaximumIsAccepted() {
        val chunk = TerminalChunk(ChunkKind.OUTPUT, "t1", 0, ByteArray(TerminalProtocol.MAX_CHUNK_BYTES))
        assertEquals(chunk, TerminalChunk.decode(chunk.encode()))
    }

    @Test
    fun anOversizedChunkIsRefusedBothWays() {
        rejected { TerminalChunk(ChunkKind.OUTPUT, "t1", 0, ByteArray(TerminalProtocol.MAX_CHUNK_BYTES + 1)).encode() }
        // Hand-built: the header is honest about a payload that is over the ceiling.
        val size = TerminalProtocol.MAX_CHUNK_BYTES + 1
        val frame = ByteBuffer.allocate(19 + 2 + size)
            .put("DLT1".toByteArray()).put(0).putShort(2).putLong(0).putInt(size)
            .put("t1".toByteArray()).put(ByteArray(size)).array()
        rejected { TerminalChunk.decode(frame) }
    }

    @Test
    fun truncatedAndLyingFramesAreRefused() {
        val good = hex(GOLDEN_CHUNK_OUTPUT)
        rejected { TerminalChunk.decode(good.copyOf(good.size - 1)) }
        rejected { TerminalChunk.decode(good.copyOf(3)) }
        rejected { TerminalChunk.decode(ByteArray(0)) }
        rejected { TerminalChunk.decode(good + byteArrayOf(0)) } // trailing byte

        val hugeLength = good.copyOf().also { it.fill(0xff.toByte(), 15, 19) }
        rejected { TerminalChunk.decode(hugeLength) }

        val smallerLength = good.copyOf().also { it[18] = 1 }
        rejected { TerminalChunk.decode(smallerLength) }

        val hugeId = good.copyOf().also { it[5] = 0xff.toByte(); it[6] = 0xff.toByte() }
        rejected { TerminalChunk.decode(hugeId) }
    }

    @Test
    fun badMagicAndUnknownKindsAreRefused() {
        val good = hex(GOLDEN_CHUNK_OUTPUT)
        for (magic in listOf("DLF1", "dlt1", "DLT2", "\u0000\u0000\u0000\u0000")) {
            val bad = good.copyOf().also { magic.toByteArray().copyInto(it) }
            rejected { TerminalChunk.decode(bad) }
        }
        for (kind in listOf(3, 9, 0x80, 0xff)) {
            rejected { TerminalChunk.decode(good.copyOf().also { it[4] = kind.toByte() }) }
        }
    }

    @Test
    fun aNegativeOrHugeOffsetIsRefused() {
        val bad = hex(GOLDEN_CHUNK_OUTPUT).also { it[7] = 0x80.toByte() } // u64 above Long.MAX_VALUE
        rejected { TerminalChunk.decode(bad) }
    }

    @Test
    fun hostAuthoredKindsOnlyComeFromTheHost() {
        // An INPUT frame from the desktop would be a forged keystroke echo.
        rejected { TerminalChunk.decodeFromHost(hex(GOLDEN_CHUNK_INPUT)) }
        TerminalChunk.decodeFromHost(hex(GOLDEN_CHUNK_OUTPUT))
        TerminalChunk.decodeFromHost(hex(GOLDEN_CHUNK_SNAPSHOT))
    }

    @Test
    fun malformedChunkIdsAreRefused() {
        for (id in listOf("", "has space", "../etc/passwd", "x".repeat(129))) {
            rejected { TerminalChunk(ChunkKind.OUTPUT, id, 0, byteArrayOf(1)).encode() }
        }
        // Non-ASCII bytes in the id field.
        val frame = hex(GOLDEN_CHUNK_OUTPUT).also { it[19] = 0xc3.toByte() }
        rejected { TerminalChunk.decode(frame) }
        // idLen zero
        val zeroId = ByteBuffer.allocate(19).put("DLT1".toByteArray()).put(0).putShort(0).putLong(0).putInt(0).array()
        rejected { TerminalChunk.decode(zeroId) }
    }

    @Test
    fun decodeNeverThrowsAnythingButIllegalArgument() {
        val random = java.util.Random(42)
        val good = hex(GOLDEN_CHUNK_SNAPSHOT)
        repeat(3000) {
            val mutated = good.copyOf(random.nextInt(good.size + 4)).also { bytes ->
                repeat(random.nextInt(4)) { if (bytes.isNotEmpty()) bytes[random.nextInt(bytes.size)] = random.nextInt().toByte() }
            }
            try {
                TerminalChunk.decode(mutated)
            } catch (_: IllegalArgumentException) {
            }
        }
        assertArrayEquals(good, TerminalChunk.decode(good).encode())
    }

    private companion object {
        val GOLDEN_SNAPSHOT_DATA = byteArrayOf(0x1b, 0x63, 0x1b, 0x5b, 0x3f, 0x31, 0x30, 0x34, 0x39, 0x68) // ESC c ESC [ ? 1049 h

        const val GOLDEN_CHUNK_OUTPUT = "444c543100000200000000000001020000000274316869"
        const val GOLDEN_CHUNK_SNAPSHOT = "444c543102000900000001000000000000000a6162632d4445465f391b631b5b3f3130343968"
        const val GOLDEN_CHUNK_INPUT = "444c54310100020000000000000000000000037431037f0d"

        const val GOLDEN_ATTACHED =
            "{\"protocolVersion\":1,\"action\":\"attached\",\"deviceId\":\"desktop-1\",\"sessionId\":7," +
                "\"connectionGeneration\":1,\"terminalId\":\"t1\",\"rows\":24,\"cols\":80,\"offset\":258}"
        const val GOLDEN_SESSIONS =
            "{\"protocolVersion\":1,\"action\":\"sessions\",\"deviceId\":\"desktop-1\",\"sessionId\":7," +
                "\"connectionGeneration\":1,\"sessions\":[{\"terminalId\":\"t1\",\"title\":\"bash\",\"rows\":24,\"cols\":80}," +
                "{\"terminalId\":\"t2\",\"title\":\"vim \\\"x\\\"\",\"rows\":50,\"cols\":132,\"exitStatus\":1}]}"
        const val GOLDEN_ERROR =
            "{\"protocolVersion\":1,\"action\":\"error\",\"deviceId\":\"desktop-1\",\"sessionId\":7," +
                "\"connectionGeneration\":1,\"error\":\"terminal-not-allowed\"}"
        const val GOLDEN_CLOSED =
            "{\"protocolVersion\":1,\"action\":\"closed\",\"deviceId\":\"desktop-1\",\"sessionId\":7," +
                "\"connectionGeneration\":1,\"terminalId\":\"t1\",\"exitStatus\":0}"
        const val GOLDEN_CREATE =
            "{\"protocolVersion\":1,\"action\":\"create\",\"deviceId\":\"phone-1\",\"sessionId\":7," +
                "\"connectionGeneration\":1,\"rows\":24,\"cols\":80,\"command\":[\"tmux\",\"attach\"]}"
        const val GOLDEN_LIST =
            "{\"protocolVersion\":1,\"action\":\"list\",\"deviceId\":\"phone-1\",\"sessionId\":7,\"connectionGeneration\":1}"
        const val GOLDEN_ATTACH =
            "{\"protocolVersion\":1,\"action\":\"attach\",\"deviceId\":\"phone-1\",\"sessionId\":7," +
                "\"connectionGeneration\":1,\"terminalId\":\"t1\",\"offset\":258}"
        const val GOLDEN_RESIZE =
            "{\"protocolVersion\":1,\"action\":\"resize\",\"deviceId\":\"phone-1\",\"sessionId\":7," +
                "\"connectionGeneration\":1,\"terminalId\":\"t1\",\"rows\":40,\"cols\":100}"
    }
}
