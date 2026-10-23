/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import java.util.Locale

/** Sizes in binary units (KiB, MiB, GiB), as the computer shows them: "19 MiB", "1.2 GiB", never Android's decimal "20 MB". */
object Sizes {
    private val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")

    @JvmStatic
    fun iec(bytes: Long): String {
        if (bytes < 1024) return "${bytes.coerceAtLeast(0)} B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val text = if (value < 10) String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0") else Math.round(value).toString()
        return "$text ${units[unit]}"
    }
}
