/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import android.content.Context
import androidx.core.content.edit
import org.desklink.mobile.helpers.TrustedDevices

/**
 * A paired computer's saved record (its certificate, name and the rest of its settings), taken just
 * before a QR re-pair replaces it, so a failed replace can put it back instead of leaving nothing paired.
 */
class PairingBackup private constructor(val deviceId: String, private val settings: Map<String, Any?>) {
    fun restore(context: Context) {
        TrustedDevices.getDeviceSettings(context, deviceId).edit(commit = true) {
            clear()
            settings.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }
        TrustedDevices.addTrustedDevice(context, deviceId)
    }

    companion object {
        fun capture(context: Context, deviceId: String): PairingBackup =
            PairingBackup(deviceId, TrustedDevices.getDeviceSettings(context, deviceId).all.toMap())
    }
}
