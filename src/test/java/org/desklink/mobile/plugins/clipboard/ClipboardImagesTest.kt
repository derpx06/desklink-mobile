package org.desklink.mobile.plugins.clipboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.plugins.share.LinkFromDesk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ClipboardImagesTest {
    @Test
    fun onlyAFlaggedPngIsAClipboardImage() {
        val np = ClipboardImages.packet(byteArrayOf(1, 2, 3))
        assertTrue(ClipboardImages.isClipboardImage(np))
        assertEquals("clipboard.png", np.getString("filename"))
        assertFalse(ClipboardImages.isClipboardImage(NetworkPacket("desklink.share.request").apply { set("filename", "a.png") }))
        assertFalse(ClipboardImages.isClipboardImage(NetworkPacket("desklink.share.request").apply { set("clipboard", true); set("mimeType", "image/jpeg") }))
    }

    @Test
    fun thePngSignatureIsChecked() {
        assertTrue(ClipboardImages.isPng(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertFalse(ClipboardImages.isPng("GIF89a...".toByteArray()))
        assertFalse(ClipboardImages.isPng(ByteArray(3)))
    }

    @Test
    fun tempFilesGoAfterTenMinutes() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val old = File(dir, "old.png").apply { writeText("x"); setLastModified(1_000) }
        val fresh = File(dir, "fresh.png").apply { writeText("x"); setLastModified(1_000 + ClipboardImages.KEEP_MS - 1) }
        assertEquals(listOf(old), ClipboardImages.expired(listOf(old, fresh), now = 1_000 + ClipboardImages.KEEP_MS))
        dir.deleteRecursively()
    }

    @Test
    fun openOnDeskFindsTheLinkInSharedText() {
        assertEquals("https://example.com/a?b=1", LinkFromDesk.firstLink("Read this: https://example.com/a?b=1."))
        assertEquals("http://x.org/p", LinkFromDesk.firstLink("Title\nhttp://x.org/p"))
        assertNull(LinkFromDesk.firstLink("no link here"))
    }
}
