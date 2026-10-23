/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.clipboard

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileInputStream

/** The hand-off the v2 receiver makes for an image copy: what comes back false is a failure the sender is told about. */
@RunWith(RobolectricTestRunner::class)
class ClipboardImagesReceiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun packet(file: File, size: Long) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
        this["filename"] = "copy.png"
        this["clipboard"] = true
        this["mimeType"] = ClipboardImages.MIME
        payload = NetworkPacket.Payload(FileInputStream(file), size) { file.delete() }
    }

    @Test
    fun somethingThatIsNotAPngIsRefusedAndItsFileIsGone() {
        val file = tmp.newFile("not-a-png").apply { writeBytes(ByteArray(64) { 1 }) }
        assertFalse(ClipboardImages.receive(ApplicationProvider.getApplicationContext<Application>(), packet(file, 64)))
        assertFalse("the temporary file is deleted", file.exists())
    }

    @Test
    fun aPictureOverTheLimitIsRefused() {
        val file = tmp.newFile("big").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) }
        assertFalse(ClipboardImages.receive(ApplicationProvider.getApplicationContext<Application>(), packet(file, ClipboardImages.MAX_BYTES + 1)))
    }

    @Test
    fun aPngIsTaken() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        val file = tmp.newFile("a.png").apply { writeBytes(png) }
        assertTrue(ClipboardImages.receive(ApplicationProvider.getApplicationContext<Application>(), packet(file, png.size.toLong())))
    }
}
