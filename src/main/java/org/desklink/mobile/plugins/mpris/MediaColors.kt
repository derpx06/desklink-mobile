/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.mpris

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Colour from the cover, kept readable. ARGB ints, so it is testable without Android. */
object MediaColors {
    /** WCAG relative luminance. */
    fun luminance(argb: Int): Double {
        fun channel(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * channel(argb shr 16 and 0xff) + 0.7152 * channel(argb shr 8 and 0xff) + 0.0722 * channel(argb and 0xff)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** [over] laid on [base] at [amount] (0..1), opaque. */
    fun blend(base: Int, over: Int, amount: Double): Int {
        fun mix(shift: Int) = (((base shr shift and 0xff) * (1 - amount)) + ((over shr shift and 0xff) * amount)).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun saturationAndLightness(argb: Int): Pair<Double, Double> {
        val r = (argb shr 16 and 0xff) / 255.0
        val g = (argb shr 8 and 0xff) / 255.0
        val b = (argb and 0xff) / 255.0
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val l = (hi + lo) / 2
        val s = if (hi == lo) 0.0 else (hi - lo) / (1 - abs(2 * l - 1))
        return s to l
    }

    /**
     * A vibrant colour of a downscaled cover: the average of its most saturated, mid-light quarter.
     * Null for a grey cover (nothing worth tinting with).
     */
    fun vibrant(pixels: IntArray): Int? {
        val scored = pixels.filter { (it ushr 24) > 128 }.map { px ->
            val (s, l) = saturationAndLightness(px)
            px to s * (1 - abs(l - 0.5) * 1.6)
        }.sortedByDescending { it.second }
        if (scored.isEmpty() || scored.first().second < 0.15) return null
        val top = scored.take(max(1, scored.size / 4))
        var r = 0L
        var g = 0L
        var b = 0L
        top.forEach { (px, _) ->
            r += px shr 16 and 0xff
            g += px shr 8 and 0xff
            b += px and 0xff
        }
        val n = top.size
        return (0xff shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** The card's top tint and the accent (play button, seek bar) for a cover colour. */
    data class Scheme(val cardTop: Int, val accent: Int)

    /**
     * Null (the design tokens) when the tint would make [text] or [textDim] on the card harder to
     * read than 4.5:1 (or than the plain card already is). The accent is moved toward [text] until
     * [onAccent] reads on it at 4.5:1 and it shows on the card at 3:1; otherwise it is [text].
     */
    fun scheme(vibrant: Int?, surface: Int, text: Int, textDim: Int, onAccent: Int, dark: Boolean): Scheme? {
        vibrant ?: return null
        val needed = { fg: Int -> min(4.5, contrast(fg, surface)) }
        // The strongest tint that still reads; none at all when even a faint one would not.
        val top = (if (dark) listOf(0.30, 0.22, 0.15, 0.08) else listOf(0.22, 0.16, 0.10, 0.06))
            .map { blend(surface, vibrant, it) }
            .firstOrNull { contrast(text, it) >= needed(text) && contrast(textDim, it) >= needed(textDim) }
            ?: return null
        val accent = (0..10).asSequence()
            .map { blend(vibrant, text, it / 10.0) }
            .firstOrNull { contrast(onAccent, it) >= 4.5 && contrast(it, top) >= 3.0 }
            ?: text
        return Scheme(top, accent)
    }
}
