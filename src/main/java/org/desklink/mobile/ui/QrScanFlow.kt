/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.desklink.mobile.QrPairing

/**
 * Where a QR scan has got to. The camera runs only in [State.Scanning]; every way out of
 * [State.Pairing] (refused, expired, unreachable, timed out, cancelled) lands in [State.Stopped],
 * which keeps the camera off until the person asks to [rescan]. A code still in view of the
 * camera therefore cannot start the next attempt behind their back, and a code that failed once
 * is never remembered as "already seen": the next scan starts afresh.
 */
class QrScanFlow {
    sealed interface State {
        data object Scanning : State

        /** [token] is unique per attempt, so a late timeout or callback of an earlier one is ignored. */
        data class Pairing(val id: String, val stage: QrPairing.Stage, val token: Int) : State

        /** Camera off. [message] says why in plain words (null: the person cancelled). */
        data class Stopped(val message: String?) : State
    }

    var state: State by mutableStateOf(State.Scanning)
        private set

    /** Changes on every [rescan]/[reset]; keys the camera view so it restarts. */
    var round by mutableIntStateOf(0)
        private set

    private var tokens = 0

    /** A decoded code counts only while scanning; a stray second decode is dropped. */
    fun accepts() = state is State.Scanning

    /** The code was read but is not usable (not ours, or waiting on the person's answer): stop, with a [message] if there is one. */
    fun reject(message: String?): Boolean = stopFrom<State.Scanning>(message)

    /** Begin pairing with [id]. Refused only while an attempt is already running. */
    fun start(id: String): Boolean {
        if (state is State.Pairing) return false
        state = State.Pairing(id, QrPairing.Stage.CONNECTING, ++tokens)
        return true
    }

    fun progress(id: String, stage: QrPairing.Stage) {
        val s = state as? State.Pairing ?: return
        if (s.id == id) state = s.copy(stage = stage)
    }

    /** The attempt for [id] failed. False when it was no longer the live one (cancelled, timed out, replaced). */
    fun fail(id: String, message: String): Boolean {
        val s = state as? State.Pairing ?: return false
        if (s.id != id) return false
        state = State.Stopped(message)
        return true
    }

    /** [token]'s attempt took too long. */
    fun timedOut(token: Int, message: String): Boolean {
        val s = state as? State.Pairing ?: return false
        if (s.token != token) return false
        state = State.Stopped(message)
        return true
    }

    /** The person cancelled: stopped, silently. */
    fun cancel(): Boolean = stopFrom<State.Pairing>(null)

    /** The person asked to scan again. */
    fun rescan() {
        if (state is State.Stopped) reset()
    }

    /** Back to a fresh scan (leaving or re-entering the scan screen). */
    fun reset() {
        state = State.Scanning
        round++
    }

    private inline fun <reified S : State> stopFrom(message: String?): Boolean {
        if (state !is S) return false
        state = State.Stopped(message)
        return true
    }

    companion object {
        /** From the first scan to a pairing: longer than the 10 s link wait plus the desk's answer. */
        const val TIMEOUT_MS = 20_000L
    }
}
