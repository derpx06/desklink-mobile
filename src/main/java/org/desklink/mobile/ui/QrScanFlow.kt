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

    /** The running attempt (the dial/wait/proof worker), owned here so that every way out takes it back, and a finished one is left alone. */
    private var attempt: QrPairing.Attempt? = null

    /** Begin pairing with [id]; the new attempt's token is in [state]. Refused only while an attempt is already running. */
    fun start(id: String): Boolean {
        if (state is State.Pairing) return false
        state = State.Pairing(id, QrPairing.Stage.CONNECTING, ++tokens)
        return true
    }

    /** The worker that [start] began for [token]; ignored if that attempt has already ended (it reported its own failure at once). */
    fun attach(token: Int, started: QrPairing.Attempt) {
        if ((state as? State.Pairing)?.token == token) attempt = started
    }

    fun progress(token: Int, stage: QrPairing.Stage) {
        val s = state as? State.Pairing ?: return
        if (s.token == token) state = s.copy(stage = stage)
    }

    /**
     * The attempt [token] failed. False when it was no longer the live one (cancelled, timed out, replaced). [undo]: also take back what
     * it did (a refusal after the proof, a replace to restore); false when the worker itself reported the failure and has already.
     */
    fun fail(token: Int, message: String, undo: Boolean = true): Boolean {
        val s = state as? State.Pairing ?: return false
        if (s.token != token) return false
        state = State.Stopped(message)
        release(undo)
        return true
    }

    /** [token]'s attempt took too long. */
    fun timedOut(token: Int, message: String): Boolean = fail(token, message)

    /**
     * The person cancelled: stopped, silently, and the attempt is taken back. False when nothing was cancelled: no attempt, or the
     * pairing had completed ([isPaired]) before the screen noticed, in which case it is marked done and left alone (the caller goes Home).
     */
    fun cancel(isPaired: (String) -> Boolean): Boolean {
        val s = state as? State.Pairing ?: return false
        if (isPaired(s.id)) { attempt?.markSucceeded(); return false }
        state = State.Stopped(null)
        release(undo = true)
        return true
    }

    /** The pairing for [id] completed: its attempt can no longer be cancelled or undone, by anything. */
    fun succeeded(id: String) {
        if ((state as? State.Pairing)?.id == id) attempt?.markSucceeded()
    }

    /**
     * The scan screen is left. A pairing still being negotiated is taken back, unless [isPaired] says it completed (a race with the
     * screen noticing); one that succeeded is never touched.
     */
    fun leave(isPaired: (String) -> Boolean) {
        (state as? State.Pairing)?.let { if (isPaired(it.id)) attempt?.markSucceeded() }
        release(undo = true)
        reset()
    }

    /** The person asked to scan again. */
    fun rescan() {
        if (state is State.Stopped) reset()
    }

    /** Back to a fresh scan. */
    fun reset() {
        state = State.Scanning
        round++
    }

    private fun release(undo: Boolean) {
        val a = attempt
        attempt = null
        if (undo) a?.cancel() // a no-op for an attempt that succeeded
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
