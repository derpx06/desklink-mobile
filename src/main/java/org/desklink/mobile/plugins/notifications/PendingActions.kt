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

    companion object {
        /** The label the desk shows and sends back on a press: no control characters and trimmed, as the desk keeps it. */
        fun label(title: CharSequence?): String = title?.toString().orEmpty().filter { !it.isISOControl() }.trim()

        /**
         * The buttons worth mirroring, each with its label: a press is found by that label, so a blank one (Android 15's redaction
         * blanks every title; icon-only buttons) or a second one with the same label could never be pressed. [usable]: it has an
         * intent and is not a reply (that goes as `requestReplyId`). A [redacted] notification has none: they would act blind.
         */
        fun <T> sendable(actions: List<T>, redacted: Boolean, title: (T) -> CharSequence?, usable: (T) -> Boolean): List<Pair<String, T>> {
            if (redacted) return emptyList()
            return actions.filter(usable).map { label(title(it)) to it }.filter { it.first.isNotEmpty() }.distinctBy { it.first }
        }
    }
}
