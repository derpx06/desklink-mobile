/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

/**
 * The reply handles of notifications that can be replied to, by reply id, with at most one per notification key: an update of a
 * chat replaces its handle (it used to add a new one every time and keep them all until the plugin died), a removed notification
 * drops it, and the total is bounded whatever the apps do (oldest first).
 */
class PendingReplies<T>(private val max: Int = 200) {
    private val byId = LinkedHashMap<String, T>()
    private val idByKey = HashMap<String, String>()

    @Synchronized
    fun put(key: String, replyId: String, value: T) {
        idByKey.put(key, replyId)?.let { old -> if (old != replyId) byId.remove(old) }
        byId[replyId] = value
        while (byId.size > max) {
            val oldest = byId.keys.first()
            byId.remove(oldest)
            idByKey.values.remove(oldest)
        }
    }

    @Synchronized
    fun get(replyId: String): T? = byId[replyId]

    /** Used (a reply was sent): no second reply through the same handle. */
    @Synchronized
    fun take(replyId: String): T? = byId.remove(replyId)?.also { idByKey.values.remove(replyId) }

    /** The notification [key] is gone. */
    @Synchronized
    fun removeKey(key: String) {
        idByKey.remove(key)?.let(byId::remove)
    }

    @Synchronized
    fun clear() {
        byId.clear()
        idByKey.clear()
    }

    @get:Synchronized
    val size: Int get() = byId.size
}
