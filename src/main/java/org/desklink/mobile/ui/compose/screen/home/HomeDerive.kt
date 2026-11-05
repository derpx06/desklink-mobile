/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.screen.home

import org.desklink.mobile.plugins.share.TransferRecord
import org.desklink.mobile.ui.compose.model.device.DeviceConnection

/** Why the computer's actions cannot be used right now; null from [HomeDerive.whyNot] when they can. */
enum class NotReady { UPDATE, CONNECTING, CANT_REACH, OFFLINE }

/** What Home shows for a given state: pure, so a test can say what appears without drawing anything. */
object HomeDerive {
    const val RECENT_FILES = 3
    const val RECENT_NOTES = 2
    const val PREVIEW_CHARS = 48

    /** The newest [RECENT_FILES] files sent or received, newest first (the store already lists them that way). */
    fun recentFiles(history: List<TransferRecord>): List<TransferRecord> = history.take(RECENT_FILES)

    /** One line of the copied text, or null when there is nothing worth showing. */
    fun clipboardPreview(raw: String?, max: Int = PREVIEW_CHARS): String? {
        val line = raw?.let { Regex("\\s+").replace(it, " ").trim() }?.takeIf { it.isNotEmpty() } ?: return null
        return if (line.length <= max) line else line.take(max - 1).trimEnd() + "…"
    }

    /** The computer's actions can be used: connected and not waiting for an update. Everything that sends something asks this, never `ready`. */
    fun usable(s: HomeState): Boolean = whyNot(s) == null

    fun whyNot(s: HomeState): NotReady? = when {
        s.updateNeeded -> NotReady.UPDATE
        s.ready -> null
        s.connection == DeviceConnection.CONNECTING -> NotReady.CONNECTING
        s.connection == DeviceConnection.UNAVAILABLE -> NotReady.CANT_REACH
        else -> NotReady.OFFLINE
    }

    /** The "Happening now" area exists only while something is happening. */
    fun happeningNow(s: HomeState): Boolean = s.live.any || s.transfer != null || s.media != null

    /** Cancel is offered only for a transfer that is still going. */
    fun canCancel(t: HomeTransfer?): Boolean = t != null && t.active

    /** The Recent area: files, then notes (always, they are local), then the mirrored-notifications line. */
    fun showRecentFiles(s: HomeState): Boolean = s.recent.isNotEmpty()

    fun showMirrored(s: HomeState): Boolean = s.deskNotifications > 0

    /** Whether the "Show all" link to the Files tab is worth showing: there is more than the rows shown. */
    fun moreFiles(s: HomeState): Boolean = s.historyCount > s.recent.size
}
