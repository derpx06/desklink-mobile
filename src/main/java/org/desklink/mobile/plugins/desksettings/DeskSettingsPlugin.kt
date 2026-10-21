/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.desksettings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.json.JSONObject

/** The desk's own settings, as its `desklink.settings` reports them. Every key is always present. */
data class DeskSettingsState(
    val keepAwake: Boolean,
    val clipboard: Boolean,
    /** Whether the desk shows THIS phone's notifications. */
    val notifications: Boolean,
    val relayConfigured: Boolean,
    val relay: Boolean,
    val commandAlerts: Boolean,
    /** Set on the desk only; the phone may read it, never change it. */
    val unlockNearby: Boolean,
    /** Whether a session password is set at the desk (S4); null from a desk that does not say. */
    val sessionPasswordSet: Boolean? = null,
)

/**
 * The desk's settings, live: the desk reports them on feature-ready and on every change, and the
 * phone may change the allow-listed ones ([SETTABLE]) with `desklink.settings.request`. The desk
 * applies, logs and answers with a fresh report, so nothing is assumed here until it does.
 */
@LoadablePlugin
class DeskSettingsPlugin : Plugin() {
    var settings by mutableStateOf<DeskSettingsState?>(null)
        private set

    /**
     * Switches flipped here that the desk has not reported back yet: shown as asked, marked
     * pending. The next report (or a lost link) settles them.
     */
    var pending by mutableStateOf<Map<String, Boolean>>(emptyMap())
        private set

    /** The link dropped: the desk's report may no longer be true, and nothing pending will land. */
    fun markStale() {
        pending = emptyMap()
    }

    override fun onFeatureReady() = markStale()

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_desk_settings)

    override val description: String
        get() = context.getString(R.string.pref_plugin_desk_settings_desc)

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != DeskLinkProtocol.PACKET_TYPE_SETTINGS) return false
        parse(np)?.let {
            settings = it
            pending = emptyMap()
            org.desklink.mobile.ui.surfaces.DeskSurfaces.refresh(context)
        }
        return true
    }

    /** Asks the desk to change [key]; the screen follows the desk's reply, not the request. */
    fun set(key: String, value: Boolean) {
        val packet = requestPacket(key, value, settings?.relayConfigured == true) ?: return
        pending = pending + (key to value)
        org.desklink.mobile.ui.surfaces.DeskSurfaces.refresh(context)
        device.sendPacket(packet, object : org.desklink.mobile.UserActionCallback() {
            override fun onSuccess() = Unit
            override fun onFailure(e: Throwable) {
                pending = pending - key
            }
        })
    }

    override val supportedPacketTypes: Array<String> = arrayOf(DeskLinkProtocol.PACKET_TYPE_SETTINGS)
    override val outgoingPacketTypes: Array<String> = arrayOf(DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST)

    companion object {
        const val KEEP_AWAKE = "keepAwake"
        const val CLIPBOARD = "clipboard"
        const val NOTIFICATIONS = "notifications"
        const val RELAY = "relay"
        const val COMMAND_ALERTS = "commandAlerts"

        /** What the phone may change; `unlockNearby` is the desk's alone. */
        val SETTABLE = setOf(KEEP_AWAKE, CLIPBOARD, NOTIFICATIONS, RELAY, COMMAND_ALERTS)

        /** A report with any key missing is ignored: the desk always sends all of them. */
        fun parse(np: NetworkPacket): DeskSettingsState? {
            val keys = listOf(KEEP_AWAKE, CLIPBOARD, NOTIFICATIONS, "relayConfigured", RELAY, COMMAND_ALERTS, "unlockNearby")
            if (keys.any { !np.has(it) }) return null
            return DeskSettingsState(
                keepAwake = np.getBoolean(KEEP_AWAKE),
                clipboard = np.getBoolean(CLIPBOARD),
                notifications = np.getBoolean(NOTIFICATIONS),
                relayConfigured = np.getBoolean("relayConfigured"),
                relay = np.getBoolean(RELAY),
                commandAlerts = np.getBoolean(COMMAND_ALERTS),
                unlockNearby = np.getBoolean("unlockNearby"),
                sessionPasswordSet = if (np.has("sessionPasswordSet")) np.getBoolean("sessionPasswordSet") else null,
            )
        }

        /** What a switch shows: the asked value while pending, else the desk's. */
        fun shown(key: String, reported: Boolean, pending: Map<String, Boolean>): Boolean = pending[key] ?: reported

        /** `{set: {key: value}}`, or null for a key the desk would ignore. */
        fun requestPacket(key: String, value: Boolean, relayConfigured: Boolean): NetworkPacket? {
            if (key !in SETTABLE || (key == RELAY && !relayConfigured)) return null
            return NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST).apply {
                this["set"] = JSONObject().put(key, value)
            }
        }
    }
}
