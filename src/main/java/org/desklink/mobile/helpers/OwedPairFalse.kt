/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.desklink.mobile.helpers

import android.content.Context

/**
 * Computers this phone unpaired while it had no link to tell them. The `pair:false` is owed: it is sent as soon as a link to that
 * computer exists (see PairingHandler.sendOwedPairFalse), and forgotten once sent or when the computer is paired again. Without it the
 * computer keeps its record until it happens to send a packet the phone answers. A small set of device ids, kept across restarts.
 */
object OwedPairFalse {
    private const val PREFS = "desklink_owed_pair_false"
    private const val KEY = "ids"

    private fun prefs(context: Context) = (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): Set<String> = prefs(context).getStringSet(KEY, emptySet()).orEmpty()

    @Synchronized
    fun owe(context: Context, deviceId: String) {
        prefs(context).edit().putStringSet(KEY, read(context) + deviceId).commit()
    }

    fun owes(context: Context, deviceId: String) = deviceId in read(context)

    @Synchronized
    fun clear(context: Context, deviceId: String) {
        if (deviceId in read(context)) prefs(context).edit().putStringSet(KEY, read(context) - deviceId).commit()
    }
}
