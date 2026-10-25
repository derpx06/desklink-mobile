/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.content.Context
import org.desklink.mobile.Device
import org.desklink.mobile.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Files whose send was interrupted and cannot go on: after the app restarted, Android no longer lets DeskLink read the
 * file (the sharing app's permission ended) or it changed. Kept a week (at most 20) so the Files tab can say so and offer
 * "Send again", instead of the send ending in silence.
 */
object SendAgain {
    private const val PREFS = "send_again"
    private const val KEY = "items"
    private const val MAX_ITEMS = 20
    private const val MAX_AGE_MS = 7 * 24 * 3600 * 1000L

    data class Item(val id: String, val deviceId: String, val name: String, val unreadable: Boolean, val at: Long)

    @JvmStatic
    @Synchronized
    fun list(context: Context, now: Long = System.currentTimeMillis()): List<Item> = runCatching {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { i ->
            runCatching {
                array.getJSONObject(i).let { Item(it.getString("id"), it.getString("device"), it.getString("name"), it.getBoolean("unreadable"), it.getLong("at")) }
            }.getOrNull()?.takeIf { now - it.at < MAX_AGE_MS }
        }
    }.getOrDefault(emptyList())

    /** Remembers it, shows the failed notification, and lets the Files tab know. [device] may be null (no notification). */
    @JvmStatic
    @Synchronized
    fun add(context: Context, device: Device?, id: String, deviceId: String, name: String, unreadable: Boolean) {
        val items = (list(context).filterNot { it.id == id } + Item(id, deviceId, name, unreadable, System.currentTimeMillis())).takeLast(MAX_ITEMS)
        save(context, items)
        if (device != null) {
            runCatching {
                val note = UploadNotification(device, id.hashCode().toLong() and 0xffffffffL)
                val text = context.getString(if (unreadable) R.string.send_again_notification_unreadable else R.string.send_again_notification_changed, name)
                note.setFailed(text)
                note.show()
            }
        }
        TransferHistory.changed()
    }

    /** The computer was unpaired: what was waiting to be sent to it is gone. */
    @JvmStatic
    @Synchronized
    fun forgetDevice(context: Context, deviceId: String) {
        save(context, list(context).filterNot { it.deviceId == deviceId })
        TransferHistory.changed()
    }

    @JvmStatic
    @Synchronized
    fun dismiss(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
        TransferHistory.changed()
    }

    private fun save(context: Context, items: List<Item>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("device", it.deviceId).put("name", it.name).put("unreadable", it.unreadable).put("at", it.at)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
