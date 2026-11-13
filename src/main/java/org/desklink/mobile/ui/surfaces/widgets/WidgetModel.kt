/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.surfaces.widgets

import androidx.annotation.StringRes
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.components.LinkGlyphState
import org.desklink.mobile.ui.compose.components.linkGlyphState
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.screen.home.HomeDerive
import org.desklink.mobile.ui.compose.screen.home.NotReady
import org.desklink.mobile.webrtc.WebRtcConnectionPath

/** What the phone knows about whether the computer lets this phone open a terminal. */
enum class TerminalAccess { ALLOWED, OFF, UNKNOWN }

/**
 * One computer as the home-screen widgets need it: plain values, so the rules below are unit-tested
 * without a device. Built from the live objects by [WidgetFacts]; the same rules as the Home tiles.
 */
data class ComputerFacts(
    val id: String,
    val name: String,
    val connection: DeviceConnection,
    val path: WebRtcConnectionPath?,
    /** The feature link (WebRTC) is up: actions go out now, desk-owned values are current. */
    val ready: Boolean,
    val updateNeeded: Boolean = false,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val rttMs: Int? = null,
    /** Which features are loaded for this computer (the Home tiles' `HomeOffers`). */
    val screen: Boolean = false,
    val files: Boolean = false,
    val clipboard: Boolean = false,
    val ring: Boolean = false,
    val lock: Boolean = false,
    val terminals: TerminalAccess = TerminalAccess.UNKNOWN,
    val terminalCount: Int = 0,
    val lastSeen: Long? = null,
)

/** The words on the status chip. */
enum class WidgetStatus(@StringRes val label: Int) {
    WIFI(R.string.home_connected_wifi),
    RELAY(R.string.home_connected_relay),
    TURN(R.string.home_connected_turn),
    INTERNET(R.string.home_connected_internet),
    CONNECTED(R.string.connection_connected),
    CONNECTING(R.string.connection_connecting),
    UPDATE(R.string.home_tile_update),
    CANT_REACH(R.string.widget_cant_reach),
    NO_COMPUTER(R.string.surfaces_not_paired),
    ;

    /** The dot beside the words: mint only while connected, amber while waiting. */
    val tone: Tone
        get() = when (this) {
            WIFI, RELAY, TURN, INTERNET, CONNECTED -> Tone.OK
            CONNECTING, UPDATE -> Tone.WAIT
            CANT_REACH, NO_COMPUTER -> Tone.OFF
        }
}

enum class Tone { OK, WAIT, OFF }

enum class WidgetAction { SCREEN, FILES, CLIPBOARD, RING, LOCK, TERMINAL }

/** A button as the widget draws it. [reason] is the hint the Home tile would show under the name. */
data class ActionUi(val action: WidgetAction, val enabled: Boolean, @StringRes val reason: Int? = null)

/** What a tap does once the widget asks the rules. */
sealed interface Gate {
    data object Go : Gate

    /** Say it (a short toast, formatted with the computer's name) and do nothing else. */
    data class Say(@StringRes val message: Int) : Gate

    /**
     * The last answer was "terminal access is off": never start a terminal on it, but never trust it for good either. The tap
     * asks the computer again and opens the Terminals tab, which has the Retry and the explanation.
     */
    data object TerminalsTab : Gate
}

sealed interface NotesView {
    /** Locked phone: no text at all, a "tap to show" line. */
    data object Hidden : NotesView
    data object Empty : NotesView

    /** The sealed notes cannot be opened just now. */
    data object Unavailable : NotesView
    data class Titles(val lines: List<NoteLine>, val more: Int) : NotesView
}

data class NoteLine(val id: String, val title: String)

sealed interface TerminalsView {
    /** "New terminal", with how many are open. */
    data class Ready(val open: Int) : TerminalsView

    /** The computer has not allowed it (as last heard): shown as it is; the tap asks again through the Terminals tab. */
    data object AccessOff : TerminalsView
    data class NotReady(@StringRes val label: Int) : TerminalsView
}

sealed interface TransfersView {
    data object Idle : TransfersView
    data class Running(val name: String, val upload: Boolean, val percent: Int?) : TransfersView
    data class Last(val name: String, val sent: Boolean, val time: Long) : TransfersView
}

/** The pure parts, unit-tested. */
object WidgetRules {
    fun status(f: ComputerFacts?): WidgetStatus = when {
        f == null -> WidgetStatus.NO_COMPUTER
        f.ready -> when (linkGlyphState(f.connection, f.path)) {
            LinkGlyphState.RELAY -> if (f.path == WebRtcConnectionPath.TURN) WidgetStatus.TURN else WidgetStatus.RELAY
            LinkGlyphState.DIRECT -> when {
                f.connection == DeviceConnection.LOCAL || f.path == WebRtcConnectionPath.LAN -> WidgetStatus.WIFI
                f.path == WebRtcConnectionPath.DIRECT || f.connection == DeviceConnection.AWAY -> WidgetStatus.INTERNET
                else -> WidgetStatus.CONNECTED
            }
            else -> WidgetStatus.CONNECTED
        }
        f.updateNeeded -> WidgetStatus.UPDATE
        f.connection == DeviceConnection.CONNECTING -> WidgetStatus.CONNECTING
        else -> WidgetStatus.CANT_REACH
    }

