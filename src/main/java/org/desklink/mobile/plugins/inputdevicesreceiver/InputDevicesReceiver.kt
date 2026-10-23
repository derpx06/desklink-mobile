/*
 * SPDX-FileCopyrightText: 2026 Youssef Al-Bor3y <youssefelbor3y@protonmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.inputdevicesreceiver

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.helpers.RateLimiter
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.plugins.mousereceiver.MouseReceiverService
import org.desklink.mobile.plugins.screen.SessionLiveNotification
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

/**
 * F4 glide, KDE Connect's `shareinputdevices` edge protocol: the desk's pointer enters across an
 * edge (G1), its mousepad packets move the accessibility cursor (G2), and reaching the same edge
 * hands it back (G3). Only while "Let <desk> control this phone" is on ([Glide.allowed]).
 */
@LoadablePlugin
class InputDevicesReceiverPlugin : Plugin() {
    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_inputdevicesreceiver)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_inputdevicesreceiver_desc)

    /** Where MouseReceiverService last put the cursor (it sets these as it moves). */
    object Cursor {
        var x = 0
        var y = 0
    }

    private val metrics = DisplayMetrics()
    private lateinit var displayManager: DisplayManager
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) { resolveDisplaySize() }
    }
    private val enters = RateLimiter(Glide.MAX_ENTERS_PER_SECOND, 1000, TAG)

    override fun onCreate(): Boolean {
        // A rotation while the pointer is here changes the edges.
        displayManager = context.getSystemService(DisplayManager::class.java)
        resolveDisplaySize()
        displayManager.registerDisplayListener(displayListener, null)
        return true
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        if (Glide.activeDesk == device.deviceId) stop(release = false)
    }

    /** Whether a glide could start now: the person's switch for this desk and the accessibility service that moves the pointer. */
    fun glideAllowed() = Glide.allowed(context, device.deviceId) && MouseReceiverService.instance != null

    /**
     * `desklink.shareinputdevices {allowed}`: a report of state, never a command, so the desk arms its edge only when a glide
     * could be taken. Sent on every feature-ready and whenever the switch or the service changes.
     */
    fun reportAllowed() {
        device.sendPacket(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES).apply { this["allowed"] = glideAllowed() })
    }

    override fun onFeatureReady() = reportAllowed()

    /** The link is gone: the desk's own link-loss rule gives it its pointer back; here the overlay must not stay. */
    override fun onFeatureLost() {
        if (Glide.activeDesk == device.deviceId) stop(release = false)
    }

    private fun resolveDisplaySize() {
        @Suppress("DEPRECATION")
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
    }

    private fun sendRelease(dx: Int, dy: Int) {
        device.sendPacket(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES).apply {
            this["releaseDeltax"] = dx
            this["releaseDeltay"] = dy
        })
    }

    /** The glide ends here: the pointer hides, and (unless the desk ended it) the desk gets it back. */
    fun stop(release: Boolean) {
        if (release) {
            val (dx, dy) = Glide.handBack(Glide.enterEdge, Cursor.x, Cursor.y, Glide.edgeLength, metrics.widthPixels, metrics.heightPixels)
            sendRelease(dx, dy)
        }
        letGoOfThePointer()
    }

    /** What every way of ending a glide has in common: no drag left held, no pointer on screen, no state. */
    private fun letGoOfThePointer() {
        // A drag the desk started with a held button must not outlive the glide: it would swallow the next tap.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching { MouseReceiverService.instance?.stopSwipe() }
        Glide.end()
        MouseReceiverService.instance?.hide(0)
        SessionLiveNotification.refresh(context, device.deviceId)
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        when (np.type) {
            DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES_REQUEST -> onEnter(np)
            DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST -> onMotion(np)
            else -> return false
        }
        return true
    }

    /** G1. */
    private fun onEnter(np: NetworkPacket) {
        if (!enters.allow(np.type)) return
        val edge = np.getInt("exitEdge", Glide.NONE_EDGE)
        if (edge == Glide.NONE_EDGE) {
            // G4: the desk took the pointer back.
            if (Glide.activeDesk == device.deviceId) stop(release = false)
            return
        }
        val deltax = np.getDouble("deltax", 0.0)
        val deltay = np.getDouble("deltay", 0.0)
        val length = np.getDouble("edgeLength", 0.0).takeIf { it > 0 && it <= Glide.MAX_DESK_PX }
        // Not allowed, no accessibility service, a dark screen (nobody to see the pointer), or a nonsense edge: hand it straight back (G5).
        if (!Glide.validEdge(edge) || !Glide.allowed(context, device.deviceId) || MouseReceiverService.instance == null || !screenIsOn()) {
            Log.i(TAG, "Glide refused (allowed=${Glide.allowed(context, device.deviceId)}, service=${MouseReceiverService.instance != null})")
            sendRelease(0, deltay.coerceIn(-Glide.MAX_DESK_PX, Glide.MAX_DESK_PX).toInt())
            // And the reason as state, so the desk stops arming the edge for nothing.
            reportAllowed()
            return
        }
        // Another desk was gliding: it gets its pointer back first.
        Glide.activeDesk?.takeIf { it != device.deviceId }?.let { other ->
            org.desklink.mobile.DeskLinkApplication.getInstance().getDevice(other)?.getPlugin(InputDevicesReceiverPlugin::class.java)?.stop(release = true)
        }
        resolveDisplaySize()
        Glide.begin(device.deviceId, edge, length)
        val (x, y) = Glide.entry(edge, deltax, deltay, length, metrics.widthPixels, metrics.heightPixels)
        MouseReceiverService.setPos(x, y)
        SessionLiveNotification.refresh(context, device.deviceId)
        // The accept (W6): the desk streams nothing until it hears this, and takes silence for a refusal after 300 ms. Sent
        // only for an enter acted on, and before any hand-back this glide can produce.
        sendAccept()
    }

    /** `desklink.shareinputdevices {allowed: true, entered: true}`: a state report that also answers the enter. */
    private fun sendAccept() {
        device.sendPacket(NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES).apply {
            this["allowed"] = true
            this["entered"] = true
        })
    }

    private fun screenIsOn() = context.getSystemService(android.os.PowerManager::class.java)?.isInteractive != false

    /** G2 motion: hand back once the pointer reaches the edge it came in by. */
    private fun onMotion(np: NetworkPacket) {
        if (!Glide.isGliding(device.deviceId)) return
        // The accessibility service was switched off or killed mid-glide: nothing can show or move the pointer, so
        // the desk gets it back now rather than typing into the void.
        if (MouseReceiverService.instance == null) return stop(release = true)
        Glide.lastPacketAt = SystemClock.elapsedRealtime()
        if (!np.has("dx") || np.getBoolean("scroll", false)) return
        Glide.release(Glide.enterEdge, Cursor.x, Cursor.y, Glide.edgeLength, metrics.widthPixels, metrics.heightPixels)?.let { (dx, dy) ->
            sendRelease(dx, dy)
            letGoOfThePointer()
        }
    }

    override val supportedPacketTypes: Array<String> = arrayOf(DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST, DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES_REQUEST)
    override val outgoingPacketTypes: Array<String> = arrayOf(DeskLinkProtocol.PACKET_TYPE_SHARE_INPUT_DEVICES)

    companion object {
        private const val TAG = "DeskLink/Glide"

        /**
         * Ends whatever glide is on, whichever desk: the screen went off, or the accessibility service went away.
         * With [release] the desk is told to take its pointer back (best effort: the link may be asleep).
         */
        /** The switch or the accessibility service changed: every desk that has this plugin hears the new state. */
        @JvmStatic
        fun reportAllowedToAll() {
            val app = org.desklink.mobile.DeskLinkApplication.getInstance()
            app.devices.values.forEach { d -> runCatching { d.getPlugin(InputDevicesReceiverPlugin::class.java)?.takeIf { d.isPaired }?.reportAllowed() } }
        }

        @JvmStatic
        fun endActive(release: Boolean) {
            val id = Glide.activeDesk ?: return
            val plugin = org.desklink.mobile.DeskLinkApplication.getInstance().getDevice(id)?.getPlugin(InputDevicesReceiverPlugin::class.java)
            if (plugin != null) runCatching { plugin.stop(release) } else Glide.end()
        }
    }
}
