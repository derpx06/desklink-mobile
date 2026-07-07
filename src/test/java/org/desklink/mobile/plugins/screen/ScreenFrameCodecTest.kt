package org.desklink.mobile.plugins.screen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenFrameCodecTest {
    @Test
    fun roundTripsHeaderAndPayload() {
        val header = ScreenFrameHeader(
            streamId = "desktop",
            sequence = 7,
            width = 1280,
            height = 720,
            format = ScreenFrameFormat.JPEG,
            timestampMillis = 1_234_567
        )

        val encoded = ScreenFrameCodec.encode(header, "frame-bytes".toByteArray())
        val decoded = ScreenFrameCodec.decode(encoded)

        assertEquals(header, decoded.header)
        assertArrayEquals("frame-bytes".toByteArray(), decoded.payload)
    }

    @Test(expected = ScreenFrameCodecException.Truncated::class)
    fun rejectsTruncatedMessages() {
        ScreenFrameCodec.decode(byteArrayOf(0, 0, 0, 20, '{'.code.toByte()))
    }
}
