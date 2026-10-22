/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mprisreceiver

import android.graphics.Bitmap
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Wire size fix: the phone's cover is capped like the desk's, and its packet crosses the data channel. */
@RunWith(RobolectricTestRunner::class)
class AlbumArtWireTest {
    @Test
    fun aBigCoverIsCappedAndItsPacketCrosses() {
        val art = Bitmap.createBitmap(3000, 3000, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 3000 step 7) for (y in 0 until 3000 step 7) setPixel(x, y, (x * 31 + y * 17) or 0xFF000000.toInt())
        }
        val jpeg = MprisReceiverCallback.cappedJpeg(art)
        if (jpeg != null) {
            assertTrue(jpeg.size <= MprisReceiverCallback.MAX_ART_BYTES)
            val packet = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_MPRIS).apply {
                this["player"] = "Music"
                this["albumArtUrl"] = "file:///art/123"
                this["albumArtData"] = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
            }
            org.desklink.mobile.webrtc.assertAllCross(listOf(packet))
        }
        // At the cap itself the packet still crosses.
        val atCap = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_MPRIS).apply {
            this["albumArtData"] = android.util.Base64.encodeToString(ByteArray(MprisReceiverCallback.MAX_ART_BYTES), android.util.Base64.NO_WRAP)
        }
        org.desklink.mobile.webrtc.assertAllCross(listOf(atCap))
    }
}
