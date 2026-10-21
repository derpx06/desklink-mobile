/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.telephony

/** What the phone can do to a call; the platform behind it is swapped for a fake in tests. */
interface CallControls {
    fun canAnswer(): Boolean
    fun canDial(): Boolean
    fun acceptRingingCall(): Boolean
    /** TelecomManager.endCall (API 28+, ANSWER_PHONE_CALLS); false when it is not available. */
    fun endCall(): Boolean
    /** The dialer notification's own Decline / Hang up button, when the listener sees it. */
    fun notificationAction(hangUp: Boolean): Boolean
    fun muteRinger(): Boolean
    fun setMicrophoneMute(mute: Boolean): Boolean
    fun placeCall(number: String): Boolean
}

/** Flags T1 reports besides the calls. */
class CallFlags {
    var muted = false
    var silenced = false
}

/** T2, `desklink.telephony.request {action, callId?, number?}`. */
object CallRequests {
    const val ERROR_PERMISSION = "permission"
    const val ERROR_NO_CALL = "no-call"
    const val ERROR_FAILED = "failed"

    /** Runs [action]; null when done (the caller replies with a fresh snapshot), else the T2 error. */
    fun handle(action: String, number: String?, tracker: CallTracker, controls: CallControls, flags: CallFlags): String? = when (action) {
        "answer" -> when {
            tracker.ringing() == null -> ERROR_NO_CALL
            !controls.canAnswer() -> ERROR_PERMISSION
            controls.acceptRingingCall() -> null
            else -> ERROR_FAILED
        }
        "decline" -> if (tracker.ringing() == null) ERROR_NO_CALL else {
            tracker.markDeclined()
            hangUp(controls)
        }
        "end" -> if (!tracker.hasCall()) ERROR_NO_CALL else hangUp(controls)
        "silence" -> when {
            tracker.ringing() == null -> ERROR_NO_CALL
            controls.muteRinger() -> null.also { flags.silenced = true }
            else -> ERROR_FAILED
        }
        "mute", "unmute" -> when {
            !tracker.hasCall() -> ERROR_NO_CALL
            controls.setMicrophoneMute(action == "mute") -> null.also { flags.muted = action == "mute" }
            else -> ERROR_FAILED
        }
        "dial" -> {
            val dialable = CallTracker.dialable(number)
            when {
                !controls.canDial() -> ERROR_PERMISSION
                dialable == null -> ERROR_FAILED
                controls.placeCall(dialable) -> null
                else -> ERROR_FAILED
            }
        }
        else -> ERROR_FAILED
    }

    private fun hangUp(controls: CallControls): String? = when {
        controls.endCall() -> null
        controls.notificationAction(hangUp = true) -> null
        !controls.canAnswer() -> ERROR_PERMISSION
        else -> ERROR_FAILED
    }
}
