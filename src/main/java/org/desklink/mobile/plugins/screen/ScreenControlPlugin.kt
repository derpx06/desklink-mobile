/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin

@LoadablePlugin
class ScreenControlPlugin : Plugin() {
    override val displayName: String
        get() = context.getString(R.string.pref_plugin_screen_control)

    override val description: String
        get() = context.getString(R.string.pref_plugin_screen_control_desc)

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        when (np.type) {
            PACKET_TYPE_SCREEN_READY -> {
                latestStatus = context.getString(R.string.remote_screen_connected)
            }
            PACKET_TYPE_SCREEN_ERROR -> {
                latestStatus = np.getString("message", context.getString(R.string.remote_screen_error))
            }
            PACKET_TYPE_SCREEN_FRAME -> {
                val payload = np.payload
                if (payload?.inputStream == null) {
                    latestStatus = context.getString(R.string.remote_screen_error)
                    return true
                }

                try {
                    val encoded = payload.inputStream.readBytes()
                    val frame = ScreenFrameCodec.decode(encoded)
                    val bitmap = BitmapFactory.decodeByteArray(frame.payload, 0, frame.payload.size)
                    if (bitmap != null) {
                        latestFrameWidth = frame.header.width
                        latestFrameHeight = frame.header.height
                        latestFrame = bitmap
                        latestStatus = context.getString(R.string.remote_screen_connected)
                    } else {
                        latestStatus = context.getString(R.string.remote_screen_error)
                    }
                } catch (exception: Exception) {
                    Log.e(TAG, "Failed to decode screen frame", exception)
                    latestStatus = context.getString(R.string.remote_screen_error)
                } finally {
                    payload.close()
                }
            }
        }
        return np.type in SCREEN_PACKET_TYPES
    }

    fun requestDesktopScreen(
        maxDimension: Int = DEFAULT_MAX_DIMENSION,
        fps: Int = DEFAULT_FPS,
        quality: Int = DEFAULT_QUALITY
    ) {
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
    }

    override val supportedPacketTypes: Array<String> = SCREEN_PACKET_TYPES
    override val outgoingPacketTypes: Array<String> = SCREEN_PACKET_TYPES

    companion object {
        private const val TAG = "DeskLink/ScreenControl"
        const val PACKET_TYPE_SCREEN_REQUEST = "desklink.screen.request"
        const val PACKET_TYPE_SCREEN_READY = "desklink.screen.ready"
        const val PACKET_TYPE_SCREEN_FRAME = "desklink.screen.frame"
        const val PACKET_TYPE_SCREEN_STOP = "desklink.screen.stop"
        const val PACKET_TYPE_SCREEN_ERROR = "desklink.screen.error"

        const val ROLE_PHONE_SCREEN = "phone-screen"
        const val ROLE_DESKTOP_SCREEN = "desktop-screen"
        const val DEFAULT_MAX_DIMENSION = 1280
        const val DEFAULT_FPS = 6
        const val DEFAULT_QUALITY = 60

        var latestFrame: Bitmap? by mutableStateOf(null)
            private set

        var latestFrameWidth: Int by mutableStateOf(1280)
            private set

        var latestFrameHeight: Int by mutableStateOf(720)
            private set

        var latestStatus: String by mutableStateOf("")
            private set

        val SCREEN_PACKET_TYPES = arrayOf(
            PACKET_TYPE_SCREEN_REQUEST,
            PACKET_TYPE_SCREEN_READY,
            PACKET_TYPE_SCREEN_FRAME,
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
