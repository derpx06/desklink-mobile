/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.mpris

import android.graphics.Bitmap
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MprisArtTest {
    private fun jpeg(side: Int): String {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFD03020.toInt()) }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    @Test
    fun aDeskCoverDecodesAndIsCachedByItsId() {
        val bitmap = MprisArt.decode(jpeg(320))
        assertNotNull(bitmap)
        assertEquals(320, bitmap!!.width)
        val art = MprisArt.put("abc123", bitmap)
        assertSame(art, MprisArt.get("abc123"))
        assertNull(MprisArt.get("other"))
    }

    @Test
    fun oversizedOrMalformedDataIsRefusedBeforeDecoding() {
        assertNull(MprisArt.decode(""))
        assertNull(MprisArt.decode("A".repeat(MprisArt.MAX_DATA_CHARS + 1)))
        assertNull(MprisArt.decode("not base64 at all!!"))
        // Base64 of something that is not an image.
        assertNull(MprisArt.decode(Base64.encodeToString(ByteArray(100) { 7 }, Base64.NO_WRAP)))
        // An image far bigger than the desk's 320 px covers.
        assertNull(MprisArt.decode(jpeg(MprisArt.MAX_SIDE + 1)))
    }

    @Test
    fun idsAreShortAndPlain() {
        assertTrue(MprisArt.validId("3f2a-9b_c"))
        assertFalse(MprisArt.validId(""))
        assertFalse(MprisArt.validId(null))
        assertFalse(MprisArt.validId("../x"))
        assertFalse(MprisArt.validId("a".repeat(129)))
    }
}
