/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

import android.util.Base64
import org.desklink.mobile.NetworkPacket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The app icon on a notification: small, named by its hash, its picture sent only when the desk lacks it. */
@RunWith(RobolectricTestRunner::class)
class AppIconsTest {
    private val wire = JSONObject(javaClass.getResourceAsStream("/shared/wire_values.json")!!.bufferedReader().readText())
        .getJSONObject("notificationAppIcon")

    @Test
    fun `the field names, sizes and hash are the desk's`() {
        assertEquals(AppIcons.HASH_FIELD, wire.getJSONObject("fields").getString("hash"))
        assertEquals(AppIcons.PNG_FIELD, wire.getJSONObject("fields").getString("png"))
        assertEquals(AppIcons.HASH_HEX_CHARS, wire.getInt("hashHexChars"))
        assertEquals(AppIcons.MAX_PNG_BYTES, wire.getInt("maxPngBytes"))
        assertEquals(AppIcons.MAX_SIDE, wire.getInt("maxSide"))
        assertTrue(AppIcons.TARGET_PNG_BYTES <= AppIcons.MAX_PNG_BYTES)
        val vector = wire.getJSONObject("vector")
        val png = Base64.decode(vector.getString("png"), Base64.DEFAULT)
        assertEquals(vector.getString("hash"), AppIcons.hash(png))
    }

    @Test
    fun `a smaller picture is tried when the 64 px one is too big, and none past 32 px`() {
        val asked = mutableListOf<Int>()
        val icon = AppIcons.encode { side -> asked += side; ByteArray(if (side == 32) 1000 else 7000) }!!
        assertEquals(listOf(64, 48, 32), asked)
        assertEquals(AppIcons.hash(ByteArray(1000)), icon.hash)
        assertEquals(1000, Base64.decode(icon.png, Base64.NO_WRAP).size)
        assertNull(AppIcons.encode { ByteArray(7000) })
        assertNull(AppIcons.encode { null })
        assertEquals(listOf(64), mutableListOf<Int>().also { sides -> AppIcons.encode { sides += it; ByteArray(10) } })
    }

    @Test
    fun `the picture goes only when the desk lacks it, the hash always`() {
        val icon = AppIcon("a".repeat(32), "iVBORw0KGgo=")
        val first = NetworkPacket("desklink.notification")
        assertTrue(AppIcons.addTo(first, icon, deskHas = false))
        assertEquals(icon.hash, first.getString(AppIcons.HASH_FIELD))
        assertEquals(icon.png, first.getString(AppIcons.PNG_FIELD))
        val again = NetworkPacket("desklink.notification")
        assertFalse(AppIcons.addTo(again, icon, deskHas = true))
        assertEquals(icon.hash, again.getString(AppIcons.HASH_FIELD))
        assertFalse(again.has(AppIcons.PNG_FIELD))
    }

    @Test
    fun `a packet too big for the picture goes without it`() {
        val full = NetworkPacket("desklink.notification").apply {
            this["text"] = "x".repeat(org.desklink.mobile.webrtc.WebRtcEnvelope.FEATURE_PACKET_BUDGET - 200)
        }
        assertFalse(AppIcons.addTo(full, AppIcon("b".repeat(32), "A".repeat(4000)), deskHas = false))
        assertFalse(full.has(AppIcons.PNG_FIELD))
        assertTrue(full.has(AppIcons.HASH_FIELD))
    }

    /** The icon is not what the desk shows as the notification's words: adding it never makes a notification "changed". */
    @Test
    fun `the icon does not change a notification's signature`() {
        val np = NetworkPacket("desklink.notification").apply { this["title"] = "Mara"; this["text"] = "hi" }
        val before = NotificationsPlugin.contentSignature(np)
        AppIcons.addTo(np, AppIcon("c".repeat(32), "iVBORw0KGgo="), deskHas = false)
        assertEquals(before, NotificationsPlugin.contentSignature(np))
    }
}
