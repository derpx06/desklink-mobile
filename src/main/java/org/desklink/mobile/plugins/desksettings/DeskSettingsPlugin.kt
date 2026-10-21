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

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_desk_settings)

    override val description: String
        get() = context.getString(R.string.pref_plugin_desk_settings_desc)

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != DeskLinkProtocol.PACKET_TYPE_SETTINGS) return false
        parse(np)?.let { settings = it }
        return true
    }

    /** Asks the desk to change [key]; the screen follows the desk's reply, not the request. */
    fun set(key: String, value: Boolean) {
        requestPacket(key, value, settings?.relayConfigured == true)?.let(device::sendPacket)
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
            )
        }

        /** `{set: {key: value}}`, or null for a key the desk would ignore. */
        fun requestPacket(key: String, value: Boolean, relayConfigured: Boolean): NetworkPacket? {
            if (key !in SETTABLE || (key == RELAY && !relayConfigured)) return null
            return NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST).apply {
                this["set"] = JSONObject().put(key, value)
            }
        }
    }
}
