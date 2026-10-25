/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.findmyphone

import android.content.Context
import android.media.AudioManager
import androidx.core.content.edit

/**
 * The alarm volume to put back after "find my phone" ring: remembered on disk while it rings, so a process that dies mid-ring
 * (killed, crashed) does not leave the phone's alarm at full volume for good. Restored at the next app start.
 */
object RingVolume {
    private const val PREFS = "findmyphone_ring"
    private const val KEY = "previous_alarm_volume"

    /** A ring lasts at most this long unless the person stops it first. */
    const val RING_LIMIT_MS = 60_000L

    @JvmStatic
    fun remember(context: Context, volume: Int) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY, volume) }

    @JvmStatic
    fun forget(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY) }

    /** The volume a dead ring left to restore, or null. */
    fun pending(context: Context): Int? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).takeIf { it.contains(KEY) }?.getInt(KEY, -1)?.takeIf { it >= 0 }

    /** At app start: puts a left-over alarm volume back. */
    fun restoreIfLeft(context: Context, audio: AudioManager? = context.getSystemService(AudioManager::class.java)) {
        val volume = pending(context) ?: return
        runCatching { audio?.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
        forget(context)
    }
}
