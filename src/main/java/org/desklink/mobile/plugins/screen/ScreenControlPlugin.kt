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

    /** The desktop reports a text input has keyboard focus: the remote
     * screen raises the phone keyboard, and drops it when this clears. */
    var desktopTextFocused by mutableStateOf(false)

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
            remoteLive = true
            latestStatus = context.getString(R.string.remote_screen_connected)
        }
    }

    fun onRemoteScreenRequesting() {
        remoteControlEnabled = false
        resetView()
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
        latestStatus = context.getString(R.string.remote_control_enabled)
    }

    fun onRemoteControlDenied(reason: String?) {
        remoteControlEnabled = false
        latestStatus = reason?.takeIf(String::isNotBlank)?.let {
            context.getString(R.string.remote_control_denied_reason, it)
        } ?: context.getString(R.string.remote_control_denied)
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

    private fun resetView() {
        desktopTextFocused = false
        frameSeen.set(false)
        remoteLive = false
        remoteScreenWidth = 0
        remoteScreenHeight = 0
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        when (np.type) {
            PACKET_TYPE_SCREEN_REQUEST -> {
                if (np.getString("role", "") == ROLE_PHONE_SCREEN && shouldPromptForCapture()) {
                    promptedAt = android.os.SystemClock.elapsedRealtime()
                    screenSessionActive = true
                    promptForCapture(offered = np.getBoolean(OFFERED_FIELD, false))
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

    /** Not while capture runs, nor while a prompt from the last 30 s may
     * still be on screen. It used to be a latch: a prompt Android silently
     * blocked (background activity launch) left it set, and every later
     * request from the desktop was ignored with nothing shown. */
    private fun shouldPromptForCapture(): Boolean {
        if (phoneCaptureRequested) return false
        val since = promptedAt ?: return true
        return android.os.SystemClock.elapsedRealtime() - since > PROMPT_PENDING_MS
    }

    /** Android blocks activity starts from the background without telling
     * us, so the consent prompt is also offered as a notification: tapping it
     * is a user action, which Android always lets open the prompt. */
    private fun promptForCapture(offered: Boolean) {
        val intent = Intent(context, ScreenProjectionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ScreenProjectionActivity.EXTRA_DEVICE_ID, device.deviceId)
        try {
            context.startActivity(intent)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not open the screen-sharing prompt directly", exception)
        }
        // Offered from this phone's own Share screen: the person is in the app, no notification.
        if (offered) return
        val pending = android.app.PendingIntent.getActivity(
            context,
            device.deviceId.hashCode(),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = androidx.core.app.NotificationCompat.Builder(
            context,
            org.desklink.mobile.helpers.NotificationHelper.Channels.HIGHPRIORITY,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.screen_share_prompt_title, device.name))
            .setContentText(context.getString(R.string.screen_share_prompt_text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(PROMPT_PENDING_MS)
            .build()
        context.getSystemService(android.app.NotificationManager::class.java)
            ?.notify(PROMPT_NOTIFICATION_ID, notification)
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
