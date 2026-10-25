/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the pairing notification's Accept/Reject. Not exported, so only this
 * app's own PendingIntents can reach it — the old path went through the
 * exported MainActivity, where any app could accept or unpair a device.
 */
class PairingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return
        val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, -1L)
        val device = DeskLinkApplication.getInstance().getDevice(deviceId) ?: return
        when (intent.action) {
            ACTION_ACCEPT -> {
                if (org.desklink.mobile.helpers.LockedActions.refuseWhileLocked(context)) return
                device.acceptPairing(timestamp)
            }
            ACTION_REJECT -> device.rejectPairingRequest(timestamp)
            else -> return
        }
        device.hidePairingNotification()
    }

    companion object {
        const val ACTION_ACCEPT = "org.desklink.mobile.pairing.ACCEPT"
        const val ACTION_REJECT = "org.desklink.mobile.pairing.REJECT"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_TIMESTAMP = "timestamp"
    }
}
