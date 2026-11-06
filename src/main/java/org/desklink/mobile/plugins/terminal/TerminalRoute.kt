/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import org.desklink.mobile.webrtc.terminal.TerminalProtocol
import org.desklink.mobile.webrtc.terminal.TerminalSessionState

/**
 * Where an intent for [TerminalActivity] goes. The activity is the live terminal and nothing else: the list of
 * sessions is the Terminals tab, so an intent that names no session and asks for no new one (an old widget or
 * shortcut, a stale notification) is sent to the tab.
 */
object TerminalRoute {
    sealed interface Destination {
        /** Show this session. */
        data class Session(val id: String) : Destination

        /** Start a session (a shell, or one of [TerminalProtocol.KNOWN_LAUNCHERS]) and show it. */
        data class New(val launcher: String?, val cwd: String?) : Destination

        /** Nothing to show here: the Terminals tab lists the sessions. */
        data object Tab : Destination
    }

    fun decide(terminalId: String?, newRequested: Boolean, launcher: String? = null, cwd: String? = null): Destination = when {
        !terminalId.isNullOrBlank() -> Destination.Session(terminalId)
        newRequested -> Destination.New(launcher?.takeIf { it in TerminalProtocol.KNOWN_LAUNCHERS }, cwd?.takeIf { it.isNotBlank() })
        else -> Destination.Tab
    }

    /** Why a session that was running stopped being shown. */
    sealed interface Ending {
        /** The session no longer exists on the computer. */
        data object Ended : Ending

        /** The shell exited by itself. */
        data class Exited(val status: Long) : Ending

        /** The person at the computer ended it. */
        data object ByComputer : Ending
    }

    /**
     * What happened to the open session since it was seen running; null while it still runs, or if it was never seen running
     * (opening a session that has already exited is the person's choice, and does not leave again).
     */
    fun ending(wasRunning: Boolean, now: TerminalSessionState?): Ending? = when {
        !wasRunning -> null
        now == null || now.gone -> Ending.Ended
        now.exitStatus != null -> Ending.Exited(now.exitStatus)
        else -> null
    }

    fun isRunning(session: TerminalSessionState?): Boolean = session != null && !session.gone && session.exitStatus == null
}
