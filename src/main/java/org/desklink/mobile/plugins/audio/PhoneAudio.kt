/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.audio

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.desklink.mobile.R
import org.desklink.mobile.helpers.AppLog
import org.desklink.mobile.helpers.LifecycleHelper
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.helpers.RequestReach
import org.desklink.mobile.webrtc.WebRtcRemoteSessionControlMessage.Companion.DESK_TO_PHONE

/**
 * F1 (A2): at most one audio session per direction with a desk. "Hearing" is the desk's sound
 * playing here (this phone asked: the requester always hears); "sending" is this phone's
 * microphone or sound going to the desk (the desk asked). Pure, so the rules are testable.
 */
class AudioSessions {
    data class Session(val remoteSessionId: String, val direction: String, val source: String, val live: Boolean = false)

    var hearing: Session? = null
        private set
    var sending: Session? = null
        private set

    /** A new session in its direction; returns the one it replaces (to be told `audio-stop {replaced}`). */
    @Synchronized
    fun open(session: Session): Session? {
        val previous = if (session.direction == DESK_TO_PHONE) hearing else sending
        if (session.direction == DESK_TO_PHONE) hearing = session else sending = session
        return previous?.takeIf { it.remoteSessionId != session.remoteSessionId }
    }

    /** The sender started: true when [remoteSessionId] is still the session of its direction. */
    @Synchronized
    fun ready(remoteSessionId: String): Session? {
        hearing?.takeIf { it.remoteSessionId == remoteSessionId }?.let { return it.copy(live = true).also { s -> hearing = s } }
        sending?.takeIf { it.remoteSessionId == remoteSessionId }?.let { return it.copy(live = true).also { s -> sending = s } }
        return null
    }

    /** Ends [remoteSessionId] in whichever direction holds it; returns what ended. */
    @Synchronized
    fun end(remoteSessionId: String): Session? {
        hearing?.takeIf { it.remoteSessionId == remoteSessionId }?.let { hearing = null; return it }
        sending?.takeIf { it.remoteSessionId == remoteSessionId }?.let { sending = null; return it }
        return null
    }

    @Synchronized
    fun clear(): List<Session> = listOfNotNull(hearing, sending).also { hearing = null; sending = null }
}

/** The phone side of F1: per-desk settings, the request heads-up and the live state Home shows. */
object PhoneAudio {
    private const val NOTIFICATION_ASK = 1735

    /** The desk whose request is waiting for the person, and what it wants. */
    data class Pending(val deviceId: String, val remoteSessionId: String, val source: String)

    @Volatile var pending: Pending? = null

    /** The desk this phone's microphone or sound is going to right now (Home's coral row). */
    var sendingTo by mutableStateOf<String?>(null)
    var sendingSource by mutableStateOf<String?>(null)

    /** The desk whose sound is playing here right now (Home's live row, with End). */
    var hearingFrom by mutableStateOf<String?>(null)

    /**
     * One microphone (or one projection): while this phone sends to one computer, another's request is refused as
     * busy instead of sharing the audio module, where either one stopping would silence the other.
     */
    fun busyWith(sendingTo: String?, requester: String): Boolean = sendingTo != null && sendingTo != requester

    /** The one audio module may be put down by the computer it is feeding (or by anyone when it feeds nobody), never by another's session ending. */
    fun mayReset(feeding: String?, deviceId: String): Boolean = feeding == null || feeding == deviceId

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    /** "Play <desk>'s sound here": asked again on every link while on. Off by default. */
    fun playDeskSound(context: Context, deviceId: String) = prefs(context).getBoolean("desk_sound_$deviceId", false)

    fun setPlayDeskSound(context: Context, deviceId: String, on: Boolean) = prefs(context).edit { putBoolean("desk_sound_$deviceId", on) }

    /** "Let <desk> play sound here": the desk may start its sound unasked (A2). On by default. */
    fun deskMayPlay(context: Context, deviceId: String) = prefs(context).getBoolean("desk_sound_push_$deviceId", true)

