/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.screen

import android.content.Intent
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.ui.compose.sessionPasswordLockedMessage
import org.desklink.mobile.webrtc.SessionPasswordDenial
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@LoadablePlugin
class ScreenControlPlugin : Plugin() {
    var remoteControlEnabled by mutableStateOf(false)
        private set

    /** The desktop refused control: the screen stops asking by itself until the person asks again (a menu entry), never a 3-second loop. */
    var remoteControlRefused by mutableStateOf(false)
        private set

    /** Why the computer refused control, while it is refused; null otherwise. Shown once per distinct reason. */
    var controlDenial by mutableStateOf<String?>(null)
        private set

    /** True once a frame of the current view has reached a renderer. */
    var remoteLive by mutableStateOf(false)
        private set

    /** How many screens the desktop shares; above one, the view can switch. */
    var remoteScreenCount by mutableStateOf(1)

    /** F2: what the desk is showing — "extend" (a virtual monitor of this view's size) or "mirror". */
    var remoteDisplay by mutableStateOf(ScreenDisplay.MIRROR)
        private set

    /** F2: the desk could not add a display; a one-off notice for the screen (then cleared). */
    var extendNotice by mutableStateOf<String?>(null)

    /** The desktop's latest text-focus report: the remote screen raises the
     * phone keyboard for an editable one and drops it for one that is not.
     * Every report is a new value, so a second "editable" (another field, or a
     * click into the same one after Back put the keyboard away) raises the
     * keyboard again: a plain Boolean stayed `true` and nothing reacted. */
    var desktopTextFocus by mutableStateOf(DesktopTextFocus())
        private set

    fun onDesktopTextFocus(editable: Boolean) {
        desktopTextFocus = desktopTextFocus.next(editable)
    }

    /** The desktop's own coordinate space (its `screen-ready`), which input must
     * be mapped into. Zero until the desktop has reported it. */
    var remoteScreenWidth by mutableStateOf(0)
        private set
    var remoteScreenHeight by mutableStateOf(0)
        private set

    /** The desktop refused a request for want of its session password: the screen asks for it,
     * clears this, and repeats the request. */
    var passwordPrompt by mutableStateOf<PasswordPrompt?>(null)

    /** [control]: the refused request was for control, not the view. */
    data class PasswordPrompt(val wrong: Boolean, val control: Boolean)
    private val frameSeen = AtomicBoolean(false)
    @Volatile
    private var phoneCaptureRequested = false
    @Volatile
    private var promptedAt: Long? = null
    @Volatile
    private var screenSessionActive = false
    override val displayName: String
        get() = context.getString(R.string.pref_plugin_screen_control)

    override val description: String
        get() = context.getString(R.string.pref_plugin_screen_control_desc)

    override fun onDestroy() {
        context.stopService(Intent(context, PhoneScreenCaptureService::class.java))
        phoneCaptureRequested = false
        screenSessionActive = false
        // Not the remote track: it belongs to the WebRTC peer, which delivers
        // it once, at negotiation. Plugins are reloaded right after the
        // handover, so clearing it here wiped every desktop picture before it
        // started — frames decoded, renderer never fed. The coordinator clears
        // it when the peer closes.
        resetView()
        latestStatus = ""
        super.onDestroy()
    }

    /**
     * Installs the current authenticated desktop video track for the screen UI.
     *
     * A track arriving is **not** a picture: it is delivered once per peer, at
     * negotiation time, long before anything is captured. "Live" is claimed only
     * when a frame actually reaches a renderer ([noteRemoteFrame]).
     */
    fun onRemoteVideoTrack(track: VideoTrack) {
        synchronized(remoteVideoLock) {
            remoteVideoTrack?.let { oldTrack ->
                remoteVideoSinks.values.forEach { runCatching { oldTrack.removeSink(it) } }
            }
            remoteVideoTrack = track
            Log.i(TAG, "Desktop video track installed (${track.id()}), ${remoteVideoSinks.size} renderer(s) waiting")
            remoteVideoSinks.values.forEach { sink ->
                runCatching { track.addSink(sink) }
                    .onFailure { Log.w(TAG, "Could not attach a renderer to the desktop track", it) }
            }
        }
    }

