/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.screen.home

import org.desklink.mobile.plugins.share.TransferRecord
import org.desklink.mobile.ui.compose.model.device.DeviceConnection

/** Why the computer's actions cannot be used right now; null from [HomeDerive.whyNot] when they can. */
enum class NotReady { UPDATE, CONNECTING, CANT_REACH, OFFLINE }

/**
 * Why one action cannot be used, checked in this order: the link ([NotReady]), then the action itself. An action is never hidden for
 * it: it shows dimmed, says this in one line, and offers the [Fix].
 */
enum class Blocker { UPDATE, CONNECTING, CANT_REACH, OFFLINE, PHONE_OFF, PERMISSION, DESK_OFF, NOT_SAME_WIFI, UNLOCK_OFF }

/** What a tap on a blocked action does besides saying why: nothing from it ever reaches the computer. */
enum class Fix {
    /** Look for the computer again. */
    RETRY,
    /** The feature is off on this phone: a button turns it on. */
    TURN_ON_HERE,
    /** Android has not granted it: the request (or the system page) opens at once. */
    ALLOW,
    /** Only the computer (or an update) can change it: the line says where. */
    NONE,
}

/** What Home shows for a given state: pure, so a test can say what appears without drawing anything. */
object HomeDerive {
    const val RECENT_FILES = 3
    const val RECENT_NOTES = 2
    const val PREVIEW_CHARS = 48

    /**
     * The newest [RECENT_FILES] files sent or received, newest first (the store already lists them that way). The same file
     * sent or received again (same name, size and direction) is one card here; the Files tab still lists every transfer.
     */
    fun recentFiles(history: List<TransferRecord>): List<TransferRecord> =
        history.distinctBy { Triple(it.name, it.size, it.sent) }.take(RECENT_FILES)

    /** One line of the copied text, or null when there is nothing worth showing. */
    fun clipboardPreview(raw: String?, max: Int = PREVIEW_CHARS): String? {
        val line = raw?.let { Regex("\\s+").replace(it, " ").trim() }?.takeIf { it.isNotEmpty() } ?: return null
        return if (line.length <= max) line else line.take(max - 1).trimEnd() + "…"
    }

    /** The computer's actions can be used: connected and not waiting for an update. Everything that sends something asks this, never `ready`. */
    fun usable(s: HomeState): Boolean = whyNot(s) == null

    fun whyNot(s: HomeState): NotReady? = whyNot(s.updateNeeded, s.ready, s.connection)

    /** The same rule from its three inputs, for the widgets and tiles that have no [HomeState]. */
    fun whyNot(updateNeeded: Boolean, ready: Boolean, connection: DeviceConnection): NotReady? = when {
        updateNeeded -> NotReady.UPDATE
        ready -> null
        connection == DeviceConnection.CONNECTING -> NotReady.CONNECTING
        connection == DeviceConnection.UNAVAILABLE -> NotReady.CANT_REACH
        else -> NotReady.OFFLINE
    }

    fun blocker(n: NotReady): Blocker = when (n) {
        NotReady.UPDATE -> Blocker.UPDATE
        NotReady.CONNECTING -> Blocker.CONNECTING
        NotReady.CANT_REACH -> Blocker.CANT_REACH
        NotReady.OFFLINE -> Blocker.OFFLINE
    }

    /** Why [action] (a [PaletteModel.Action] id) cannot be used now; null when it can. Actions that do not need the computer never are. */
    fun blocker(s: HomeState, action: String): Blocker? {
        val o = s.offers
        val offered = when (action) {
            PaletteModel.Action.SEND_FILES -> o.files
            PaletteModel.Action.SEND_CLIPBOARD -> o.clipboard
            PaletteModel.Action.SEND_LINK -> o.link
            PaletteModel.Action.DESK_SCREEN, PaletteModel.Action.SHARE_SCREEN -> o.screen
            PaletteModel.Action.RING -> o.ring
            PaletteModel.Action.PING -> o.ping
            PaletteModel.Action.LOCK -> o.lock
            PaletteModel.Action.UNLOCK -> s.canUnlock
            PaletteModel.Action.NEW_TERMINAL -> true
            else -> return null
        }
        whyNot(s)?.let { return blocker(it) }
        if (offered) return null
        return if (action == PaletteModel.Action.UNLOCK) s.unlockBlocker else o.why[action] ?: Blocker.DESK_OFF
    }

