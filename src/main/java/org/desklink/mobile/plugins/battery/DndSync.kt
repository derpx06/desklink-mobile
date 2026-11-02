/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.battery

import android.app.NotificationManager
import android.content.Context

/**
 * Do Not Disturb sync (W14). The phone reports its interruption filter in the battery packet (`dnd`, `dndCanSet`) only while the
 * computer's switch is on, and sets the filter from the computer's `dnd` only when it holds notification-policy access.
 */
object DndSync {
    /** Do Not Disturb is anything but "all": priority only, alarms only, total silence. */
    fun isDnd(filter: Int): Boolean = filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN

    /** The keys the battery packet carries: none unless the last `dndSync` the phone was told is true. */
    fun keys(syncOn: Boolean, dnd: Boolean, canSet: Boolean): Map<String, Boolean> =
        if (syncOn) mapOf("dnd" to dnd, "dndCanSet" to canSet) else emptyMap()

    fun phoneDnd(context: Context): Boolean = runCatching {
        isDnd(context.getSystemService(NotificationManager::class.java).currentInterruptionFilter)
    }.getOrDefault(false)

    fun canSet(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    }.getOrDefault(false)

    /** Turns the phone's Do Not Disturb on (priority only) or off. False when Android refuses. */
    fun apply(context: Context, dnd: Boolean): Boolean = runCatching {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.isNotificationPolicyAccessGranted) return false
        manager.setInterruptionFilter(if (dnd) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        true
    }.getOrDefault(false)

    /**
     * The two loop guards. Each side remembers the last value it received from the peer, never reports a change that equals it, and
     * ignores a local change that equals what it just applied.
     */
    class Loop(private val now: () -> Long = System::currentTimeMillis) {
        private var received: Boolean? = null
        private var applied: Boolean? = null
        private var appliedAt = 0L

        /** The computer's value: what to set on the phone, or null when the phone already has it (or the value is absent). */
        @Synchronized
        fun fromDesk(deskDnd: Boolean?, phoneDnd: Boolean, canSet: Boolean): Boolean? {
            if (deskDnd == null) return null
            val changed = deskDnd != received
            received = deskDnd
            if (!changed || !canSet || deskDnd == phoneDnd) return null
            applied = deskDnd
            appliedAt = now()
            return deskDnd
        }

        /** A change of the phone's own filter: whether the computer should be told. */
        @Synchronized
        fun localChange(phoneDnd: Boolean): Boolean {
            if (applied == phoneDnd && now() - appliedAt < APPLIED_ECHO_MS) return false // our own write coming back
            if (received == phoneDnd) return false // the computer already has it
            return true
        }

        /** The link or the switch went away: nothing received counts any more. */
        @Synchronized
        fun reset() { received = null; applied = null }
    }

    const val APPLIED_ECHO_MS = 3_000L
}