    /** Attaches a native WebRTC renderer to the current remote video track. */
    fun attachRemoteVideoSink(sink: VideoSink) {
        synchronized(remoteVideoLock) {
            // Attached twice (it was, on every open), a second forwarder replaced the first in the map while the
            // first stayed on the track: each frame drawn twice, and the first left feeding a released renderer.
            if (remoteVideoSinks.containsKey(sink)) return
            val forwardingSink = SizeTrackingVideoSink(sink, ::noteRemoteFrame)
            remoteVideoSinks[sink] = forwardingSink
            Log.i(TAG, "Renderer attached; desktop track ${if (remoteVideoTrack == null) "not yet installed" else "present"}")
            // A track from a peer that has since closed is disposed; touching it
            // throws, and this runs on the UI thread.
            remoteVideoTrack?.let { track ->
                runCatching { track.addSink(forwardingSink) }
                    .onFailure { Log.w(TAG, "Could not attach a renderer to the desktop track", it) }
            }
        }
    }

    /** Detaches a renderer and stops forwarding frames to it. */
    fun detachRemoteVideoSink(sink: VideoSink) {
        synchronized(remoteVideoLock) {
            remoteVideoSinks.remove(sink)?.let { forwardingSink ->
                remoteVideoTrack?.let { runCatching { it.removeSink(forwardingSink) } }
            }
        }
    }

    fun clearRemoteVideoTrack() {
        synchronized(remoteVideoLock) {
            remoteVideoTrack?.let { track ->
                remoteVideoSinks.values.forEach { runCatching { track.removeSink(it) } }
            }
            remoteVideoTrack = null
        }
    }

    /** First frame of the current view reached a renderer. */
    private fun noteRemoteFrame() {
        if (frameSeen.compareAndSet(false, true)) {
            Log.i(TAG, "First desktop frame ${viewRequestedAt?.let { ViewWait.clockMs() - it } ?: -1} ms after the view request")
            remoteLive = true
            latestStatus = context.getString(R.string.remote_screen_connected)
        }
    }

    /** When the current view request went out ([ViewWait.clockMs]), for [ViewWait]; null before one. */
    var viewRequestedAt by mutableStateOf<Long?>(null)
        private set

    fun onRemoteScreenRequesting() {
        remoteControlEnabled = false
        resetView()
        viewRequestedAt = ViewWait.clockMs()
        latestStatus = context.getString(R.string.remote_screen_requesting)
    }

    /** The desktop reports it has started capturing. This is a control-plane
     * claim, not a picture, and carries the desktop's own coordinate space. */
    fun onExtendUnsupported() {
        remoteDisplay = ScreenDisplay.MIRROR
        extendNotice = context.getString(R.string.remote_extend_unsupported, device.name)
    }

    fun onRemoteScreenReady(width: Int?, height: Int?, screenCount: Int? = null, display: String? = null) {
        remoteDisplay = if (display == ScreenDisplay.EXTEND) ScreenDisplay.EXTEND else ScreenDisplay.MIRROR
        if (width != null && height != null) {
            remoteScreenWidth = width
            remoteScreenHeight = height
        }
        if (screenCount != null) remoteScreenCount = screenCount
        if (!frameSeen.get()) {
            latestStatus = context.getString(R.string.remote_screen_waiting_frame)
        }
    }

    fun onRemoteControlRequesting() {
        latestStatus = context.getString(R.string.remote_control_requesting)
    }

    fun onRemoteControlGranted() {
        remoteControlEnabled = true
        remoteControlRefused = false
        controlDenial = null
        latestStatus = context.getString(R.string.remote_control_enabled)
    }

