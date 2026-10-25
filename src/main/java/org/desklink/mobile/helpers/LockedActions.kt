/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.app.KeyguardManager
import android.content.Context
import android.widget.Toast
import org.desklink.mobile.R

/**
 * A notification button that grants something (accept a pairing, type, place a call, answer a terminal prompt) must not work from
 * the lock screen: anyone holding a locked phone could press it. A broadcast action runs without unlocking, so the receiver asks.
 */
object LockedActions {
    /** Pure: only actions that grant something are held back while the phone is locked. */
    fun refuse(locked: Boolean, grants: Boolean): Boolean = locked && grants

    fun isLocked(context: Context): Boolean =
        runCatching { context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true }.getOrDefault(false)

    /** True (and says so) when a granting action arrived while the phone is locked: the receiver does nothing. */
    fun refuseWhileLocked(context: Context): Boolean {
        if (!refuse(isLocked(context), grants = true)) return false
        Toast.makeText(context, R.string.unlock_first, Toast.LENGTH_LONG).show()
        return true
    }
}
