package org.desklink.mobile.plugins

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.verify
import org.junit.After
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.plugins.clipboard.ClipboardListener
import org.desklink.mobile.plugins.clipboard.ClipboardPlugin

@RunWith(AndroidJUnit4::class)
class ClipboardPluginTest {
    private lateinit var clipboardPlugin: ClipboardPlugin
    private lateinit var clipboardListener: ClipboardListener
    private lateinit var context: Application
    private lateinit var device: Device
    private var packet: NetworkPacket? = null

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        val realListener = ClipboardListener.instance(context)
        clipboardListener = spyk(realListener)

        mockkObject(ClipboardListener.Companion)
        every { ClipboardListener.instance(context) } returns clipboardListener

        device = mockk {
            every { name } returns "Desk"
            every { sendPacket(any()) } answers {
                packet = arg<NetworkPacket>(0)
            }
            every { sendPacket(any(), any()) } answers {
                packet = arg<NetworkPacket>(0)
            }
        }

        clipboardPlugin = ClipboardPlugin().apply {
            setContext(context, device)
        }
    }

    @After
    fun cleanup() {
        packet = null // Remove old capture packet
        io.mockk.unmockkAll() // the object/static mocks must not leak into the next test class
    }

    // REMOTE -> LOCAL

    @Test
    fun testReceiveAndApplyClipboardUpdate() {
        val content = "Gr5hDjL68YQKfH6pez7n59Dm6"
        val packet = NetworkPacket("desklink.clipboard").apply {
            set("content", content)
        }

        Assert.assertTrue(clipboardPlugin.onPacketReceived(packet))

        // Verify that the clipboard content has been set
        verify(exactly = 1) { clipboardListener.setText(content) }
    }

    @Test
    fun testReceiveAndDiscardClipboardUpdateTimestampZero() {
        val content = "DLWq7RvblSa6zFPrwLjs9JAdA"
        val timestamp = System.currentTimeMillis()
        val packet = NetworkPacket("desklink.clipboard.connect").apply {
            set("content", content)
            set("timestamp", timestamp)
        }

        every { clipboardListener.updateTimestamp } returns 0L // Existing timestamp is invalid

        Assert.assertTrue(clipboardPlugin.onPacketReceived(packet))

        // Verify that the clipboard content is updated
        verify(exactly = 1) { clipboardListener.setText(content) }
    }

    @Test
    fun testReceiveAndDiscardClipboardUpdateTimestampOld() {
        val content = "2aZB2x22brdYSubSPPDr864LW"
        val timestamp = System.currentTimeMillis() - 1000 // Simulating an older timestamp
        val packet = NetworkPacket("desklink.clipboard.connect").apply {
            set("content", content)
            set("timestamp", timestamp)
        }

        every { clipboardListener.updateTimestamp } returns timestamp + 1000

        Assert.assertFalse(clipboardPlugin.onPacketReceived(packet))

        // Verify that the clipboard content is NOT updated
        verify(exactly = 0) { clipboardListener.setText(content) }
    }

    @Test(expected = UnsupportedOperationException::class)
    fun testReceiveInvalidPacket() {
        val invalidPacket = NetworkPacket("invalid.type")
        clipboardPlugin.onPacketReceived(invalidPacket) // Should throw exception
    }

    // LOCAL -> REMOTE

    @Test
    fun testOnCreate() {
        val content = "B7n30xe0NNO6Y1J7PXOFj6pGd"
        every { clipboardListener.currentContent } returns content
        every { clipboardListener.updateTimestamp } returns 0L

        Assert.assertTrue(clipboardPlugin.onCreate())

        val sentPacket = checkNotNull(packet)
        Assert.assertEquals("desklink.clipboard.connect", sentPacket.type)
        Assert.assertEquals(0, sentPacket.getLong("timestamp"))
        Assert.assertEquals(content, sentPacket.getString("content"))
    }

    @Test
    fun testPropagateClipboard() {
        val content = "llY3kfZNhPur9ldTWEuHQBHPC"
        every { clipboardListener.currentContent } returns content

        clipboardPlugin.propagateClipboard(content)

        val sentPacket = checkNotNull(packet)
        Assert.assertEquals("desklink.clipboard", sentPacket.type)
        Assert.assertEquals(content, sentPacket.getString("content"))
    }

    // W11: every outgoing clipboard packet carries when it was copied, never later than now (shared fixture clipboard_bodies.json).
    private fun cases() = org.json.JSONObject(javaClass.getResourceAsStream("/shared/clipboard_bodies.json")!!.bufferedReader().readText())
        .getJSONArray("cases").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test
    fun everyOutgoingClipboardPacketCarriesWhenItWasCopied() {
        for (case in cases()) {
            val now = System.currentTimeMillis()
            packet = null
            when (case.getString("type")) {
                "desklink.clipboard" -> clipboardPlugin.propagateClipboard(case.getString("content"), now - case.getLong("copiedAtMsBeforeNow"))
                "desklink.clipboard.connect" -> {
                    every { clipboardListener.currentContent } returns case.getString("content")
                    every { clipboardListener.updateTimestamp } returns now - case.getLong("timestampMsBeforeNow")
                    clipboardPlugin.onFeatureReady()
                }
                else -> {
                    packet = org.desklink.mobile.plugins.clipboard.ClipboardImages.packet(ByteArray(8), now - case.getLong("copiedAtMsBeforeNow"))
                }
            }
            val sent = checkNotNull(packet) { case.getString("name") }
            assertEquals(case.getString("name"), case.getString("type"), sent.type)
            val keys = case.getJSONArray("expectKeys")
            for (i in 0 until keys.length()) Assert.assertTrue("${case.getString("name")} has ${keys.getString(i)}", sent.has(keys.getString(i)))
            val copiedAt = sent.getLong("copiedAt")
            Assert.assertTrue("${case.getString("name")}: copiedAt $copiedAt is not in the future", copiedAt in 1..System.currentTimeMillis())
            if (case.has("expectCopiedAtMsBeforeNow")) Assert.assertTrue(now - copiedAt <= 2_000)
            if (case.optBoolean("copiedAtEqualsTimestamp")) assertEquals(sent.getLong("timestamp"), copiedAt)
            if (case.has("copiedAtMsBeforeNow") && !case.has("expectCopiedAtMsBeforeNow")) assertEquals(now - case.getLong("copiedAtMsBeforeNow"), copiedAt)
        }
    }

    @Test
    fun theCopyTimeIsTheClipsOwnStampThenWhenItWasSeenAndNeverInTheFuture() {
        assertEquals(1_000L, ClipboardListener.CopyTime.of(clipStamp = 1_000, seenAt = 2_000, now = 5_000))
        assertEquals(2_000L, ClipboardListener.CopyTime.of(0, 2_000, 5_000))
        assertEquals(5_000L, ClipboardListener.CopyTime.of(0, 0, 5_000))
        assertEquals(5_000L, ClipboardListener.CopyTime.of(9_999, 0, 5_000))
    }
}
