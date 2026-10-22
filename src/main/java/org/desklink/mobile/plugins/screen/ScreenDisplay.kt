/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.screen

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.util.concurrent.ConcurrentHashMap

/**
 * F2 (D1): show the desk's screen as a Mirror of one of its monitors, or Extend it with a virtual
 * monitor the size of this phone's view. Remembered per desk; tablets (smallest width ≥ 600 dp)
 * start on Extend. A desk that cannot extend makes this session mirror.
 */
object ScreenDisplay {
    const val MIRROR = "mirror"
    const val EXTEND = "extend"
    const val REASON_UNSUPPORTED = "extend-unsupported"

    /** The phone view's size in physical pixels, as the desk screen last laid it out (observable). */
    var viewArea by mutableStateOf<Pair<Int, Int>?>(null)

    /** Desks that refused Extend in this process: they mirror until the person asks again. */
    private val refused = ConcurrentHashMap.newKeySet<String>()

    private fun key(deviceId: String) = "screen_display_$deviceId"

    fun defaultFor(smallestWidthDp: Int) = if (smallestWidthDp >= 600) EXTEND else MIRROR

    fun chosen(context: Context, deviceId: String): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(key(deviceId), null) ?: defaultFor(context.resources.configuration.smallestScreenWidthDp)

    fun choose(context: Context, deviceId: String, mode: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit { putString(key(deviceId), mode) }
        refused.remove(deviceId)
    }

    /** What the next `request-view` asks for. */
    fun requested(context: Context, deviceId: String): String =
        if (deviceId in refused) MIRROR else chosen(context, deviceId)

    fun refusedBy(deviceId: String) { refused += deviceId }

    /** D1 fields of a desk-to-phone `request-view`: display, and for Extend the view's pixels and scale. */
    data class Ask(val display: String, val width: Int?, val height: Int?, val scale: Double?)

    fun ask(display: String, viewArea: Pair<Int, Int>?, density: Float): Ask {
        val area = viewArea?.takeIf { it.first in 1..8192 && it.second in 1..8192 }
        return if (display == EXTEND && area != null) {
            Ask(EXTEND, area.first, area.second, (density.toDouble() * 100).toInt() / 100.0)
        } else if (display == EXTEND) {
            Ask(EXTEND, null, null, null)
        } else {
            Ask(MIRROR, null, null, null)
        }
    }
}
