/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

/**
 * The buttons of the mirrored notifications, by notification key. Written on the main thread (posts, updates, removals) and read
 * on the packet thread when the desk presses one, so every access is locked and a read gets a copy, never a live list
 * (iterating a list another thread is changing threw, and the press was lost).
 */
class PendingActions<T> {
    private val byKey = HashMap<String, List<T>>()

    /** An update replaces the buttons of the key; none left means none kept. */
    @Synchronized
    fun replace(key: String, actions: List<T>) {
        if (actions.isEmpty()) byKey.remove(key) else byKey[key] = actions.toList()
    }

    @Synchronized
    fun find(key: String, matches: (T) -> Boolean): T? = byKey[key]?.firstOrNull(matches)

    @Synchronized
    fun remove(key: String) {
        byKey.remove(key)
    }

    @Synchronized
    fun clear() = byKey.clear()
}
