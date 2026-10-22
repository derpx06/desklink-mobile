/*
 * SPDX-FileCopyrightText: 2014 Ahmed I. Khalil <ahmedibrahimkhali@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.mousepad

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.R
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

@LoadablePlugin
class MousePadPlugin : Plugin() {
    var isKeyboardEnabled: Boolean = true
        private set

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        this.isKeyboardEnabled = np.getBoolean("state", true)
        return true
    }

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_mousepad)

    override fun getUiButtons(): List<PluginUiButton> {
        val mouseAndKeyboardInput = PluginUiButton(
            context.getString(R.string.open_mousepad),
            R.drawable.touchpad_plugin_action_24dp
        ) { parentActivity ->
            val intent = Intent(parentActivity, MousePadActivity::class.java)
            intent.putExtra("deviceId", device.deviceId)
            parentActivity.startActivity(intent)
        }
        return listOf(mouseAndKeyboardInput)
    }

    override val description: String
        get() = context.getString(R.string.pref_plugin_mousepad_desc_nontv)

    /** Replaced by tests; the app sends motion on the next vsync. */
    internal var frameScheduler: (Runnable) -> Unit = ::onNextFrame

    /** A drag produced one packet per touch event; motion now leaves at most
     * once per display frame, and everything else flushes it first. */
    private val motion = PointerMotionCoalescer({ frameScheduler(it) }, { device.sendPacket(it) })

    fun sendMouseDelta(dx: Float, dy: Float) = motion.addDelta(dx.toDouble(), dy.toDouble())

    fun sendMousePosition(x: Int, y: Int) = motion.moveTo(x, y)

    /**
     * Screen-mode taps must carry their absolute position with the click. The
     * packet bridge then selects the reliable input channel, so a click cannot
     * overtake a replaceable pointer-motion packet on the realtime channel.
     */
    fun sendScreenTap(x: Int, y: Int) {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["x"] = x
        np["y"] = y
        np["singleclick"] = true
        sendPacket(np)
    }

    /** Starts a phone-style drag at the exact position displayed to the user. */
    fun sendScreenHold(x: Int, y: Int) {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["x"] = x
        np["y"] = y
        np["singlehold"] = true
        sendPacket(np)
    }

    /** A double-click at the tapped position. Without the position it lands
     * wherever the pointer last was, which is not where the user tapped. */
    fun sendScreenDoubleClick(x: Int, y: Int) {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["x"] = x
        np["y"] = y
        np["doubleclick"] = true
        sendPacket(np)
    }

    fun hasMicPermission(): Boolean {
        return isPermissionGranted(Manifest.permission.RECORD_AUDIO)
    }

    fun sendLeftClick() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["singleclick"] = true
        sendPacket(np)
    }

    fun sendDoubleClick() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["doubleclick"] = true
        sendPacket(np)
    }

    fun sendMiddleClick() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["middleclick"] = true
        sendPacket(np)
    }

    fun sendRightClick() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["rightclick"] = true
        sendPacket(np)
    }

    fun sendSingleHold() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["singlehold"] = true
        sendPacket(np)
    }

    fun sendSingleRelease() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["singlerelease"] = true
        sendPacket(np)
    }

    fun sendScroll(dx: Double, dy: Double) {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["scroll"] = true
        np["dx"] = dx
        np["dy"] = dy
        sendPacket(np)
    }

    fun sendText(content: String) {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["key"] = content
        sendPacket(np)
    }

    /** Any non-motion packet: pending pointer motion goes out ahead of it. */
    fun sendPacket(np: NetworkPacket) = motion.sendAfterMotion(np)

    private fun onNextFrame(task: Runnable) {
        val post = Runnable { Choreographer.getInstance().postFrameCallback { task.run() } }
        if (Looper.myLooper() == Looper.getMainLooper()) post.run() else Handler(Looper.getMainLooper()).post(post)
    }

    override val supportedPacketTypes = arrayOf(PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE)
    override val outgoingPacketTypes = arrayOf(PACKET_TYPE_MOUSEPAD_REQUEST)

    companion object {
        const val PACKET_TYPE_MOUSEPAD_REQUEST: String = DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST
        private const val PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE = DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE
    }
}
