/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.mpris

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Covers the desk sends inline (`albumArtData`, base64 JPEG ≤ 320 px and ≤ 48 KB, with an
 * `albumArtId` hash), and http(s) covers, each with its colour. Decoded off the main thread by the
 * caller; anything over the limits is refused before it is decoded.
 */
object MprisArt {
    const val MAX_BYTES = 48 * 1024
    /** Base64 of [MAX_BYTES], with room for line breaks. */
    const val MAX_DATA_CHARS = MAX_BYTES / 3 * 4 + 1024
    /** The desk scales to 320 px; anything far larger is not its cover. */
    const val MAX_SIDE = 640
    private val ID = Regex("[A-Za-z0-9_-]{1,128}")

    class Art(val image: ImageBitmap, val vibrant: Int?)

    private val cache = LruCache<String, Art>(16)

    fun validId(id: String?): Boolean = id != null && ID.matches(id)

    fun get(id: String?): Art? = id?.let { cache.get(it) }

    /** The cover in [data], or null when it is too big, not base64 or not an image of sane size. */
    fun decode(data: String): Bitmap? {
        if (data.isEmpty() || data.length > MAX_DATA_CHARS) return null
        val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_BYTES || !isJpegOrPng(bytes)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_SIDE || bounds.outHeight !in 1..MAX_SIDE) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun isJpegOrPng(b: ByteArray): Boolean =
        (b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) ||
            (b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte())

    /** Caches [bitmap] under [key] with its colour (from a 16×16 copy). */
    fun put(key: String, bitmap: Bitmap): Art {
        val small = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        val pixels = IntArray(16 * 16).also { small.getPixels(it, 0, 16, 0, 0, 16, 16) }
        if (small !== bitmap) small.recycle()
        return Art(bitmap.asImageBitmap(), MediaColors.vibrant(pixels)).also { cache.put(key, it) }
    }

    /** An http(s) cover from [AlbumArtCache], analysed once per URL. */
    fun forUrl(url: String, bitmap: Bitmap): Art = cache.get(url) ?: put(url, bitmap)
}