    fun setDeskMayPlay(context: Context, deviceId: String, on: Boolean) = prefs(context).edit { putBoolean("desk_sound_push_$deviceId", on) }

    fun volume(context: Context, deviceId: String) = prefs(context).getInt("desk_sound_volume_$deviceId", 100)

    fun setVolume(context: Context, deviceId: String, percent: Int) = prefs(context).edit { putInt("desk_sound_volume_$deviceId", percent.coerceIn(0, 100)) }

    private fun consentKey(deviceId: String) = "mic_consent_$deviceId"

    fun consented(context: Context, deviceId: String) = prefs(context).getBoolean(consentKey(deviceId), false)

    fun rememberConsent(context: Context, deviceId: String) = prefs(context).edit { putBoolean(consentKey(deviceId), true) }

    /** The person takes the remembered consent back: the next request from this computer asks again. */
    fun forgetConsent(context: Context, deviceId: String) = prefs(context).edit { remove(consentKey(deviceId)) }

    fun hasMicPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * The desk wants this phone's microphone or sound. Android lets a microphone (or projection)
     * service start only from a visible app, so: the activity at once while DeskLink is in front,
     * else a heads-up the person taps.
     */
    fun ask(context: Context, deskName: String, request: Pending) {
        pending = request
        val open = Intent(context, PhoneAudioActivity::class.java)
            .putExtra(PhoneAudioActivity.EXTRA_DEVICE, request.deviceId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(context, NOTIFICATION_ASK, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = context.getString(if (request.source == "mic") R.string.audio_request_mic else R.string.audio_request_sound, deskName)
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.audio_tap_answer))
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify("audio:${request.deviceId}", NOTIFICATION_ASK, notification)
        }
        // The heads-up always goes up (a background start may be silently refused); in front the
        // screen opens at once too, and takes the heads-up down.
        if (LifecycleHelper.isInForeground) runCatching { context.startActivity(open) }
        opened = false
        val app = context.applicationContext
        // Nobody saw the question (notifications off) or answered it: the computer is told, in words, instead of waiting for ever.
        when (RequestReach.check(context, NotificationHelper.Channels.REQUESTS)) {
            RequestReach.Verdict.NOTIFICATIONS_OFF -> {
                AppLog.event("audio", "request not shown: notifications are off")
                end(app, request, "notifications")
                return
            }
            RequestReach.Verdict.QUIET -> AppLog.event("audio", "request channel is quiet: the heads-up may not pop up")
            RequestReach.Verdict.OK -> Unit
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (pending === request && !opened) end(app, request, "timeout") }, ASK_TIMEOUT_MS)
    }

    /** What to tell the person on the phone when a request ends for a reason that is not their own "no" (null for that). */
    fun reasonTextRes(reason: String): Int? = when (reason) {
        "permission" -> R.string.audio_mic_denied
        "unavailable" -> R.string.audio_unavailable
        "unsupported" -> R.string.audio_unsupported
        "busy" -> R.string.audio_busy
        else -> null // "declined": the person said no themselves
    }

    fun reasonText(context: Context, reason: String): String? = reasonTextRes(reason)?.let(context::getString)

    /** True once [PhoneAudioActivity] has the question on screen: the timeout no longer applies. */
    @Volatile var opened = false

    const val ASK_TIMEOUT_MS = 60_000L

    private fun end(context: Context, request: Pending, reason: String) {
        if (pending !== request) return
        pending = null
        clearAsk(context, request.deviceId)
        org.desklink.mobile.DeskLinkApplication.getInstance().webRtcCoordinator(request.deviceId)?.denyAudio(reason)
    }

    /** DeskLink came to the front while a question was waiting (the heads-up was missed): its screen opens now. */
    fun openIfWaiting(context: Context) {
        val waiting = pending ?: return
        if (opened) return
        runCatching {
            context.startActivity(
                Intent(context, PhoneAudioActivity::class.java).putExtra(PhoneAudioActivity.EXTRA_DEVICE, waiting.deviceId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun clearAsk(context: Context, deviceId: String) =
        NotificationManagerCompat.from(context).cancel("audio:$deviceId", NOTIFICATION_ASK)
}