    fun fix(b: Blocker): Fix = when (b) {
        Blocker.CONNECTING, Blocker.CANT_REACH, Blocker.OFFLINE -> Fix.RETRY
        Blocker.PHONE_OFF -> Fix.TURN_ON_HERE
        Blocker.PERMISSION -> Fix.ALLOW
        Blocker.UPDATE, Blocker.DESK_OFF, Blocker.NOT_SAME_WIFI, Blocker.UNLOCK_OFF -> Fix.NONE
    }

    /** The one line a tap says, formatted with the action's name and then the computer's. */
    @androidx.annotation.StringRes
    fun message(b: Blocker): Int = when (b) {
        Blocker.UPDATE -> org.desklink.mobile.R.string.home_blocked_update
        Blocker.CONNECTING -> org.desklink.mobile.R.string.home_blocked_connecting
        Blocker.CANT_REACH -> org.desklink.mobile.R.string.home_blocked_unreachable
        Blocker.OFFLINE -> org.desklink.mobile.R.string.home_blocked_offline
        Blocker.PHONE_OFF -> org.desklink.mobile.R.string.home_blocked_phone_off
        Blocker.PERMISSION -> org.desklink.mobile.R.string.home_blocked_permission
        Blocker.DESK_OFF -> org.desklink.mobile.R.string.home_blocked_desk_off
        Blocker.NOT_SAME_WIFI -> org.desklink.mobile.R.string.home_blocked_same_wifi
        Blocker.UNLOCK_OFF -> org.desklink.mobile.R.string.home_blocked_unlock_off
    }

    /** The short "Needs …" under a dimmed action (the palette, a tile), formatted with the computer's name. */
    @androidx.annotation.StringRes
    fun needs(b: Blocker): Int = when (b) {
        Blocker.UPDATE -> org.desklink.mobile.R.string.home_needs_update
        Blocker.CONNECTING, Blocker.CANT_REACH, Blocker.OFFLINE -> org.desklink.mobile.R.string.home_needs_link
        Blocker.PHONE_OFF -> org.desklink.mobile.R.string.home_needs_turning_on
        Blocker.PERMISSION -> org.desklink.mobile.R.string.home_needs_permission
        Blocker.DESK_OFF, Blocker.UNLOCK_OFF -> org.desklink.mobile.R.string.home_needs_desk
        Blocker.NOT_SAME_WIFI -> org.desklink.mobile.R.string.home_needs_same_wifi
    }

    /** The fix button's words, or null when the line alone says what to do ([Fix.ALLOW] acts at once). */
    @androidx.annotation.StringRes
    fun fixLabel(f: Fix): Int? = when (f) {
        Fix.RETRY -> org.desklink.mobile.R.string.terminal_retry
        Fix.TURN_ON_HERE -> org.desklink.mobile.R.string.home_turn_on
        Fix.ALLOW, Fix.NONE -> null
    }

    /** The "Happening now" area exists only while something is happening. */
    fun happeningNow(s: HomeState): Boolean = s.live.any || s.transfer != null || s.media != null

    /** Cancel is offered only for a transfer that is still going. */
    fun canCancel(t: HomeTransfer?): Boolean = t != null && t.active

    /** Android 13+ asks for notifications; until it is granted Home says so, once, with a button (never an automatic prompt). */
    fun notificationsOff(sdk: Int, granted: Boolean): Boolean = sdk >= 33 && !granted

    /** The Recent area: files, then notes (always, they are local), then the mirrored-notifications line. */
    fun showRecentFiles(s: HomeState): Boolean = s.recent.isNotEmpty()

    fun showMirrored(s: HomeState): Boolean = s.deskNotifications > 0

    /** Whether the "Show all" link to the Files tab is worth showing: there is more than the rows shown. */
    fun moreFiles(s: HomeState): Boolean = s.historyCount > s.recent.size
}
