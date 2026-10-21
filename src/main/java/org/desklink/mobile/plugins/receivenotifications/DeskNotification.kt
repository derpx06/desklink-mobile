/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.receivenotifications

import org.desklink.mobile.NetworkPacket

/**
 * A desk notification shown on the phone (contract C1): `desklink.notification` desk -> phone with
 * `id` ("desk:…"), `appName`, `title`, `text`, `time`, `isClearable`, `silent`. The KDE form
 * (`ticker` instead of `title`/`text`) is still read.
 */
data class DeskNotification(
    val id: String,
    val appName: String,
    val title: String,
    val text: String,
    val time: Long,
    val clearable: Boolean,
    val silent: Boolean,
    val cancel: Boolean,
) {
    companion object {
        private const val MAX_TITLE = 256
        private const val MAX_TEXT = 4096

        /** Null when the packet names nothing to show (no id, or no words at all). */
        fun parse(np: NetworkPacket): DeskNotification? {
            val id = np.getString("id", "").take(MAX_TITLE)
            if (id.isEmpty()) return null
            val cancel = np.getBoolean("isCancel", false)
            val appName = np.getString("appName", "").take(MAX_TITLE)
            val text = np.getString("text", "").ifEmpty { np.getString("ticker", "") }.take(MAX_TEXT)
            val title = np.getString("title", "").ifEmpty { appName }.take(MAX_TITLE)
            if (!cancel && title.isEmpty() && text.isEmpty()) return null
            return DeskNotification(
                id = id,
                appName = appName,
                title = title,
                text = text,
                time = np.getLong("time", 0L),
                clearable = np.getBoolean("isClearable", true),
                silent = np.getBoolean("silent", false),
                cancel = cancel,
            )
        }

        /** One namespace per desk: two desks may both have a "desk:1". */
        fun tag(deviceId: String, id: String) = "$deviceId/$id"

        fun channelId(deviceId: String) = "desk_notifications_$deviceId"

        /** Posted tags of [deviceId] whose id is not in [keep]: the desk no longer has them. */
        fun stale(posted: Collection<String>, deviceId: String, keep: Set<String>): List<String> {
            val prefix = "$deviceId/"
            return posted.filter { it.startsWith(prefix) && it.removePrefix(prefix) !in keep }
        }
    }
}
