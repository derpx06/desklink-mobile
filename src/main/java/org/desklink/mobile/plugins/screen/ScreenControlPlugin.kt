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
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap

@LoadablePlugin
class ScreenControlPlugin : Plugin() {
    @Volatile
    private var phoneCaptureRequested = false
    override val displayName: String
        get() = context.getString(R.string.pref_plugin_screen_control)

    override val description: String
        get() = context.getString(R.string.pref_plugin_screen_control_desc)

    override fun onDestroy() {
        context.stopService(Intent(context, PhoneScreenCaptureService::class.java))
        phoneCaptureRequested = false
        clearRemoteVideoTrack()
        latestStatus = ""
        super.onDestroy()
    }

    /** Installs the current authenticated desktop video track for the screen UI. */
    fun onRemoteVideoTrack(track: VideoTrack) {
        synchronized(remoteVideoLock) {
            remoteVideoTrack?.let { oldTrack ->
                remoteVideoSinks.values.forEach(oldTrack::removeSink)
            }
            remoteVideoTrack = track
            remoteVideoSinks.values.forEach(track::addSink)
        }
        latestStatus = context.getString(R.string.remote_screen_connected)
    }

    /** Attaches a native WebRTC renderer to the current remote video track. */
    fun attachRemoteVideoSink(sink: VideoSink) {
        synchronized(remoteVideoLock) {
            val forwardingSink = SizeTrackingVideoSink(sink)
            remoteVideoSinks[sink] = forwardingSink
            remoteVideoTrack?.addSink(forwardingSink)
        }
    }

    /** Detaches a renderer and stops forwarding frames to it. */
    fun detachRemoteVideoSink(sink: VideoSink) {
        synchronized(remoteVideoLock) {
            remoteVideoSinks.remove(sink)?.let { forwardingSink ->
                remoteVideoTrack?.removeSink(forwardingSink)
            }
        }
    }

    fun clearRemoteVideoTrack() {
        synchronized(remoteVideoLock) {
            remoteVideoTrack?.let { track ->
                remoteVideoSinks.values.forEach(track::removeSink)
            }
            remoteVideoTrack = null
        }
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        when (np.type) {
            PACKET_TYPE_SCREEN_REQUEST -> {
                if (np.getString("role", "") == ROLE_PHONE_SCREEN && !phoneCaptureRequested) {
                    phoneCaptureRequested = true
                    val intent = Intent(context, ScreenProjectionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(ScreenProjectionActivity.EXTRA_DEVICE_ID, device.deviceId)
                    try {
                        context.startActivity(intent)
                    } catch (exception: RuntimeException) {
                        phoneCaptureRequested = false
                        latestStatus = context.getString(R.string.remote_screen_error)
                        Log.e(TAG, "Unable to request phone screen permission", exception)
                    }
                }
            }
            PACKET_TYPE_SCREEN_READY -> {
                latestStatus = context.getString(R.string.remote_screen_connected)
            }
            PACKET_TYPE_SCREEN_ERROR -> {
                latestStatus = np.getString("message", context.getString(R.string.remote_screen_error))
            }
            PACKET_TYPE_SCREEN_STOP -> {
                context.stopService(Intent(context, PhoneScreenCaptureService::class.java))
                phoneCaptureRequested = false
                clearRemoteVideoTrack()
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
        clearRemoteVideoTrack()
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
        device.sendPacket(NetworkPacket(PACKET_TYPE_SCREEN_STOP))
        context.stopService(Intent(context, PhoneScreenCaptureService::class.java))
        phoneCaptureRequested = false
        clearRemoteVideoTrack()
    }

    fun onPhoneCapturePermissionFinished(granted: Boolean) {
        phoneCaptureRequested = granted
        if (!granted) {
            latestStatus = context.getString(R.string.remote_screen_error)
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

        private class SizeTrackingVideoSink(private val downstream: VideoSink) : VideoSink {
            override fun onFrame(frame: VideoFrame) {
                latestFrameWidth = frame.rotatedWidth
                latestFrameHeight = frame.rotatedHeight
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
