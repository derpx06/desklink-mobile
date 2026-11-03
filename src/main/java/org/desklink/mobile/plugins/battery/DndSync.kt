/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.battery

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.edit

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

    /**
     * Turns the phone's Do Not Disturb on (priority only) or off. The filter the phone had before the first change is remembered, and
     * "off" puts it back (see [Restore]). False when Android refuses.
     */
    fun apply(context: Context, deviceId: String, dnd: Boolean): Boolean = runCatching {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.isNotificationPolicyAccessGranted) return false
        if (!dnd) {
            // Off: what was saved is put back (only if the person did not change it since); with nothing saved there is nothing to
            // put back, and nothing is remembered (a note made now would later push the phone into a Do Not Disturb of the person's own).
            if (Restore.has(context, deviceId)) return Restore.restoreNow(context, deviceId)
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            return true
        }
        val target = NotificationManager.INTERRUPTION_FILTER_PRIORITY
        Restore.remember(context, deviceId, manager.currentInterruptionFilter, target)
        manager.setInterruptionFilter(target)
        true
    }.getOrDefault(false)

    /**
     * What the phone's filter was before DeskLink changed it, so it can be put back when the switch turns off, the computer is
     * unpaired, or the link stays gone for [GONE_MS]. Only when the filter still is what DeskLink set: a change the person made
     * since is theirs. Kept on disk too, so a process that ended in between does not lose it.
     */
    object Restore {
        const val GONE_MS = 60_000L

        private class Saved(val before: Int, val setTo: Int)

        /** Guarded by this object's lock, like every read-modify-write below. */
        private val saved = HashMap<String, Saved>()
        private val pending = HashMap<String, Runnable>()
        private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

        private fun stored(context: Context, deviceId: String): Saved? =
            decode(store(context).getString(deviceId, null))?.let { Saved(it.first, it.second) }

        /** Something is to be put back: in memory, or on disk from a run that ended (before [resumeAfterRestart] read it). */
        @Synchronized
        fun has(context: Context, deviceId: String) = saved.containsKey(deviceId) || stored(context, deviceId) != null

        /**
         * DeskLink is about to set [setTo] while the filter is [current]. The first filter is kept across repeated changes, unless the
         * person changed it meanwhile ([current] is not what DeskLink set): then what they chose is what comes back.
         */
        @Synchronized
        fun remember(context: Context, deviceId: String, current: Int, setTo: Int) {
            val old = saved[deviceId] ?: stored(context, deviceId)
            val kept = Saved(if (old != null && current == old.setTo) old.before else current, setTo)
            saved[deviceId] = kept
            // Kept on disk too: if Android ends this process while the computer holds the phone in Do Not Disturb, the next start puts it back.
            store(context).edit { putString(deviceId, encode(kept.before, kept.setTo)) }
        }

        private fun store(context: Context) = context.applicationContext.getSharedPreferences(org.desklink.mobile.helpers.PerPeerState.DND_RESTORE, Context.MODE_PRIVATE)

        fun encode(before: Int, setTo: Int) = "$before,$setTo"

        /** (before, setTo), or null for anything that is not what [encode] writes. */
        fun decode(text: String?): Pair<Int, Int>? {
            val parts = text?.split(',') ?: return null
            if (parts.size != 2) return null
            return (parts[0].toIntOrNull() ?: return null) to (parts[1].toIntOrNull() ?: return null)
        }

        /** After a start: what a previous run changed and never put back is put back in [GONE_MS] unless the computer returns first. */
        @Synchronized
        fun resumeAfterRestart(context: Context) {
            store(context).all.forEach { (id, value) ->
                val (before, setTo) = decode(value as? String) ?: run { store(context).edit { remove(id) }; return@forEach }
                if (!saved.containsKey(id)) {
                    saved[id] = Saved(before, setTo)
                    restoreLater(context, id)
                }
            }
        }

        /** Pure: the person's own change since is left alone. */
        fun shouldRestore(current: Int, setTo: Int) = current == setTo

        @Synchronized
        fun restoreNow(context: Context, deviceId: String): Boolean {
            cancel(deviceId)
            // From memory, else from disk (an unpair or a switch-off before [resumeAfterRestart] ran).
            val s = saved.remove(deviceId) ?: stored(context, deviceId)
            store(context).edit { remove(deviceId) }
            if (s == null) return true
            return runCatching {
                val manager = context.getSystemService(NotificationManager::class.java)
                if (manager.isNotificationPolicyAccessGranted && shouldRestore(manager.currentInterruptionFilter, s.setTo)) manager.setInterruptionFilter(s.before)
                true
            }.getOrDefault(false)
        }

        /** The link is gone: after [GONE_MS] without it coming back (see [cancel]) the filter is put back. */
        fun restoreLater(context: Context, deviceId: String) {
            if (!has(context, deviceId)) return
            val app = context.applicationContext
            val task = Runnable { restoreNow(app, deviceId) }
            synchronized(pending) { pending.remove(deviceId)?.let(handler::removeCallbacks); pending[deviceId] = task }
            handler.postDelayed(task, GONE_MS)
        }

        fun cancel(deviceId: String) = synchronized(pending) { pending.remove(deviceId)?.let(handler::removeCallbacks) }

        /** A process that ended: memory is gone, the disk note stays. */
        @androidx.annotation.VisibleForTesting
        @Synchronized
        internal fun dropMemoryForTest() = saved.clear()
    }

    /**
     * The two loop guards. Each side remembers the last value it knows the peer holds (received from it, or reported to it), never
     * reports a change that equals it, and ignores a local change that equals what it just applied.
     */
    class Loop(private val now: () -> Long = System::currentTimeMillis) {
        private var received: Boolean? = null
        private var latest: Boolean? = null
        private var applied: Boolean? = null
        private var appliedAt = 0L

        /**
         * The computer's value: what to set on the phone, or null when nothing is to be done (no change, the phone already has it,
         * no access). The first value after a connection is never an "off": a Do Not Disturb the person set here meanwhile stays.
         */
        @Synchronized
        fun fromDesk(deskDnd: Boolean?, phoneDnd: Boolean, canSet: Boolean): Boolean? {
            if (deskDnd == null) return null
            val first = received == null
            val changed = deskDnd != received
            received = deskDnd
            latest = deskDnd
            if (!changed || !canSet || deskDnd == phoneDnd || (first && !deskDnd)) return null
            return mark(deskDnd)
        }

        /** Access was granted after the computer's value arrived: its latest value is applied now. */
        @Synchronized
        fun reapply(phoneDnd: Boolean, canSet: Boolean): Boolean? {
            val value = latest ?: return null
            return if (canSet && value != phoneDnd) mark(value) else null
        }

        private fun mark(value: Boolean): Boolean { applied = value; appliedAt = now(); return value }

        /** A change of the phone's own filter: whether the computer should be told (and from then on it is known to hold it). */
        @Synchronized
        fun localChange(phoneDnd: Boolean): Boolean {
            if (applied == phoneDnd && now() - appliedAt < APPLIED_ECHO_MS) return false // our own write coming back
            if (received == phoneDnd) return false // the computer already has it
            received = phoneDnd
            return true
        }

        /** The link or the switch went away: nothing received counts any more. */
        @Synchronized
        fun reset() { received = null; latest = null; applied = null }
    }

    const val APPLIED_ECHO_MS = 3_000L
}
