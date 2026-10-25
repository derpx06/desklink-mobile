/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * "Forget this device" means all of it. Everything this phone holds for one paired computer is dropped here, from the one place
 * every way of ending a pairing passes through (the user's Unpair, the computer unpairing, a replaced record, a new identity).
 * What stays is what belongs to the person, not to the pairing: received files, the transfer history, files kept for a retry.
 */
object PeerState {
    /**
     * The default-preferences keys that belong to [deviceId]: every consent and choice stored as `<what>_<deviceId>` (glide,
     * typing, microphone, sound, extended screen, private notifications...) or `<what><deviceId>`. Pure, for tests.
     */
    fun preferenceKeys(allKeys: Collection<String>, deviceId: String): List<String> =
        if (deviceId.length < MIN_ID_LENGTH) emptyList() else allKeys.filter { it.endsWith(deviceId) || it.startsWith("${deviceId}_") }

    fun forget(context: Context, deviceId: String) {
        // Best effort, step by step: tidying up must never make an unpair look as if it failed.
        val app = runCatching { context.applicationContext }.getOrNull() ?: return
        runCatching {
            val prefs = PreferenceManager.getDefaultSharedPreferences(app)
            val keys = preferenceKeys(prefs.all.keys, deviceId)
            if (keys.isNotEmpty()) prefs.edit { keys.forEach(::remove) }
        }
        // The desk's notifications mirrored here (their texts are on the lock screen and in the shade).
        runCatching {
            val manager = ContextCompat.getSystemService(app, NotificationManager::class.java)
            val posted = manager?.activeNotifications.orEmpty().mapNotNull { it.tag }
            org.desklink.mobile.plugins.receivenotifications.DeskNotification.stale(posted, deviceId, emptySet()).forEach { manager?.cancel(it, 7330) }
        }
        runCatching { org.desklink.mobile.plugins.share.SendAgain.forgetDevice(app, deviceId) }
        runCatching { org.desklink.mobile.plugins.share.ShareQueue.forgetDevice(app, deviceId) }
        runCatching { org.desklink.mobile.plugins.folders.BucketEngine.forgetPeer(app, deviceId) }
        runCatching { org.desklink.mobile.webrtc.DeliveredTransfers.forgetDevice(app, deviceId) }
        runCatching { org.desklink.mobile.webrtc.FileTransferStore.forgetDevice(app, deviceId) }
    }

    private const val MIN_ID_LENGTH = 8
}
