/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mousereceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.desklink.mobile.DeskLinkApplication

/**
 * The Allow button of "<desk> wants to type on this phone". Not exported: only DeskLink's own
 * notification can reach it, so no other app can turn typing on.
 */
class TypeOnPhoneAllowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return
        if (org.desklink.mobile.helpers.LockedActions.refuseWhileLocked(context)) return
        if (DeskLinkApplication.getInstance().getDevice(id)?.isPaired != true) return
        FocusedFieldTyping.setAllowed(context, id, true)
        FocusedFieldTyping.dismissAsk(context)
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
    }
}
