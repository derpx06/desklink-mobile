/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID

/**
 * The cross-engine scenarios (S1-S7, S4b): JSON transcripts in `src/test/resources/transfers_v2/`, carried byte for
 * byte from the desk's repo (the desk's schema is canonical; `generate.py` there writes them with hashlib only).
 *
 * A transcript is a list of `{from: sender|receiver|test, ...}` steps. This phone replays each side its `replay`
 * field names against its own engine: every step from the other side is fed in, every `test` event for that side is
 * applied, and what the engine emits must equal that side's own steps, in order and nothing more. Controls compare as
 * parsed JSON minus the `ignore` keys; frames compare as one block each (pass, block, count, and the SHA-256 of the
 * concatenated bytes). The receiver runs through the manager (so a re-offer and the busy ordering are covered), the
 * sender through [FileSender].
 */
@RunWith(RobolectricTestRunner::class)
class TransferTranscriptsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val names = listOf("S1", "S2", "S3", "S4", "S4b", "S5", "S6", "S7")

    /** Pinned: the files are shared with the desk, so any drift between the repos fails here. */
    private val pinned = mapOf(
        "S1" to "412a7fecaf8d8acca6268a8fe5807a32cf58abc1d34131cf6de88d7a8135b338",
        "S2" to "695562745f4c0b593c3bd733c32d50f5fb492f16a124a86aaddf6688aefb5376",
        "S3" to "6a26209e5ed502d7fbfe3defa584ba224c28b708c74104f2853a83bb516c89fb",
        "S4" to "ddaf7ae7c1fb0112a4eccbc820075ab92141c62038e1304702b1e7b43fd4a8c6",
        "S4b" to "d096863050278f1481a4bf5fb4d423d4dccfd5e186943046c0c1c7b56dea1841",
        "S5" to "6c4b9b28d923ed55039b256770ccccec05e83b2a349dcc4e26aeb54c16bfc6cd",
        "S6" to "9899b56078a228ab095154a9191dff8f699a229ad517805986d4626d7b94dfa8",
        "S7" to "906d57c47914d1e3472017f377ffafdd74a4161c3b258c11aefe62f4badd58b0",
    )

    private fun text(name: String) =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("transfers_v2/$name.json")) { "missing transcript $name" }.readBytes()

    private fun load(name: String) = JSONObject(String(text(name), Charsets.UTF_8))

    @Test
    fun theTranscriptsAreTheDesksByteForByte() {
        for (name in names) {
            assertEquals("$name drifted from the desk's file", pinned.getValue(name), MessageDigest.getInstance("SHA-256").digest(text(name)).toHex())
        }
    }

    // ---------------------------------------------------------------- content and frames

    private fun contentByte(i: Long): Byte = ((((i * 2654435761L) and 0xFFFFFFFFL) shr 13) and 255).toInt().toByte()

    private fun overridesOf(array: JSONArray?): List<Pair<Int, Int>> =
        array?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getInt("offset") to o.getInt("xor") } } }.orEmpty()

    private class Content(val length: Long, val overrides: List<Pair<Int, Int>>, fail: (() -> Boolean)? = null) : FileSource {
        var failReads = false
        override fun stat() = FileSource.Stat(length, 1_700_000_000_000)
        fun byteAt(i: Long): Byte {
            var b = ((((i * 2654435761L) and 0xFFFFFFFFL) shr 13) and 255).toInt()
            overrides.forEach { (offset, xor) -> if (offset.toLong() == i) b = b xor xor }
            return b.toByte()
        }
        fun range(from: Long, to: Long) = ByteArray((to - from).toInt()) { byteAt(from + it) }
        override fun open(offset: Long): InputStream = object : InputStream() {
            var position = offset
            override fun read(): Int = error("unused")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position >= length) return -1
                if (failReads) throw IOException("the source cannot be read")
                val n = minOf(len.toLong(), length - position).toInt()
                for (i in 0 until n) b[off + i] = byteAt(position + i)
                position += n
                return n
            }
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** The frames of block [block] of [file] under [pass], as the contract defines them. */
    private fun frames(doc: JSONObject, file: Content, block: Int, pass: Int): List<ByteArray> {
        val id = UUID.fromString(doc.getJSONObject("ids").getString("transferId"))
        val start = block.toLong() * FileV2.BLOCK_BYTES
        val end = minOf(file.length, start + FileV2.BLOCK_BYTES)
        val body = file.range(start, end)
        val hash = sha(body)
        val capacity = FileV2.payloadCapacity(61_440)
        val out = mutableListOf<ByteArray>()
        var at = 0
        while (at < body.size) {
            val n = minOf(capacity, body.size - at)
            out += FileFrame.encode(id, pass, block, start + at, body, at, n, if (at + n == body.size) hash else null)
            at += n
        }
        return out
    }

    private fun digest(frames: List<ByteArray>) = sha(frames.fold(ByteArray(0)) { a, b -> a + b }).toHex()

    // ------------------------------------------------------------------- steps and messages

    private fun steps(doc: JSONObject): List<JSONObject> = doc.getJSONArray("steps").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun control(json: JSONObject) = WebRtcFileControl.fromJson(JSONObject(json.toString()))

    private fun canonical(control: WebRtcFileControl, ignore: Set<String>): String {
        val json = control.toJson().also { j -> ignore.forEach(j::remove) }
        return json.keys().asSequence().sorted().joinToString(",") { "$it=${json.get(it)}" }
    }

    private fun canonical(json: JSONObject, ignore: Set<String>) = canonical(control(json), ignore)

    private class Captured : FileLink {
        val messages = ArrayDeque<Any>() // WebRtcFileControl or ByteArray
        var spoilNextDone = false
        override fun sendControl(control: WebRtcFileControl) {
            val sent = if (control.action == WebRtcFileAction.DONE && spoilNextDone) control.copy(fileHash = "00".repeat(32)).also { spoilNextDone = false } else control
            messages.add(sent)
        }
        override fun sendFrame(message: ByteArray) { messages.add(message) }
        override fun bufferedAmount() = 0L
    }

    /** What [side] sent must equal the transcript's steps from that side, in order, and nothing more. */
    private fun compare(name: String, doc: JSONObject, side: String, captured: Captured) {
        val ignore = doc.getJSONArray("ignore").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        for ((index, step) in steps(doc).withIndex()) {
            if (step.getString("from") != side) continue
            val at = "$name $side step $index"
            if (step.has("control")) {
                val got = captured.messages.poll() as? WebRtcFileControl ?: error("$at: expected a control")
                assertEquals(at, canonical(step.getJSONObject("control"), ignore), canonical(got, ignore))
            } else if (step.has("blockFrames")) {
                val group = step.getJSONObject("blockFrames")
                val got = (0 until group.getInt("frames")).map { captured.messages.poll() as? ByteArray ?: error("$at: expected a frame") }
                got.forEach {
                    val f = FileFrame.decode(it, FileV2.FRAME_BYTES_PROVEN)
                    assertEquals("$at: block", group.getInt("block"), f.blockIndex)
                    assertEquals("$at: pass", group.getInt("pass"), f.pass)
                }
                assertEquals(at, group.getString("sha256"), digest(got))
            }
        }
        assertEquals("$name $side: the engine sent more than the transcript", emptyList<Any>(), captured.messages.toList())
    }

    private fun sides(doc: JSONObject) = doc.getJSONArray("replay").let { a -> (0 until a.length()).map { a.getString(it) } }

    private fun files(doc: JSONObject): Pair<Content, Content> {
        val length = doc.getJSONObject("file").getLong("length")
        val original = Content(length, emptyList())
        val sender = doc.optJSONObject("senderFile")?.optJSONArray("overrides")?.let { Content(length, overridesOf(it)) } ?: original
        return original to sender
    }

    // -------------------------------------------------------------------------- the sender

    private fun replaySender(name: String) {
        val doc = load(name)
        val ids = doc.getJSONObject("ids")
        val (_, source) = files(doc)
        val file = doc.getJSONObject("file")
        val wire = WebRtcWireBinding(ids.getString("sender"), ids.getString("receiver"), ids.getLong("sessionId"), ids.getLong("generation"))
        val store = FileTransferStore(tmp.newFolder())
        val captured = Captured()
        val outcomes = mutableListOf<FileSender.Outcome>()
        val sender = FileSender(
            wire, store, captured, { 0L }, object : FileSender.Listener {
                override fun progress(sender: FileSender, bytesDone: Long) = Unit
                override fun ended(sender: FileSender, outcome: FileSender.Outcome, message: String?) { outcomes += outcome }
            },
            ids.getString("transferId"), ids.getString("token"), source, file.getString("name"), file.getLong("length"), file.getLong("mtime"), emptyMap(), spooled = false,
        )
        sender.restore(emptyList()) // a fresh process: no hashes in memory
        sender.start()
        for (step in steps(doc)) {
            when {
                step.getString("from") == "receiver" -> sender.onControl(control(step.getJSONObject("control")))
                step.getString("from") == "test" && step.getString("side") == "sender" -> when (step.getString("event")) {
                    "spoil-next-done" -> captured.spoilNextDone = true
                    "source-read-error" -> source.failReads = true
                    else -> error("$name: unknown sender event")
                }
            }
        }
        compare(name, doc, "sender", captured)
        if (doc.getJSONObject("final").optBoolean("slotsFree")) {
            assertTrue("$name: the send ended", outcomes.isNotEmpty())
            assertTrue("$name: nothing left of it", store.root.listFiles().orEmpty().isEmpty())
        }
    }

    // ------------------------------------------------------------------------- the receiver

    private class ManualRunner : FileRunner {
        private val queue = ArrayDeque<Runnable>()
        override fun post(task: Runnable) = synchronized(queue) { queue.add(task); Unit }
        override fun postDelayed(delayMs: Long, task: Runnable) = post(task)
        override fun every(periodMs: Long, task: Runnable) = Unit
        fun drain() { while (true) (synchronized(queue) { queue.poll() } ?: break).run() }
    }

    private fun root() = File(ApplicationProvider.getApplicationContext<android.app.Application>().filesDir, "webrtc-transfers")

    private fun replayReceiver(name: String) {
        val doc = load(name)
        val ids = doc.getJSONObject("ids")
        val (original, senderFile) = files(doc)
        val file = doc.getJSONObject("file")
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        root().deleteRecursively()
        java.io.File(context.filesDir, "webrtc-delivered.json").delete()
        val wire = WebRtcWireBinding(ids.getString("receiver"), ids.getString("sender"), ids.getLong("sessionId"), ids.getLong("generation"))
        val captured = Captured()
        val runner = ManualRunner()
        val published = mutableListOf<ByteArray>()
        val device = mockk<Device>(relaxed = true)
        val destination = org.desklink.mobile.plugins.share.ReceiveDestination { source, name, _, mime ->
            published += source.readBytes()
            org.desklink.mobile.plugins.share.Published(android.net.Uri.parse("content://fake/$name"), name, mime, source.length())
        }
        val manager = WebRtcFileTransferManager(context, device, captured, runner, { 0L }, { Long.MAX_VALUE }, destination, java.util.concurrent.Executor { it.run() })
        // A checkpoint of the first blocks, as a crashed receiver left it.
        doc.optJSONObject("receiverHolds")?.optInt("blocks", 0)?.takeIf { it > 0 }?.let { blocks ->
            val store = FileTransferStore(root())
            val id = ids.getString("transferId")
            store.partial(id).writeBytes(original.range(0, blocks.toLong() * FileV2.BLOCK_BYTES))
            val hashes = (0 until blocks).map { sha(original.range(it.toLong() * FileV2.BLOCK_BYTES, (it + 1L) * FileV2.BLOCK_BYTES)) }
            assertTrue(store.writeBlocks(id, hashes))
            assertTrue(
                store.save(FileCheckpoint(id, "receive", ids.getString("token"), ids.getString("sender"), file.getString("name"), file.getLong("length"), file.getLong("mtime"), blocks)),
            )
        }
        for ((n, step) in steps(doc).withIndex()) {
            when {
                step.getString("from") == "sender" -> {
                    if (step.has("control")) {
                        manager.handleControl(wire, control(step.getJSONObject("control")))
                    } else {
                        val group = step.getJSONObject("blockFrames")
                        val fed = frames(doc, senderFile, group.getInt("block"), group.getInt("pass"))
                        assertEquals("$name step $n: our frames are not the contract's", group.getString("sha256"), digest(fed))
                        fed.forEach(manager::handleFrame)
                    }
                    runner.drain()
                }
                step.getString("from") == "test" && step.getString("side") == "receiver" -> {
                    when (step.getString("event")) {
                        "backlog-on" -> manager.testBacklog(true)
                        "backlog-off" -> manager.testBacklog(false)
                        else -> error("$name: unknown receiver event")
                    }
                    runner.drain()
                }
            }
        }
        compare(name, doc, "receiver", captured)
        val end = doc.getJSONObject("final")
        if (end.has("slotsFree")) {
            val empty = root().listFiles().orEmpty().isEmpty()
            assertEquals("$name: slots", end.getBoolean("slotsFree"), empty)
        }
        if (end.has("receiverPublished")) {
            if (end.getBoolean("receiverPublished")) {
                val wanted = (if (end.optBoolean("publishedIsSenderFile")) senderFile else original).range(0, file.getLong("length"))
                assertEquals("$name: one file published", 1, published.size)
                assertTrue("$name: the published bytes", wanted.contentEquals(published.single()))
            } else {
                assertEquals("$name: nothing published", 0, published.size)
            }
        }
    }

    @After
    fun cleanUp() {
        root().deleteRecursively()
    }

    private fun replay(name: String) {
        val doc = load(name)
        for (side in sides(doc)) when (side) {
            "sender" -> replaySender(name)
            "receiver" -> replayReceiver(name)
            else -> error("unknown side $side")
        }
    }

    @Test fun s1_resumedAtBlock2WithoutHashes() = replay("S1")
    @Test fun s2_aPrefixMismatchRestartsUnderPass1AndTheReceiverAdoptsIt() = replay("S2")
    @Test fun s3_pauseAndResumeCarryThePass() = replay("S3")
    @Test fun s4_aFinalHashMismatchResumesTheWholeFileOnce() = replay("S4")
    @Test fun s4b_theOlderBlockNackFormIsAccepted() = replay("S4b")
    @Test fun s5_aSourceReadErrorIsAnInternalError() = replay("S5")
    @Test fun s6_aReofferOfALiveIdIsAnswered() = replay("S6")
    @Test fun s7_busyOrdering() = replay("S7")
}