    /**
     * The computer refused control. A grant already held wins over a denial (the answer to an
     * earlier, repeated request arriving late), and the same refusal is announced once.
     */
    fun onRemoteControlDenied(reason: String?) {
        if (remoteControlEnabled) return
        val text = reason?.takeIf(String::isNotBlank) ?: context.getString(R.string.remote_control_denied)
        if (remoteControlRefused && controlDenial == text) return
        remoteControlRefused = true
        controlDenial = text
        latestStatus = reason?.takeIf(String::isNotBlank)?.let {
            context.getString(R.string.remote_control_denied_reason, it)
        } ?: text
    }

    /** The phone could not ask (no live view yet, link not ready): not a refusal, so nothing sticks and asking goes on. */
    fun onRemoteControlUnavailable() {
        if (!remoteControlEnabled) latestStatus = context.getString(R.string.remote_control_requesting)
    }

    /** A view or control request was refused for the session password. A lockout is only shown:
     * no prompt until it has passed. */
    fun onPasswordDenied(denial: SessionPasswordDenial, control: Boolean) {
        remoteControlEnabled = false
        if (!control) resetView()
        if (denial is SessionPasswordDenial.Locked) {
            passwordPrompt = null
            latestStatus = sessionPasswordLockedMessage(context, denial.seconds)
        } else {
            if (denial == SessionPasswordDenial.Wrong) org.desklink.mobile.ui.SessionGate.forget(context, device.deviceId)
            passwordPrompt = PasswordPrompt(wrong = denial == SessionPasswordDenial.Wrong, control = control)
            latestStatus = context.getString(R.string.session_password_needed)
        }
    }

    /** Control ended but the view is still there. */
    fun onRemoteControlStopped() {
        remoteControlEnabled = false
        latestStatus = context.getString(
            if (frameSeen.get()) R.string.remote_screen_connected else R.string.remote_screen_waiting_frame,
        )
    }

    fun onRemotePaused() {
        remoteControlEnabled = false
        latestStatus = context.getString(R.string.remote_screen_paused)
    }

