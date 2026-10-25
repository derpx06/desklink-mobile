/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTextBytesTest {
    @Test
    fun aTextIsCutByUtf8BytesNotByCharacters() {
        val chinese = "中".repeat(12_000) // 36 000 bytes, 12 000 characters
        val cut = CaptureText.fit(chinese)
        assertTrue("fits in 32 KiB: ${cut.toByteArray().size}", cut.toByteArray().size <= 32 * 1024)
        assertEquals("whole characters only", 0, cut.toByteArray().size % 3)
        assertEquals("short text untouched", "hello", CaptureText.fit("hello"))
    }

    @Test
    fun aSupplementaryCharacterIsNeverSplit() {
        val emoji = "😀".repeat(10_000) // 4 bytes each
        val cut = CaptureText.fit(emoji, maxBytes = 1001)
        assertEquals(250, cut.codePointCount(0, cut.length))
        assertTrue(cut.toByteArray().size <= 1001)
    }
}
