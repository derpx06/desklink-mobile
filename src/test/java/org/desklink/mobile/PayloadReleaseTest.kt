/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import org.desklink.mobile.webrtc.FileSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class PayloadReleaseTest {
    private class Counting : FileSource {
        var closed = 0
        override fun stat() = FileSource.Stat(1, 1)
        override fun open(offset: Long): InputStream = ByteArrayInputStream(ByteArray(1))
        override fun close() { closed++ }
    }

    @Test
    fun aPacketThatWillNotBeSentGivesBackItsFile() {
        val source = Counting()
        val payload = NetworkPacket.Payload(ByteArrayInputStream(ByteArray(1)), 1).also { it.source = source }
        payload.close()
        assertEquals("close alone leaves the file to the transfer that may still resume it", 0, source.closed)
        payload.release()
        assertEquals(1, source.closed)
    }
}
