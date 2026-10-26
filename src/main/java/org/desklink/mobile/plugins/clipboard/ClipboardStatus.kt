/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.clipboard

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/** When the phone's clipboard last reached a computer, and why it would not: what Settings -> Clipboard shows in one line. */
object ClipboardStatus {
    private fun key(deviceId: String) = "clipboard_last_sent_$deviceId"

    fun sent(context: Context, deviceId: String, now: Long = System.currentTimeMillis()) =
        PreferenceManager.getDefaultSharedPreferences(context).edit { putLong(key(deviceId), now) }

    fun lastSent(context: Context, deviceId: String): Long =
        PreferenceManager.getDefaultSharedPreferences(context).getLong(key(deviceId), 0L)

    enum class Why { OK, SHARING_OFF, NOT_CONNECTED }

    fun why(sharingOn: Boolean, connected: Boolean): Why = when {
        !sharingOn -> Why.SHARING_OFF
        !connected -> Why.NOT_CONNECTED
        else -> Why.OK
    }
}
