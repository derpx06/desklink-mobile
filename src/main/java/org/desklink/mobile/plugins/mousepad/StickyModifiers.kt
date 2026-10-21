/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.mousepad

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.desklink.mobile.NetworkPacket

/**
 * Ctrl / Alt / Super toggles for keys a phone keyboard lacks. One-shot, like a
 * phone's Shift: the next key carries them, then they release — so Ctrl, then
 * "c" sends Ctrl+C and nothing stays held.
 */
class StickyModifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var meta by mutableStateOf(false)

    val any: Boolean get() = ctrl || alt || meta

    /** Adds the active modifiers to `np` and releases them. */
    fun applyTo(np: NetworkPacket) {
        if (ctrl) np["ctrl"] = true
        if (alt) np["alt"] = true
        if (meta) np["super"] = true
        ctrl = false
        alt = false
        meta = false
    }
}
