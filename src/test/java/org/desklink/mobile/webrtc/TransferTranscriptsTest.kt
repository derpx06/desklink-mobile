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
 * The cross-engine scenarios S1-S7: each is a JSON transcript in `src/test/resources/transfers_v2/`, byte-identical
 * to the desk's, that each side's engine replays against itself. Steps from "desk" are fed to this phone's engine;
 * steps from "phone" are what it must emit, in order, and nothing else. Controls are compared parsed (without their
 * human `message`), frames byte for byte.
 */
@RunWith(RobolectricTestRunner::class)
class TransferTranscriptsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val wire = WebRtcWireBinding("phone-device", "desk-device", 9, 2)

    private fun load(name: String): JSONObject =
        JSONObject(requireNotNull(javaClass.classLoader!!.getResourceAsStream("transfers_v2/$name.json")) { "missing transcript $name" }.reader().readText())

    // -------------------------------------------------------------- the file, and what a step means

    private fun byteAt(i: Long): Byte = (((i * 31) + (i shr 12)) and 0xff).toByte()
    private fun pattern(from: Long, to: Long) = ByteArray((to - from).toInt()) { byteAt(from + it) }
    private fun blockHash(block: Int, size: Long) = MessageDigest.getInstance("SHA-256").digest(
        pattern(block.toLong() * FileV2.BLOCK_BYTES, minOf(size, (block + 1L) * FileV2.BLOCK_BYTES)),
    )

    private class PatternSource(val size: Long, val failFrom: Long? = null) : FileSource {
        override fun stat() = FileSource.Stat(size, 1_700_000_000_000)
        override fun open(offset: Long): InputStream = object : InputStream() {
            var position = offset
            override fun read(): Int = error("unused")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position >= size) return -1
                if (failFrom != null && position >= failFrom) throw IOException("the source cannot be read")
                val n = minOf(len.toLong(), size - position, (failFrom ?: Long.MAX_VALUE) - position).toInt()
                for (i in 0 until n) b[off + i] = (((position + i) * 31 + ((position + i) shr 12)) and 0xff).toByte()
                position += n
                return n
            }
        }
    }

    private fun control(doc: JSONObject, step: JSONObject): WebRtcFileControl {
        val json = JSONObject(step.getJSONObject("control").toString())
        json.put("protocolVersion", 2)
        json.put("transferId", json.optString("transferId", doc.getString("transferId")))
        json.put("deviceId", if (step.getString("from") == "phone") doc.getString("phoneDeviceId") else doc.getString("deskDeviceId"))
        json.put("sessionId", doc.getLong("sessionId"))
        json.put("connectionGeneration", doc.getLong("connectionGeneration"))
        json.put("transferToken", doc.getString("transferToken"))
        return WebRtcFileControl.fromJson(json)
    }

    private fun canonical(control: WebRtcFileControl): String {
        val json = control.toJson().also { it.remove("message") }
        return json.keys().asSequence().sorted().joinToString(",") { "$it=${json.get(it)}" }
    }

    private fun frameBytes(doc: JSONObject, step: JSONObject): ByteArray {
        val f = step.getJSONObject("frame")
        val size = doc.getJSONObject("file").getLong("size")
        val offset = f.getLong("offset")
        val length = f.getInt("length")
        val block = (offset / FileV2.BLOCK_BYTES).toInt()
        var trailer = if (f.getBoolean("last")) blockHash(block, size) else null
        if (f.optString("trailer") == "bad") trailer = trailer!!.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        return FileFrame.encode(UUID.fromString(doc.getString("transferId")), f.getInt("pass"), block, offset, pattern(offset, offset + length), 0, length, trailer)
    }

    private fun steps(doc: JSONObject): List<JSONObject> = doc.getJSONArray("steps").let { a: JSONArray -> (0 until a.length()).map { a.getJSONObject(it) } }

    // ------------------------------------------------------------------ engine scenarios

    private class Captured : FileLink {
        val messages = ArrayDeque<Any>() // WebRtcFileControl or ByteArray
        override fun sendControl(control: WebRtcFileControl) { messages.add(control) }
        override fun sendFrame(message: ByteArray) { messages.add(message) }
        override fun bufferedAmount() = 0L
    }

    private fun expect(doc: JSONObject, step: JSONObject, actual: Any?, at: String) {
        if (step.has("control")) {
            val got = actual as? WebRtcFileControl ?: error("$at: expected a control, got ${actual?.javaClass?.simpleName}")
            assertEquals(at, canonical(control(doc, step)), canonical(got))
        } else {
            val got = actual as? ByteArray ?: error("$at: expected a frame, got ${actual?.javaClass?.simpleName}")
            val want = frameBytes(doc, step)
            assertTrue("$at: frame differs", want.contentEquals(got))
        }
    }

    private fun replaySender(name: String) {
        val doc = load(name)
        val file = doc.getJSONObject("file")
        val size = file.getLong("size")
        val state = doc.getJSONObject("senderState")
        val source = PatternSource(size, failFrom = if (state.getString("source") == "read-error-at-block-1") FileV2.BLOCK_BYTES.toLong() else null)
        val store = FileTransferStore(tmp.newFolder())
        val link = Captured()
        val outcomes = mutableListOf<FileSender.Outcome>()
        val sender = FileSender(
            wire, store, link, { 0L }, object : FileSender.Listener {
                override fun progress(sender: FileSender, bytesDone: Long) = Unit
                override fun ended(sender: FileSender, outcome: FileSender.Outcome, message: String?) { outcomes += outcome }
            },
            doc.getString("transferId"), doc.getString("transferToken"), source, file.getString("name"), size, 1_700_000_000_000, emptyMap(), spooled = false,
        )
        sender.restore(emptyList())
        sender.start()
        var phoneStepsSeen = 0
        for ((n, step) in steps(doc).withIndex()) {
            if (step.getString("from") == "phone") {
                phoneStepsSeen++
                expect(doc, step, link.messages.poll(), "$name step $n")
            } else {
                if (step.has("control")) sender.onControl(control(doc, step))
            }
        }
        assertEquals("$name: nothing the transcript does not list", emptyList<Any>(), link.messages.toList())
        doc.optJSONObject("end")?.let { end ->
            assertEquals(FileSender.Outcome.FAILED, outcomes.last())
            assertTrue("both ends keep nothing", store.root.listFiles().orEmpty().isEmpty())
            assertEquals("failed", end.getString("senderOutcome"))
        } ?: assertEquals(listOf(FileSender.Outcome.COMPLETED), outcomes)
        assertTrue(phoneStepsSeen > 0)
    }

    private fun replayReceiver(name: String) {
        val doc = load(name)
        val store = FileTransferStore(tmp.newFolder())
        val link = Captured()
        val outcomes = mutableListOf<FileReceiver.Outcome>()
        var receiver: FileReceiver? = null
        val listener = object : FileReceiver.Listener {
            override fun progress(receiver: FileReceiver, bytesDone: Long) = Unit
            override fun deliver(receiver: FileReceiver, partial: File, fileHash: String) { partial.delete() }
            override fun ended(receiver: FileReceiver, outcome: FileReceiver.Outcome, message: String?) { outcomes += outcome }
        }
        for ((n, step) in steps(doc).withIndex()) {
            val at = "$name step $n"
            when {
                step.getString("from") == "phone" && step.has("event") -> when (step.getString("event")) {
                    "backlog" -> receiver!!.pauseBacklog()
                    "drained" -> receiver!!.resumeBacklog()
                    else -> error("unknown event")
                }
                step.getString("from") == "phone" -> expect(doc, step, link.messages.poll(), at)
                step.has("frame") -> receiver!!.onFrame(FileFrame.decode(frameBytes(doc, step), FileV2.FRAME_BYTES_PROVEN))
                else -> {
                    val c = control(doc, step)
                    when (c.action) {
                        WebRtcFileAction.OFFER -> receiver = FileReceiver(wire, store, link, { 0L }, { Long.MAX_VALUE }, listener, c).also { it.start(store.load(c.transferId)) }
                        WebRtcFileAction.DONE -> receiver!!.onDone(c)
                        else -> error("$at: the engine does not take a ${c.action} from the sender")
                    }
                }
            }
        }
        assertEquals("$name: nothing the transcript does not list", emptyList<Any>(), link.messages.toList())
        assertEquals(listOf(FileReceiver.Outcome.DELIVERED), outcomes)
        assertTrue(store.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun s1_aSenderResumedWithoutItsHashesReadsThePrefixAgainAndCompletes() = replaySender("S1")
    @Test fun s2_aReceiverAdoptsThePassOfTheFirstFrameAfterTheAccept() = replayReceiver("S2")
    @Test fun s3_aBacklogPauseAndResumeCarryThePassThrough() = replayReceiver("S3")
    @Test fun s4_aWrongFinalHashRestartsWithAResumeAtZeroUnderTheNextPass() = replayReceiver("S4")
    @Test fun s5_aSourceReadErrorEndsWithAnInternalErrorAndFreesBothSlots() = replaySender("S5")

    // ------------------------------------------------------------------ manager scenarios

    private class ManualRunner : FileRunner {
        private val queue = ArrayDeque<Runnable>()
        override fun post(task: Runnable) = synchronized(queue) { queue.add(task); Unit }
        override fun postDelayed(delayMs: Long, task: Runnable) = post(task)
        override fun every(periodMs: Long, task: Runnable) = Unit
        fun drain() { while (true) (synchronized(queue) { queue.poll() } ?: break).run() }
    }

    private fun replayManager(name: String) {
        val doc = load(name)
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        File(context.filesDir, "webrtc-transfers").deleteRecursively()
        val link = Captured()
        val runner = ManualRunner()
        val device = mockk<Device>(relaxed = true).also { every { it.onWebRtcPacketReceived(any()) } returns Unit }
        val manager = WebRtcFileTransferManager(context, device, link, runner, { 0L }, { Long.MAX_VALUE })
        for ((n, step) in steps(doc).withIndex()) {
            val at = "$name step $n"
            if (step.getString("from") == "phone") {
                runner.drain()
                expect(doc, step, link.messages.poll(), at)
            } else {
                manager.handleControl(wire, control(doc, step))
                runner.drain()
            }
        }
        runner.drain()
        assertEquals("$name: nothing the transcript does not list", emptyList<Any>(), link.messages.toList())
    }

    @After
    fun cleanUp() {
        File(ApplicationProvider.getApplicationContext<android.app.Application>().filesDir, "webrtc-transfers").deleteRecursively()
    }

    @Test fun s6_aReofferOfALiveIdIsAnsweredWithAnAcceptFromTheLiveState() = replayManager("S6")
    @Test fun s7_anUnrequestedBucketFileIsRefusedNotToldBusyWhenBothSlotsAreTaken() = replayManager("S7")
}
