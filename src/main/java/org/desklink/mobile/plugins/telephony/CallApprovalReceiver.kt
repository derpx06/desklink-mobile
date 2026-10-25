/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import org.desklink.mobile.DeskLinkApplication

/** Allow or Deny on the "<desk> wants to call <number>" heads-up. Not exported: only this app's own notification reaches it. */
class CallApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_ALLOW && org.desklink.mobile.helpers.LockedActions.refuseWhileLocked(context)) return
        val nonce = intent.getStringExtra(EXTRA_NONCE)
        val deviceId = intent.getStringExtra(EXTRA_DEVICE)
        runCatching { NotificationManagerCompat.from(context).cancel("call-approval:$deviceId", NOTIFICATION_ID) }
        val plugin = DeskLinkApplication.getInstance().getDevice(deviceId)?.getPlugin(TelephonyPlugin::class.java)
        when (intent.action) {
            ACTION_ALLOW -> plugin?.placeApprovedCall(nonce)
            else -> plugin?.denyCall(nonce)
        }
    }

    companion object {
        const val ACTION_ALLOW = "org.desklink.mobile.plugins.telephony.ALLOW_CALL"
        const val ACTION_DENY = "org.desklink.mobile.plugins.telephony.DENY_CALL"
        const val EXTRA_NONCE = "nonce"
        const val EXTRA_DEVICE = "deviceId"
        const val NOTIFICATION_ID = 7332
    }
}