    /** The view is over (released, denied, failed, or the connection went). */
    fun onRemoteSessionEnded(reason: String? = null) {
        remoteControlEnabled = false
        resetView()
        latestStatus = reason?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.remote_screen_stopped)
    }

    /** The WebRTC peer carrying the phone's capture is gone, and its capturer
     * with it. Left as it was, the service kept running and the plugin still
     * thought capture was live — so the next request from the desktop was
     * skipped: no prompt, no picture, "waiting" forever. */
    fun onCapturePeerClosed() {
        context.stopService(Intent(context, PhoneScreenCaptureService::class.java))
        phoneCaptureRequested = false
        promptedAt = null
        screenSessionActive = false
    }

    fun onRemoteViewFailed(reason: String?) {
        // The desk answers this phone's own Share screen with "busy" while another session runs.
        if (reason == REASON_BUSY) {
            val message = context.getString(R.string.screen_offer_busy, device.name)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
            onRemoteSessionEnded(message)
            return
        }
        onRemoteSessionEnded(
            reason?.takeIf(String::isNotBlank) ?: context.getString(R.string.remote_screen_error),
        )
    }

    /** Nothing answered a request in time; the user must act on the desktop. */
    fun onRemoteRequestTimedOut(control: Boolean) {
        if (!control) resetView()
        remoteControlEnabled = false
        latestStatus = context.getString(
            if (control) R.string.remote_control_timeout else R.string.remote_screen_timeout,
        )
    }

    /** The person asked for control again. */
    fun askControlAgain() { remoteControlRefused = false }

    private fun resetView() {
        remoteControlRefused = false
        controlDenial = null
        desktopTextFocus = DesktopTextFocus()
        frameSeen.set(false)
        remoteLive = false
        remoteScreenWidth = 0
        remoteScreenHeight = 0
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        when (np.type) {
            PACKET_TYPE_SCREEN_REQUEST -> {
                if (np.getString("role", "") == ROLE_PHONE_SCREEN) {
                    askForCapture(np.getBoolean(OFFERED_FIELD, false), capturing = phoneCaptureRequested)
                }
            }
            PACKET_TYPE_SCREEN_READY -> {
                screenSessionActive = true
                latestStatus = context.getString(R.string.remote_screen_connected)
            }
            PACKET_TYPE_SCREEN_ERROR -> {
                latestStatus = np.getString("message", context.getString(R.string.remote_screen_error))
            }
            PACKET_TYPE_SCREEN_STOP -> {
                // `screen.request` carries a `role`; `screen.stop` historically
                // did not, so stopping either direction stopped both. Closing
                // the desktop-screen viewer therefore killed the phone's own
                // MediaProjection capture — which cannot resume without the
                // user granting Android's consent dialog again.
                //
                // `WebRtcSessionCoordinator` already gets this right for the
                // control-plane path by checking the previous direction; this
                // is the same rule for the packet path. A stop with no role is
                // an older peer, and still stops both.
                val role = np.getString("role", "")
                if (role != ROLE_DESKTOP_SCREEN) {
                    context.stopService(Intent(context, PhoneScreenCaptureService::class.java))
                    phoneCaptureRequested = false
                }
                screenSessionActive = false
                remoteControlEnabled = false
                latestStatus = ""
            }
        }
        return np.type in SCREEN_PACKET_TYPES
    }

    fun requestDesktopScreen(
        maxDimension: Int = DEFAULT_MAX_DIMENSION,
        fps: Int = DEFAULT_FPS,
        quality: Int = DEFAULT_QUALITY
    ) {
        screenSessionActive = true
        remoteControlEnabled = false
        latestStatus = context.getString(R.string.remote_screen_requesting)
        device.sendPacket(
            createScreenRequestPacket(
                role = ROLE_DESKTOP_SCREEN,
                maxDimension = maxDimension,
                fps = fps,
                quality = quality
            )
        )
    }

    fun stopScreen() {
        if (screenSessionActive) {
            device.sendPacket(NetworkPacket(PACKET_TYPE_SCREEN_STOP))
        }
        context.stopService(Intent(context, PhoneScreenCaptureService::class.java))
        phoneCaptureRequested = false
        screenSessionActive = false
    }

    /**
     * A desk asks to see this phone's screen. [capturing] is whether the capture really runs (the transport's word, from the
     * coordinator): a flag of this plugin's own used to say "already sharing" after the share had been stopped by the phone's
     * own Desk screen or a reconnect, and every later ask from the desk was ignored with nothing shown (owner, 11-14).
     */
    fun askForCapture(offered: Boolean, capturing: Boolean) {
        if (capturing) return
        phoneCaptureRequested = false
        screenSessionActive = true
        promptForCapture(offered)
    }

    /** The share ended, however (the phone's own stop, End, a reconnect): the next ask from a desk prompts again. */
    fun onPhoneCaptureEnded() {
        phoneCaptureRequested = false
        promptedAt = null
    }

    /**
     * Android's consent prompt, opened directly when Android lets this app start it (it is in front), and always a heads-up
     * notification with Allow and Not now: an activity started from the background is blocked on Android 10+ and ColorOS
     * without a word. One per desk, posted again (alerting again) on each ask, gone when answered or after
     * [PROMPT_TIMEOUT_MS] (the desk's own wait).
     */
    private fun promptForCapture(offered: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        // Not twice within the window: a second system prompt would stack on the first.
        if (promptedAt?.let { now - it < PROMPT_PENDING_MS } != true) {
            promptedAt = now
            try {
                context.startActivity(projectionIntent(context, device.deviceId))
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Could not open the screen-sharing prompt directly", exception)
            }
        }
        // Offered from this phone's own Share screen: the person is in the app, no notification.
        if (offered) return
        Log.i(TAG, "The desk asks to see this screen: prompt posted")
        @android.annotation.SuppressLint("MissingPermission")
        fun post() = androidx.core.app.NotificationManagerCompat.from(context)
            .notify(askTag(device.deviceId), PROMPT_NOTIFICATION_ID, askNotice(context, device.deviceId, device.name))
        runCatching { post() }.onFailure { Log.w(TAG, "The screen-sharing prompt could not be shown (${it.javaClass.simpleName})") }
    }

    fun onPhoneCapturePermissionFinished(granted: Boolean) {
        promptedAt = null
        phoneCaptureRequested = granted
        screenSessionActive = granted
        if (!granted) {
            latestStatus = context.getString(R.string.remote_screen_error)
        }
    }

    /**
     * MediaProjection can be revoked by lock screen, system privacy controls,
     * or process lifecycle without unpairing the DeskLink device. Reset only
     * the capture state so the user can explicitly request fresh Android
     * consent after unlock; do not leave the plugin stuck as "requested".
     */
    fun onPhoneCaptureStopped(reason: String) {
        phoneCaptureRequested = false
        screenSessionActive = false
        latestStatus = reason.ifBlank { context.getString(R.string.remote_screen_error) }
    }

    /** Not now on a desk's ask. Not exported: no other app may answer for the person. */
    class AskReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            val deviceId = intent.getStringExtra(EXTRA_DEVICE) ?: return
            if (intent.action == ACTION_NOT_NOW) declined(context, deviceId)
        }
    }

    override val supportedPacketTypes: Array<String> = SCREEN_PACKET_TYPES
    override val outgoingPacketTypes: Array<String> = SCREEN_PACKET_TYPES

    companion object {
        private const val TAG = "DeskLink/ScreenControl"
        const val PACKET_TYPE_SCREEN_REQUEST = DeskLinkProtocol.PACKET_TYPE_SCREEN_REQUEST
        const val PACKET_TYPE_SCREEN_READY = DeskLinkProtocol.PACKET_TYPE_SCREEN_READY
        const val PACKET_TYPE_SCREEN_STOP = DeskLinkProtocol.PACKET_TYPE_SCREEN_STOP
        const val PACKET_TYPE_SCREEN_ERROR = DeskLinkProtocol.PACKET_TYPE_SCREEN_ERROR

        const val ROLE_PHONE_SCREEN = "phone-screen"

        /** Local-only flag on the synthesised request: the share was offered from this phone. */
        const val OFFERED_FIELD = "offeredHere"
        const val REASON_BUSY = "busy"
        const val PROMPT_NOTIFICATION_ID = 1702
        private const val PROMPT_PENDING_MS = 30_000L

        /** The prompt goes after this long; the desk stops waiting then too (`remote_control::ASK_WAIT`). */
        const val PROMPT_TIMEOUT_MS = 60_000L

        /** `reason` of the `screen-error` sent for "Not now" (the desk says "Not allowed on <phone>"). */
        const val REASON_DECLINED = "declined"

        const val ACTION_NOT_NOW = "org.desklink.mobile.screen.NOT_NOW"
        const val EXTRA_DEVICE = "deviceId"

        @JvmStatic
        fun askTag(deviceId: String) = "screen-ask-$deviceId"

        @JvmStatic
        fun cancelAsk(context: android.content.Context, deviceId: String) =
            androidx.core.app.NotificationManagerCompat.from(context).cancel(askTag(deviceId), PROMPT_NOTIFICATION_ID)

        @JvmStatic
        fun projectionIntent(context: android.content.Context, deviceId: String): Intent =
            Intent(context, ScreenProjectionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ScreenProjectionActivity.EXTRA_DEVICE_ID, deviceId)

        /** "<desk> wants to see your screen" with Allow (Android's own consent prompt next) and Not now. */
        @JvmStatic
        fun askNotice(context: android.content.Context, deviceId: String, deskName: String): android.app.Notification {
            val code = askTag(deviceId).hashCode()
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            val allow = android.app.PendingIntent.getActivity(context, code, projectionIntent(context, deviceId), flags)
            val notNow = android.app.PendingIntent.getBroadcast(
                context, code + 1,
                Intent(context, AskReceiver::class.java).setAction(ACTION_NOT_NOW).putExtra(EXTRA_DEVICE, deviceId),
                flags,
            )
            val text = context.getString(R.string.screen_share_prompt_text)
            return androidx.core.app.NotificationCompat.Builder(context, org.desklink.mobile.helpers.NotificationHelper.Channels.REQUESTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.screen_share_prompt_title, deskName))
                .setContentText(text)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                // Heads-up: the channel is high importance; this is the same for Android 7.
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(allow)
                .addAction(
                    androidx.core.app.NotificationCompat.Action.Builder(0, context.getString(R.string.screen_share_prompt_allow), allow)
                        // Never from the lock screen without unlocking: the desk would see this phone.
                        .setAuthenticationRequired(true)
                        .build(),
                )
                .addAction(0, context.getString(R.string.screen_share_prompt_not_now), notNow)
                .setAutoCancel(true)
                .setTimeoutAfter(PROMPT_TIMEOUT_MS)
                .build()
        }

        /** "Not now": the prompt goes and the desk hears it was declined (it stops waiting and says so). */
        @JvmStatic
        fun declined(context: android.content.Context, deviceId: String) {
            cancelAsk(context, deviceId)
            val app = org.desklink.mobile.DeskLinkApplication.getInstance()
            app.getDevicePlugin(deviceId, ScreenControlPlugin::class.java)?.onPhoneCaptureEnded()
            org.desklink.mobile.helpers.ThreadHelper.execute {
                runCatching { app.reportWebRtcScreenCaptureFailure(deviceId, REASON_DECLINED) }
            }
        }
        const val ROLE_DESKTOP_SCREEN = "desktop-screen"
        const val DEFAULT_MAX_DIMENSION = 1280
        const val DEFAULT_FPS = 6
        const val DEFAULT_QUALITY = 60
        var latestFrameWidth: Int by mutableStateOf(1280)
            private set

        var latestFrameHeight: Int by mutableStateOf(720)
            private set

        var latestStatus: String by mutableStateOf("")
            private set

        private val remoteVideoLock = Any()
        private var remoteVideoTrack: VideoTrack? = null
        private val remoteVideoSinks = ConcurrentHashMap<VideoSink, VideoSink>()

        private class SizeTrackingVideoSink(
            private val downstream: VideoSink,
            private val onFrameArrived: () -> Unit,
        ) : VideoSink {
            override fun onFrame(frame: VideoFrame) {
                latestFrameWidth = frame.rotatedWidth
                latestFrameHeight = frame.rotatedHeight
                onFrameArrived()
                downstream.onFrame(frame)
            }
        }

        val SCREEN_PACKET_TYPES = arrayOf(
            PACKET_TYPE_SCREEN_REQUEST,
            PACKET_TYPE_SCREEN_READY,
            PACKET_TYPE_SCREEN_STOP,
            PACKET_TYPE_SCREEN_ERROR
        )

        fun createScreenRequestPacket(
            role: String,
            maxDimension: Int,
            fps: Int,
            quality: Int
        ): NetworkPacket {
            return NetworkPacket(PACKET_TYPE_SCREEN_REQUEST).apply {
                this["role"] = role
                this["maxDimension"] = maxDimension
                this["fps"] = fps
                this["quality"] = quality
            }
        }
    }
}

/** One text-focus report from the desktop; [serial] tells repeated reports apart. */
data class DesktopTextFocus(val editable: Boolean = false, val serial: Int = 0) {
    fun next(editable: Boolean) = DesktopTextFocus(editable, serial + 1)
}
