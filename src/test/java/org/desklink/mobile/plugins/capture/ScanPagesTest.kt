/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.capture

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A scan holds bitmaps until it is sent: the page count and each page's size are bounded. */
@RunWith(RobolectricTestRunner::class)
class ScanPagesTest {
    @Test
    fun aScanHoldsTwelvePagesAtMost() {
        assertEquals(12, ScanImage.MAX_PAGES)
        // 12 pages at the kept size are a fraction of what 12 full photos would be.
        val perPage = ScanImage.PAGE_SIDE.toLong() * ScanImage.PAGE_SIDE * 4
        val full = ScanImage.MAX_SIDE.toLong() * ScanImage.MAX_SIDE * 4
        assertTrue(ScanImage.MAX_PAGES * perPage < ScanImage.MAX_PAGES * full / 2)
    }

    @Test
    fun aKeptPageIsShrunkToAnA4At200DpiAndASmallOneIsLeftAlone() {
        val big = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888)
        val kept = ScanImage.forPage(big)
        assertEquals(ScanImage.PAGE_SIDE, maxOf(kept.width, kept.height))
        assertEquals("the aspect ratio holds", 2400f / 1800f, kept.width.toFloat() / kept.height, 0.01f)
        val small = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        assertSame(small, ScanImage.forPage(small))
    }
}
