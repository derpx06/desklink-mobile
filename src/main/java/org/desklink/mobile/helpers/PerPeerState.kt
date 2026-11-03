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
    const val DND_RESTORE = "dnd_restore"

    fun labelKey(deviceId: String, sessionId: String) = "$deviceId/$sessionId"
    fun seenKey(deviceId: String) = "seen_$deviceId"
    fun finishedKey(deviceId: String) = "finished_$deviceId"

    val stores: List<Store> = listOf(
        Store(LABELS) { key, id -> key.startsWith("$id/") },
        Store(SURFACES) { key, id -> key == seenKey(id) || key == finishedKey(id) },
        Store(SHARE_QUEUE) { key, id -> key == id || key == "attempts_$id" },
        Store(DND_RESTORE) { key, id -> key == id },
    )

    /** Preference files that hold nothing per computer, each with why (the pairing registry itself, app-wide choices, transfer-id keyed state). */
    val NOT_PER_PEER: Map<String, String> = mapOf(
        "trusted_devices" to "the pairing registry itself: removed by TrustedDevices on unpair",
        "stored_menu_selection" to "the person's own navigation choices",
        "transfer_resume_sources" to "keyed by transfer id; swept with the transfer",
        "send_again" to "one JSON list of items naming their device: SendAgain.forgetDevice, pinned in PeerStateTest",
        "desklink.transfer.checkpoints" to "keyed by transfer id, with the device inside: FileTransferStore.forgetDevice, pinned in PeerStateTest",
        "crash_loop" to "the app's own start-up crash counter",
        "permissions_asked" to "which Android permissions were asked once; the phone's, not a computer's",
        "desklink_privacy_defaults" to "one-time marker of the privacy defaults run",
        "desklink_removed_features" to "one-time marker of the removed-feature cleanup",
        "findmyphone_ring" to "the ring volume to put back: the phone's",
        "accessibility_disclosure" to "when the person accepted the accessibility disclosure: the phone's",
        "app_database" to "the notification app filter: the phone's choice of apps, not per computer",
        "desklink_terminal_view" to "the terminal font size: a display choice",
        "session_gate" to "the app lock: the phone's",
        // Deliberately kept across an unpair: it IS the record of the unpair, and is paid and cleared by the next link or a re-pair.
        OwedPairFalse.PREFS to "owed pair:false notes: kept across an unpair on purpose, cleared once told or on a re-pair",
    )

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