    fun notReady(f: ComputerFacts): NotReady? = HomeDerive.whyNot(f.updateNeeded, f.ready, f.connection)

    /** The toast for a refused tap: Home's own "not ready" wording. */
    @StringRes
    fun notReadyMessage(why: NotReady): Int = when (why) {
        NotReady.UPDATE -> R.string.home_update_deskLink
        NotReady.CONNECTING -> R.string.home_unavailable_connecting
        NotReady.CANT_REACH -> R.string.home_unavailable_unreachable
        NotReady.OFFLINE -> R.string.home_unavailable_offline
    }

    /** What the tile shows under the name while off: Home's reasons. */
    @StringRes
    fun notReadyHint(why: NotReady): Int = when (why) {
        NotReady.UPDATE -> R.string.home_tile_update
        NotReady.CONNECTING -> R.string.connection_connecting
        NotReady.CANT_REACH -> R.string.connection_unavailable
        NotReady.OFFLINE -> R.string.connection_offline
    }

    private fun offered(a: WidgetAction, f: ComputerFacts) = when (a) {
        WidgetAction.SCREEN -> f.screen
        WidgetAction.FILES -> f.files
        WidgetAction.CLIPBOARD -> f.clipboard
        WidgetAction.RING -> f.ring
        WidgetAction.LOCK -> f.lock
        WidgetAction.TERMINAL -> true
    }

    /**
     * The quick-action buttons in order. Every one is always there (as on Home): one the computer cannot take now looks off,
     * with Home's reason, and a tap says why. Lock included: it is off while the computer does not let this phone lock it.
     */
    fun actions(f: ComputerFacts?): List<ActionUi> {
        val order = listOf(WidgetAction.SCREEN, WidgetAction.FILES, WidgetAction.CLIPBOARD, WidgetAction.RING, WidgetAction.LOCK)
        return order.map { a ->
            val why = f?.let(::notReady)
            when {
                f == null -> ActionUi(a, false, R.string.surfaces_not_paired)
                why != null -> ActionUi(a, false, notReadyHint(why))
                !offered(a, f) -> ActionUi(a, false, R.string.home_action_unavailable)
                else -> ActionUi(a, true)
            }
        }
    }

    /** What a tap does *now*, from the live state (the picture may be older than the link). */
    fun gate(a: WidgetAction, f: ComputerFacts?): Gate {
        if (f == null) return Gate.Say(R.string.surfaces_not_paired)
        notReady(f)?.let { return Gate.Say(notReadyMessage(it)) }
        if (a == WidgetAction.TERMINAL) return if (f.terminals == TerminalAccess.OFF) Gate.TerminalsTab else Gate.Go
        return if (offered(a, f)) Gate.Go else Gate.Say(R.string.home_feature_off)
    }

    /**
     * Note titles on the widget: never while the phone is locked (a placeholder, no text), and
     * never from notes that could not be opened. [max] is how many rows the widget's size has room for.
     */
    fun notes(notes: List<NoteLine>, locked: Boolean, unavailable: Boolean, max: Int): NotesView = when {
        locked -> NotesView.Hidden
        unavailable -> NotesView.Unavailable
        notes.isEmpty() -> NotesView.Empty
        else -> NotesView.Titles(notes.take(max), (notes.size - max).coerceAtLeast(0))
    }

    /** The terminal launcher: only a computer that allows it gets "New terminal". */
    fun terminals(f: ComputerFacts?): TerminalsView = when {
        f == null -> TerminalsView.NotReady(R.string.surfaces_not_paired)
        f.terminals == TerminalAccess.OFF -> TerminalsView.AccessOff
        else -> notReady(f)?.let { TerminalsView.NotReady(notReadyHint(it)) } ?: TerminalsView.Ready(f.terminalCount)
    }

    /**
     * What the computer last said about terminal access: its live answer when it has given one (a list, or a refusal of the
     * first request, which has no list), else the stored last answer, else unknown. Only a hint while the link is down.
     */
    fun terminalAccess(liveAllowed: Boolean?, storedAllowed: Boolean?): TerminalAccess = when (liveAllowed ?: storedAllowed) {
        true -> TerminalAccess.ALLOWED
        false -> TerminalAccess.OFF
        null -> TerminalAccess.UNKNOWN
    }

    /** A running transfer first, else the newest finished one, else nothing. */
    fun transfers(running: TransfersView.Running?, last: TransfersView.Last?): TransfersView = running ?: last ?: TransfersView.Idle

    /**
     * Room for the extras of a one-row widget (the action labels, the status widget's delay line) at this font scale. From
     * 150 % text they would be cut off, so they go and the essentials (name, state, icons with their spoken names) stay.
     */
    fun roomForExtras(fontScale: Float): Boolean = fontScale < 1.5f

    /** How many 48 dp note rows fit a widget this tall (dp): the header takes 56 (8 + 48). */
    fun noteRows(heightDp: Float): Int = ((heightDp - 56f) / 48f).toInt().coerceIn(1, 5)
}
