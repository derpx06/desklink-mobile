/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.telephony

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.json.JSONArray
import org.json.JSONObject

/** One call as T1 describes it. */
data class TrackedCall(
    val callId: String,
    val state: String,
    val incoming: Boolean,
    /** Unix ms when it was answered (talking) or placed. */
    val startedAt: Long?,
    val contactName: String? = null,
    val number: String? = null,
)

/** What the phone's own telephony reports: no call, ringing, or off the hook (TelephonyManager). */
enum class LineState { IDLE, RINGING, OFFHOOK }

/**
 * The calls, from the platform's coarse line state (all an app without call-log access gets):
 * IDLE → RINGING is a new incoming call; RINGING → OFFHOOK answers it (a call already talking is
 * then held); IDLE → OFFHOOK is an outgoing call; OFFHOOK → RINGING is a call waiting; RINGING →
 * IDLE with nothing else is missed (unless the phone declined it on request); OFFHOOK → IDLE ends
 * everything. Pure and clock-injected, for tests.
 */
class CallTracker(private val now: () -> Long, private val newId: () -> String) {
    var calls: List<TrackedCall> = emptyList()
        private set
    private var line = LineState.IDLE
    /** Declined from here (or the desk): its ringing → idle is not a missed call. */
    private var declined: String? = null

    /** The line changed. Returns a missed call when one just went unanswered. */
    fun onLine(next: LineState): TrackedCall? {
        val previous = line
        line = next
        if (previous == next) return null
        var missed: TrackedCall? = null
        calls = when (next) {
            LineState.RINGING -> calls + TrackedCall(newId(), STATE_RINGING, incoming = true, startedAt = null)
            LineState.OFFHOOK -> when (previous) {
                LineState.RINGING -> calls.map { call ->
                    when (call.state) {
                        STATE_RINGING -> call.copy(state = STATE_TALKING, startedAt = now())
                        STATE_TALKING -> call.copy(state = STATE_HELD)
                        else -> call
                    }
                }
                else -> listOf(TrackedCall(newId(), STATE_TALKING, incoming = false, startedAt = now()))
            }
            LineState.IDLE -> {
                if (previous == LineState.RINGING) {
                    missed = calls.lastOrNull { it.state == STATE_RINGING }?.takeIf { it.callId != declined }
                }
                emptyList()
            }
        }
        if (next == LineState.IDLE) declined = null
        // A waiting call that stopped ringing while another stays up (OFFHOOK → OFFHOOK is not
        // reported by the platform; the ringing one disappears on the next change).
        return missed
    }

    /** The phone ended a ringing call on request: it is not "missed". */
    fun markDeclined() {
        declined = calls.lastOrNull { it.state == STATE_RINGING }?.callId
    }

    fun ringing(): TrackedCall? = calls.lastOrNull { it.state == STATE_RINGING }

    fun hasCall(): Boolean = calls.isNotEmpty()

    /** Names the current calls from the dialer notification (or contacts), when the person allows it. */
    fun annotate(contactName: String?, number: String?) {
        if (contactName == null && number == null) return
        calls = calls.map { if (it.state == STATE_RINGING || calls.size == 1) it.copy(contactName = contactName ?: it.contactName, number = number ?: it.number) else it }
    }

    companion object {
        const val STATE_RINGING = "ringing"
        const val STATE_DIALING = "dialing"
        const val STATE_TALKING = "talking"
        const val STATE_HELD = "held"

        /** T1: the full snapshot. Names and numbers only when [showCaller] (T3). */
        fun snapshotPacket(calls: List<TrackedCall>, muted: Boolean, silenced: Boolean, showCaller: Boolean) =
            NetworkPacket(DeskLinkProtocol.PACKET_TYPE_TELEPHONY).apply {
                this["calls"] = JSONArray(calls.map { call ->
                    JSONObject().apply {
                        put("callId", call.callId)
                        put("state", call.state)
                        put("incoming", call.incoming)
                        call.startedAt?.let { put("startedAt", it) }
                        if (showCaller) {
                            call.contactName?.let { put("contactName", it) }
                            call.number?.let { put("number", it) }
                        }
                    }
                })
                this["muted"] = muted
                this["silenced"] = silenced
            }

        /** `calls: []`: off, or no permission (Phase 1 empty form). */
        fun emptySnapshot() = snapshotPacket(emptyList(), muted = false, silenced = false, showCaller = false)

        /** T1 one-shot `{missed: {callId, contactName?, number?, at}}`. */
        fun missedPacket(call: TrackedCall, at: Long, showCaller: Boolean) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_TELEPHONY).apply {
            this["missed"] = JSONObject().apply {
                put("callId", call.callId)
                put("at", at)
                if (showCaller) {
                    call.contactName?.let { put("contactName", it) }
                    call.number?.let { put("number", it) }
                }
            }
        }

        /** T2's `{error, action}` reply, inside `desklink.telephony`. */
        fun errorPacket(error: String, action: String) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_TELEPHONY).apply {
            this["error"] = error
            this["action"] = action
        }

        /** A number the desk may dial: digits and the usual dialling characters, at most 32. */
        fun dialable(number: String?): String? =
            number?.trim()?.takeIf { it.length in 1..32 && it.all { c -> c.isDigit() || c in "+*#() -." } && it.any(Char::isDigit) }
    }
}
