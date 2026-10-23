/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.desklink.mobile.plugins.clipboard

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.plugins.clipboard.ClipboardListener.ClipboardObserver
import org.desklink.mobile.R
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

@LoadablePlugin
class ClipboardPlugin : Plugin() {
    private val maxClipboardBytes = 1024 * 1024
    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_clipboard)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_clipboard_desc)

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        val content = np.getString("content")
        if (content.toByteArray(Charsets.UTF_8).size > maxClipboardBytes) {
            return false
        }
        when (np.type) {
            (PACKET_TYPE_CLIPBOARD) -> {
                ClipboardListener.instance(context).setText(content)
                return true
            }
            (PACKET_TYPE_CLIPBOARD_CONNECT) -> {
                val packetTime = np.getLong("timestamp")
                // If the packetTime is 0, it means the timestamp is unknown (so do nothing).
                if (packetTime == 0L || packetTime < ClipboardListener.instance(context).updateTimestamp) {
                    return false
                }
                if ("content" in np) { // change clipboard if content is in NetworkPacket
                    ClipboardListener.instance(context).setText(content)
                }
                return true
            }
            else -> throw UnsupportedOperationException("Unknown packet type: " + np.type)
        }
    }

    private val observer: ClipboardObserver = object : ClipboardObserver {
        override fun clipboardChanged(content: String) {
            return this@ClipboardPlugin.propagateClipboard(content)
        }

        override fun clipboardImage(png: ByteArray) {
            val name = device.name
            device.sendPacket(ClipboardImages.packet(png), object : Device.SendPacketStatusCallback() {
                override fun onSuccess() { Log.i(TAG, "clipboard: image sent to $name") }
                override fun onFailure(e: Throwable) { Log.w(TAG, "clipboard: image not sent to $name (${e.javaClass.simpleName})") }
            })
        }
    }

    @VisibleForTesting
    fun propagateClipboard(content: String) {
        if (!fitsWire(content)) {
            Log.w(TAG, "clipboard: ${content.length} chars is too large for one message, not sent")
            return
        }
        val np = NetworkPacket(PACKET_TYPE_CLIPBOARD)
        np["content"] = content
        val name = device.name
        device.sendPacket(np, object : Device.SendPacketStatusCallback() {
            override fun onSuccess() { Log.i(TAG, "clipboard: sent to $name") }
            override fun onFailure(e: Throwable) { Log.w(TAG, "clipboard: not sent to $name (${e.javaClass.simpleName})") }
        })
    }

    /** Home's "Send clipboard to <desk>": whatever is on the clipboard, text or image, sent now. */
    fun sendNow(): Boolean = ClipboardListener.instance(context).sync(force = true)

    private fun sendConnectPacket() {
        val content = ClipboardListener.instance(context).currentContent?.takeIf(::fitsWire) ?: return // Send clipboard only if it had been initialized
        val np = NetworkPacket(PACKET_TYPE_CLIPBOARD_CONNECT)
        val timestamp = ClipboardListener.instance(context).updateTimestamp
        np["timestamp"] = timestamp
        np["content"] = content
        device.sendPacket(np)
    }


    override fun onCreate(): Boolean {
        ClipboardListener.instance(context).registerObserver(observer)
        sendConnectPacket()
        return true
    }

    /** Every feature-ready, not only when the plugin loads (contract S2). */
    override fun onFeatureReady() = sendConnectPacket()

    override fun onDestroy() {
        ClipboardListener.instance(context).removeObserver(observer)
    }

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_CLIPBOARD, PACKET_TYPE_CLIPBOARD_CONNECT)

    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_CLIPBOARD, PACKET_TYPE_CLIPBOARD_CONNECT)

    companion object {
        /** Clipboard text shared with the desk, as escaped JSON: one packet must cross the data channel (wire size fix). */
        const val MAX_SHARED_TEXT_BYTES = org.desklink.mobile.webrtc.WebRtcEnvelope.FEATURE_PACKET_BUDGET - 2 * 1024

        /** Too large → not shared (contract "Wire size"). */
        fun fitsWire(content: String) = org.json.JSONObject.quote(content).toByteArray().size <= MAX_SHARED_TEXT_BYTES

        /**
         * Packet containing just clipboard contents, sent when a device updates its clipboard.
         * 
         * 
         * The body should look like so:
         * {
         * "content": "password"
         * }
         */
        private const val PACKET_TYPE_CLIPBOARD = DeskLinkProtocol.PACKET_TYPE_CLIPBOARD

        /**
         * Packet containing clipboard contents and a timestamp that the contents were last updated, sent
         * on first connection
         * 
         * 
         * The timestamp is milliseconds since epoch. It can be 0, which indicates that the clipboard
         * update time is currently unknown.
         * 
         * 
         * The body should look like so:
         * {
         * "timestamp": 542904563213,
         * "content": "password"
         * }
         */
        private const val PACKET_TYPE_CLIPBOARD_CONNECT = DeskLinkProtocol.PACKET_TYPE_CLIPBOARD_CONNECT

        private const val TAG = "DeskLink/Clipboard"

        /** Below Android 10, or with "Automatic sync (advanced)": no tap needed for the phone's copies. */
        fun canSyncAutomatically(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ClipboardListener.autoSyncActive(context)

    }
}
