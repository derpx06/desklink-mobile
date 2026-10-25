/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.content.Context
import androidx.core.content.edit

/**
 * The one list of preference files (other than the default one, which [PeerState] sweeps by key suffix) that keep something per
 * paired computer, with how each names its keys. The writers build their keys here, [forget] clears them all, and the test
 * walks this list, so a new per-computer key cannot be added without being forgotten on unpair.
 */
object PerPeerState {
    class Store(val prefsName: String, val owns: (key: String, deviceId: String) -> Boolean)

    const val LABELS = "desklink_terminal_labels"
    const val SURFACES = "desk_surfaces"
    const val SHARE_QUEUE = "share_queue"

    fun labelKey(deviceId: String, sessionId: String) = "$deviceId/$sessionId"
    fun seenKey(deviceId: String) = "seen_$deviceId"
    fun finishedKey(deviceId: String) = "finished_$deviceId"

    val stores: List<Store> = listOf(
        Store(LABELS) { key, id -> key.startsWith("$id/") },
        Store(SURFACES) { key, id -> key == seenKey(id) || key == finishedKey(id) },
        Store(SHARE_QUEUE) { key, id -> key == id || key == "attempts_$id" },
    )

    /** Preference files that hold nothing per computer (the pairing registry itself, app-wide choices, transfer-id keyed state). */
    val NOT_PER_PEER = setOf("trusted_devices", "stored_menu_selection", "transfer_resume_sources")

    fun forget(context: Context, deviceId: String) {
        if (deviceId.length < 8) return
        stores.forEach { store ->
            runCatching {
                val prefs = context.getSharedPreferences(store.prefsName, Context.MODE_PRIVATE)
                val keys = prefs.all.keys.filter { store.owns(it, deviceId) }
                if (keys.isNotEmpty()) prefs.edit { keys.forEach(::remove) }
            }
        }
    }
}
