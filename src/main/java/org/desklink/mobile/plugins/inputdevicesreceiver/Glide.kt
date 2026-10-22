/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.inputdevicesreceiver

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlin.math.roundToInt

/**
 * F4 glide: the desk's pointer and keyboard continue onto this phone across a screen edge.
 *
 * Consent is the per-desk switch "Let <desk> control this phone" (off by default); while a glide is
 * on, the desk's mousepad packets need no screen lease. One glide at a time: [activeDesk].
 */
object Glide {
    // The phone's edge the pointer entered by, from the desk's Qt exit edge (G1): the desk's bottom
    // (Qt 8) is this phone's top, and so on.
    const val TOP_EDGE = 0x8
    const val LEFT_EDGE = 0x4
    const val RIGHT_EDGE = 0x2
    const val BOTTOM_EDGE = 0x1
    const val NONE_EDGE = 0

    /** G7: at most this many G1 enters a second; a desk sends one per crossing. */
    const val MAX_ENTERS_PER_SECOND = 10

    /** A glide with no packet for this long is over: the desk went away without G4 (a pointer left idle is rarely this long). */
    const val IDLE_END_MS = 600_000L

    /** G7: the desk sends ≤ 250 motion packets a second; twice that is a flood, dropped. */
    const val MAX_PACKETS_PER_SECOND = 500

    /** G7: key text per packet. */
    const val MAX_KEY_CHARS = 16

    /** Per desk, for the coordinator: mousepad packets while gliding. */
    val packets = org.desklink.mobile.helpers.RateLimiter(MAX_PACKETS_PER_SECOND, 1000, "DeskLink/Glide")

    /** A glide packet the phone takes: within the rate and the key bound. */
    fun accepts(deviceId: String, key: String?): Boolean =
        (key == null || key.length <= MAX_KEY_CHARS) && packets.allow(deviceId)

    /** Desk px are bounded so a junk delta cannot overflow the scaling. */
    const val MAX_DESK_PX = 100_000.0

    @Volatile var activeDesk: String? = null
        private set
    @Volatile var enterEdge = NONE_EDGE
        private set
    /** G1 `edgeLength`, or null for raw pixels (KDE peers). */
    @Volatile var edgeLength: Double? = null
        private set
    @Volatile var lastPacketAt = 0L

    fun isGliding(deviceId: String, now: Long = android.os.SystemClock.elapsedRealtime()): Boolean =
        activeDesk == deviceId && enterEdge != NONE_EDGE && now - lastPacketAt < IDLE_END_MS

    fun begin(deviceId: String, edge: Int, length: Double?, now: Long = android.os.SystemClock.elapsedRealtime()) {
        activeDesk = deviceId
        enterEdge = edge
        edgeLength = length
        lastPacketAt = now
    }

    fun end() {
        enterEdge = NONE_EDGE
        activeDesk = null
        edgeLength = null
    }

    private fun key(deviceId: String) = "glide_allowed_$deviceId"

    fun allowed(context: Context, deviceId: String): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(key(deviceId), false)

    fun setAllowed(context: Context, deviceId: String, on: Boolean) =
        PreferenceManager.getDefaultSharedPreferences(context).edit { putBoolean(key(deviceId), on) }

    /** A known Qt exit edge, read as the phone's entry edge; anything else is not one. */
    fun validEdge(edge: Int) = edge == TOP_EDGE || edge == LEFT_EDGE || edge == RIGHT_EDGE || edge == BOTTOM_EDGE

    /**
     * G1: where the pointer appears on a [width]×[height] screen. Along the edge the desk's delta is
     * scaled by [length] when the desk sent it (no jump), else taken as pixels.
     */
    fun entry(edge: Int, deltax: Double, deltay: Double, length: Double?, width: Int, height: Int): Pair<Int, Int> {
        fun along(delta: Double, size: Int): Int {
            val d = delta.coerceIn(-MAX_DESK_PX, MAX_DESK_PX)
            val px = if (length != null) d * size / length else d
            return px.roundToInt().coerceIn(0, size - 1)
        }
        return when (edge) {
            TOP_EDGE -> along(deltax, width) to 1
            BOTTOM_EDGE -> along(deltax, width) to height - 1
            LEFT_EDGE -> 1 to along(deltay, height)
            else -> width - 1 to along(deltay, height)
        }
    }

    /**
     * G3: the release deltas when the pointer at ([x], [y]) has reached the edge it entered by, in
     * the desk's units; null while it is still inside.
     */
    fun release(edge: Int, x: Int, y: Int, length: Double?, width: Int, height: Int): Pair<Int, Int>? {
        val atEdge = when (edge) {
            TOP_EDGE -> y <= 0
            BOTTOM_EDGE -> y >= height
            LEFT_EDGE -> x <= 0
            RIGHT_EDGE -> x >= width
            else -> false
        }
        return if (atEdge) handBack(edge, x, y, length, width, height) else null
    }

    /** G3 deltas for the pointer at ([x], [y]) wherever it is: along the entry edge, in the desk's units. */
    fun handBack(edge: Int, x: Int, y: Int, length: Double?, width: Int, height: Int): Pair<Int, Int> {
        fun back(px: Int, size: Int): Int = if (length != null && size > 0) (px.toDouble() * length / size).roundToInt() else px
        return when (edge) {
            TOP_EDGE, BOTTOM_EDGE -> back(x.coerceIn(0, width), width) to 0
            else -> 0 to back(y.coerceIn(0, height), height)
        }
    }
}
