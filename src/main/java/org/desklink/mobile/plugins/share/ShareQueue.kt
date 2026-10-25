/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.content.Context
import org.json.JSONArray

/**
 * The files of one share that are still to be sent to a computer, by source URI, so a dropped link or a restarted app does not
 * lose all but the file in flight (that one is kept by the transfer manager and resumes by itself). Bounded; an entry goes when
 * its file is sent, handed to the transfer manager, or cannot be read any more.
 */
object ShareQueue {
    private const val PREFS = "share_queue"
    const val MAX_PER_DEVICE = 100

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    @Synchronized
    fun pending(context: Context, deviceId: String): List<String> = runCatching {
        val array = JSONArray(prefs(context).getString(deviceId, "[]"))
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    /** Appends [uris] (no duplicates), keeping at most [MAX_PER_DEVICE]; returns how many did not fit. */
    @JvmStatic
    @Synchronized
    fun add(context: Context, deviceId: String, uris: List<String>): Int {
        val all = (pending(context, deviceId) + uris).distinct()
        save(context, deviceId, all.take(MAX_PER_DEVICE))
        return (all.size - MAX_PER_DEVICE).coerceAtLeast(0)
    }

    @JvmStatic
    @Synchronized
    fun remove(context: Context, deviceId: String, uri: String) = save(context, deviceId, pending(context, deviceId) - uri)

    @JvmStatic
    @Synchronized
    fun forgetDevice(context: Context, deviceId: String) = prefs(context).edit().remove(deviceId).apply()

    private fun save(context: Context, deviceId: String, uris: List<String>) {
        val edit = prefs(context).edit()
        if (uris.isEmpty()) edit.remove(deviceId) else edit.putString(deviceId, JSONArray(uris).toString())
        edit.apply()
    }
}
