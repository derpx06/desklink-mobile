/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.surfaces.widgets

import android.content.Context
import androidx.core.content.edit
import org.desklink.mobile.BuildConfig
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.Device
import org.desklink.mobile.DeviceInfo
import org.desklink.mobile.helpers.PerPeerState
import org.desklink.mobile.plugins.clipboard.ClipboardPlugin
import org.desklink.mobile.plugins.findremotedevice.FindRemoteDevicePlugin
import org.desklink.mobile.plugins.lockdevice.LockDevicePlugin
import org.desklink.mobile.plugins.screen.ScreenControlPlugin
import org.desklink.mobile.plugins.share.SharePlugin
import org.desklink.mobile.ui.compose.extensions.device.toUiModel
import org.desklink.mobile.ui.surfaces.DeskSurfaces
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads [ComputerFacts] from the live objects, the same way Home does. Nothing here is cached
 * between redraws except the last round-trip time and the last answer on terminal access.
 */
object WidgetFacts {
    private val rtt = ConcurrentHashMap<String, Int>()

    /** The computer the app shows (the one picked in Home's switcher), or null when none is paired. */
    fun current(context: Context): ComputerFacts? = DeskSurfaces.currentDesk(context)?.let { of(context, it) }

    fun of(context: Context, device: Device): ComputerFacts {
        val id = device.deviceId
        val ui = device.toUiModel()
        val ready = device.hasActiveWebRtcFeatureTransport
        if (ready) requestLatency(context, device) else rtt.remove(id)
        return ComputerFacts(
            id = id,
            name = device.name,
            connection = ui.connection,
            path = device.connectionPath,
            ready = ready,
            updateNeeded = !ready && DeviceInfo.versionsSkew(BuildConfig.VERSION_NAME, device.deviceInfo.appVersion),
            batteryPercent = ui.batteryPercent.takeIf { ready },
            charging = ui.charging,
            rttMs = rtt[id].takeIf { ready },
            screen = device.getPlugin(ScreenControlPlugin::class.java) != null,
            files = device.getPlugin(SharePlugin::class.java) != null,
            clipboard = device.getPlugin(ClipboardPlugin::class.java) != null,
            ring = device.getPlugin(FindRemoteDevicePlugin::class.java) != null,
            lock = device.getPlugin(LockDevicePlugin::class.java) != null,
            terminals = terminalAccess(context, id),
            terminalCount = DeskLinkApplication.getInstance().terminalClient(id)?.state?.value?.sessions?.count { !it.gone } ?: 0,
            lastSeen = context.getSharedPreferences(PerPeerState.SURFACES, Context.MODE_PRIVATE).getLong(PerPeerState.seenKey(id), 0).takeIf { it > 0 },
        )
    }

    /** What the computer last said about terminal access; the answer is kept so a restart does not forget "off". */
    private fun terminalAccess(context: Context, id: String): TerminalAccess {
        val prefs = context.getSharedPreferences(PerPeerState.SURFACES, Context.MODE_PRIVATE)
        val state = DeskLinkApplication.getInstance().terminalClient(id)?.state?.value
        if (state != null && state.listLoaded) {
            val allowed = !state.notAllowed
            if (prefs.getBoolean(PerPeerState.terminalAccessKey(id), true) != allowed || !prefs.contains(PerPeerState.terminalAccessKey(id))) {
                prefs.edit { putBoolean(PerPeerState.terminalAccessKey(id), allowed) }
            }
            return if (allowed) TerminalAccess.ALLOWED else TerminalAccess.OFF
        }
        return if (prefs.contains(PerPeerState.terminalAccessKey(id))) {
            if (prefs.getBoolean(PerPeerState.terminalAccessKey(id), true)) TerminalAccess.ALLOWED else TerminalAccess.OFF
        } else {
            TerminalAccess.UNKNOWN
        }
    }

    /**
     * One round-trip measurement per draw; the first value for a computer asks for another draw, later ones wait for the
     * next natural change (a widget never polls).
     */
    private fun requestLatency(context: Context, device: Device) {
        val app = context.applicationContext
        device.requestConnectionStats { stats ->
            val ms = stats?.roundTripTimeMs?.toInt() ?: return@requestConnectionStats
            if (rtt.put(device.deviceId, ms) == null) DeskSurfaces.refresh(app)
        }
    }
}
