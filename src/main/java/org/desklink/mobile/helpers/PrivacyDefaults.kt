/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.content.Context
import androidx.core.content.edit
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.clipboard.ClipboardPlugin
import org.desklink.mobile.plugins.notifications.NotificationsPlugin

/**
 * Sharing the clipboard and mirroring notifications are off for a new pairing until the person says yes (a first-run question on
 * the Home tab). Computers paired before that keep what they had: one versioned step writes their current choice down, so
 * the change of default alters nothing for them.
 */
object PrivacyDefaults {
    internal const val MARKER_PREFS = "desklink_privacy_defaults"
    internal const val MARKER_KEY = "grandfathered_1_3"

    fun clipboardKey(): String = Plugin.getPluginKey(ClipboardPlugin::class.java)
    fun notificationsKey(): String = Plugin.getPluginKey(NotificationsPlugin::class.java)

    /** For every paired computer without an explicit choice: the old default (on), recorded, and the question marked as asked. */
    fun grandfather(context: Context, devices: List<String> = TrustedDevices.getAllTrustedDevices(context)) {
        val app = context.applicationContext
        val marker = app.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE)
        if (marker.getBoolean(MARKER_KEY, false)) return
        val ok = devices.map { id ->
            runCatching {
                val settings = TrustedDevices.getDeviceSettings(app, id)
                settings.edit {
                    if (!settings.contains(clipboardKey())) putBoolean(clipboardKey(), true)
                    if (!settings.contains(notificationsKey())) putBoolean(notificationsKey(), true)
                }
                FirstShare.markDone(app, id)
            }
        }.all { it.isSuccess }
        if (ok) marker.edit { putBoolean(MARKER_KEY, true) }
    }
}

/** The one-time "What should DeskLink share with <desk>?" question after pairing. */
object FirstShare {
    private fun prefs(context: Context) = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)

    /** Per computer, so unpairing it clears it and a new pairing is asked again. */
    fun key(deviceId: String) = "first_share_done_$deviceId"

    fun needed(context: Context, deviceId: String): Boolean = !prefs(context).getBoolean(key(deviceId), false)

    fun markDone(context: Context, deviceId: String) = prefs(context).edit { putBoolean(key(deviceId), true) }
}
