/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import org.desklink.mobile.NetworkPacket
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** A notifying app's launcher icon as the desk gets it: the PNG (base64) and its hash. */
internal class AppIcon(val hash: String, val png: String)

/**
 * The app icon on a mirrored notification (wire: `shared/wire_values.json`, `notificationAppIcon`).
 *
 * Every notification names its app's icon by [HASH_FIELD]; the picture itself ([PNG_FIELD], a PNG of at most
 * [MAX_SIDE] px, base64) rides only on the first one of an app per connection, so nothing is resent per notification.
 * It is the app's public launcher icon, never a picture from the notification. A desk without this keeps working:
 * both fields are optional.
 */
internal object AppIcons {
    const val HASH_FIELD = "appIconHash"
    const val PNG_FIELD = "appIcon"
    /** The first 128 bits of the PNG's SHA-256, in hex. */
    const val HASH_HEX_CHARS = 32
    /** What the desk takes at most. */
    const val MAX_PNG_BYTES = 8 * 1024
    const val MAX_SIDE = 64
    /** The size aimed for: a smaller picture is tried when the 64 px one is bigger. */
    const val TARGET_PNG_BYTES = 6 * 1024
    private val SIDES = listOf(MAX_SIDE, 48, 32)

    /** By package, for the life of the process: rendered once (an app whose icon cannot be read is remembered too). */
    private class Entry(val icon: AppIcon?)
    private val cache = LruCache<String, Entry>(64)

    fun forPackage(context: Context, packageName: String): AppIcon? {
        cache.get(packageName)?.let { return it.icon }
        val icon = runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            encode { side -> png(drawable.toBitmap(side, side, Bitmap.Config.ARGB_8888)) }
        }.getOrNull()
        cache.put(packageName, Entry(icon))
        return icon
    }

    private fun png(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** The largest of 64, 48 and 32 px whose PNG is at most [TARGET_PNG_BYTES]; none when even 32 px is bigger. */
    fun encode(render: (Int) -> ByteArray?): AppIcon? =
        SIDES.firstNotNullOfOrNull { side -> render(side)?.takeIf { it.size <= TARGET_PNG_BYTES } }
            ?.let { AppIcon(hash(it), Base64.encodeToString(it, Base64.NO_WRAP)) }

    fun hash(png: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }.take(HASH_HEX_CHARS)

    /**
     * Names [icon] on [np], with the picture unless [deskHas] it; the picture is left out when the packet would not cross the
     * data channel with it. Returns whether the picture went on.
     */
    fun addTo(np: NetworkPacket, icon: AppIcon, deskHas: Boolean): Boolean {
        np[HASH_FIELD] = icon.hash
        if (deskHas) return false
        np[PNG_FIELD] = icon.png
        if (np.serialize().toByteArray().size <= org.desklink.mobile.webrtc.WebRtcEnvelope.FEATURE_PACKET_BUDGET) return true
        np[PNG_FIELD] = null as org.json.JSONArray?
        return false
    }
}
