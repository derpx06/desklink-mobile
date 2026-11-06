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

        /** A session was already asked for before this screen was rebuilt: show the progress, ask for nothing more. */
        data object Waiting : Destination
    }

    /** What a rebuilt screen (rotation, dark mode, another computer's notification) kept. */
    data class Saved(val deviceId: String?, val openId: String?, val starting: Destination.New?, val creating: Boolean)

    /**
     * Where a screen starts. A rebuilt screen goes by what it kept, never by its intent again: the intent still says "new terminal",
     * and following it would start a second session. What was kept for another computer is not this one's.
     */
    fun initial(saved: Saved?, deviceId: String, fromIntent: Destination): Destination = when {
        saved == null || saved.deviceId != deviceId -> fromIntent
        saved.openId != null -> Destination.Session(saved.openId)
        saved.starting != null -> saved.starting
        saved.creating -> Destination.Waiting
        else -> Destination.Tab
    }

    /** A second "new terminal" (a double tap, a quick action on top of a tile) while one is being started is the same wish. */
    fun acceptNew(starting: Boolean, creating: Boolean): Boolean = !starting && !creating

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
