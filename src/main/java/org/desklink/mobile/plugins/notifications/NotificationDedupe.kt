/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

/**
 * What was last sent for each notification key, so the desk is not told the same thing twice: an app re-posting identical
 * content (a timer, a refreshed `when`), or the same words under two keys a moment apart. A resync is always sent (the desk may
 * have lost what it held) and is remembered. Locked: the sends run on the main looper, but a failed send forgets on the outbound worker.
 */
class NotificationDedupe(private val clock: () -> Long = System::currentTimeMillis) {
    private val sent = HashMap<String, String>()
    private val twins = HashMap<String, Pair<String, Long>>()

    /** [signature]: everything the desk would show for [key]; [words]: what a person reads, with its app. */
    @Synchronized
    fun shouldSend(key: String, app: String, signature: String, preexisting: Boolean, words: String = "$app|$signature"): Boolean {
        val now = clock()
        if (!preexisting) {
            if (sent[key] == signature) return false
            val twin = twins[words]
            if (twin != null && twin.first != key && now - twin.second < TWIN_WINDOW_MS) return false
        }
        sent[key] = signature
        twins[words] = key to now
        if (twins.size > MAX_TWINS) twins.entries.removeAll { now - it.value.second >= TWIN_WINDOW_MS }
        return true
    }

    /** The notification went away, or its send failed: it counts as new next time. */
    @Synchronized
    fun forget(key: String) {
        sent.remove(key)
    }

    @Synchronized
    fun clear() {
        sent.clear()
        twins.clear()
    }

    companion object {
        const val TWIN_WINDOW_MS = 3_000L
        private const val MAX_TWINS = 200
    }
}
