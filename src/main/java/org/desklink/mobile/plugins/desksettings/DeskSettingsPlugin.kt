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
    /** W10: whether the computer lets this phone keep it awake (its own per-phone switch, off by default); null from a desk that does not say. */
    val keepAwakeAllowed: Boolean? = null,
    /** W14: the computer's "Share Do Not Disturb with <phone>" switch; null from a desk that does not say. */
    val dndSync: Boolean? = null,
    /** W14: the computer's Do Not Disturb, sent only while [dndSync] is true. */
    val dnd: Boolean? = null,
    /** W12: the computer's "Accept photos and screenshots from <phone>" switch; null from a desk that does not say. */
    val galleryAccepted: Boolean? = null,
    /** asks.json: the desk's toDesk features (key -> on); null from a desk that cannot be asked (no "Ask on computer" then). */
    val features: Map<String, Boolean>? = null,
    /** asks.json prompts: false when the desk's notifications cannot show an ask (it waits in its window instead). */
    val prompts: Boolean = true,
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

    override fun onFeatureReady() {
        markStale()
        // This phone's state, and the asks that could not go out before (each once).
        sendReport(force = true)
        book.toRetry().forEach { sendAsk(it) }
    }

    /** Where this phone's "Ask on computer" asks stand (asks.json states). */
    val book = AskBook()

    /** Bumped on every change to [book]: the screens read it to recompose. */
    var asksChanged by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** Where the ask for [key] stands; null when there is none (or the desk reports it on). */
    fun askState(key: String): AskBook.State? = book.state(key, promptsOff = settings?.prompts == false)

    /** Whether this desk can be asked for [key] (a desk that reports its asks.json features) and it is off there. */
    fun askable(key: String): Boolean = settings?.features?.get(key) == false

    /**
     * "Ask on computer" (or Ask again): `desklink.settings.request {set: {<key>: true}}`, a request the desk shows its person; it
     * never turns anything on by itself. One pending per key; [manual] false (nobody pressed) at most every [Asks.REASK_MS].
     */
    fun askDesk(key: String, manual: Boolean = true): Boolean {
        if (key !in Asks.TO_DESK || key in SETTABLE || !book.mayAsk(key, manual)) return false
        if (!device.hasActiveWebRtcFeatureTransport) {
            // Not lost: said ("Couldn't reach"), and sent once the link is ready.
            book.asked(key, delivered = false)
        } else {
            book.asked(key, delivered = true)
            sendAsk(key)
        }
        asksChanged++
        return true
    }

    private fun sendAsk(key: String) {
        val packet = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST).apply { this["set"] = JSONObject().put(key, true) }
        device.sendPacket(packet, object : org.desklink.mobile.UserActionCallback() {
            override fun onSuccess() = Unit
            override fun onFailure(e: Throwable) {
                book.failed(key)
                asksChanged++
            }
        })
    }

    /** The last report sent, so a resume that changed nothing sends nothing. */
    private var lastReport: String? = null

    /** This phone's state for the desk (asks.json): sent when it changed, or always with [force] or a [declined] answer. */
    fun sendReport(declined: String? = null, force: Boolean = false) {
        val features = Asks.features(context, device)
        val prompts = Asks.promptsOn(context)
        val packet = Asks.reportPacket(features, prompts, declined)
        val text = "$features $prompts"
        if (declined == null && !force && text == lastReport) return
        lastReport = text
        device.sendPacket(packet)
    }

    /** The desk asks this phone to turn something on: a prompt for the person (never a switch flipped), then the state. */
    private fun onAsked(np: NetworkPacket) {
        val asked = Asks.askedKeys(runCatching { np.getJSONObject("set") }.getOrNull())
        for (feature in asked) {
            Asks.onAsked(context, device.deviceId, device.name, feature, Asks.isOn(context, device, feature))
        }
        if (asked.isNotEmpty()) sendReport(force = true)
    }

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_desk_settings)

    override val description: String
        get() = context.getString(R.string.pref_plugin_desk_settings_desc)

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type == DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST) {
            onAsked(np)
            return true
        }
        if (np.type != DeskLinkProtocol.PACKET_TYPE_SETTINGS) return false
        parse(np)?.let {
            settings = it
            pending = emptyMap()
            book.onReport(it.features.orEmpty(), np.getStringList("declined", null).orEmpty())
            asksChanged++
            device.getPlugin(org.desklink.mobile.plugins.battery.BatteryPlugin::class.java)?.deskDndChanged(it.dndSync == true, it.dnd)
            org.desklink.mobile.plugins.gallery.GalleryWatcher.kick(context)
            org.desklink.mobile.ui.surfaces.DeskSurfaces.refresh(context)
        }
        return true
    }

    /** Asks the desk to change [key]; the screen follows the desk's reply, not the request. */
    fun set(key: String, value: Boolean) {
        val packet = requestPacket(key, value) ?: return
        pending = pending + (key to value)
        org.desklink.mobile.ui.surfaces.DeskSurfaces.refresh(context)
        device.sendPacket(packet, object : org.desklink.mobile.UserActionCallback() {
            override fun onSuccess() = Unit
            override fun onFailure(e: Throwable) {
                pending = pending - key
            }
        })
    }

    override fun onDestroy() {
        Asks.Feature.entries.forEach { Asks.forget(context, device.deviceId, it.key) }
    }

    /** The desk's report, and its asks (asks.json: a prompt here, never a switch flipped). */
    override val supportedPacketTypes: Array<String> = arrayOf(DeskLinkProtocol.PACKET_TYPE_SETTINGS, DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST)

    /** Changing a desk setting or asking the desk for one, and this phone's own report (asks.json features). */
    override val outgoingPacketTypes: Array<String> = arrayOf(DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST, DeskLinkProtocol.PACKET_TYPE_SETTINGS)

    companion object {
        const val KEEP_AWAKE = "keepAwake"
        const val CLIPBOARD = "clipboard"
        const val NOTIFICATIONS = "notifications"
        const val RELAY = "relay"
        const val COMMAND_ALERTS = "commandAlerts"

        /**
         * What the phone may change; `unlockNearby` is the desk's alone, and `relay` is no one's: the relay follows the
         * RELAY_ENABLED constant in each app (the desk still reports it, derived, for older builds).
         */
        val SETTABLE = setOf(KEEP_AWAKE, CLIPBOARD, NOTIFICATIONS, COMMAND_ALERTS)

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
                keepAwakeAllowed = if (np.has("keepAwakeAllowed")) np.getBoolean("keepAwakeAllowed") else null,
                galleryAccepted = if (np.has("galleryAccepted")) np.getBoolean("galleryAccepted") else null,
                dndSync = if (np.has("dndSync")) np.getBoolean("dndSync") else null,
                dnd = if (np.has("dndSync") && np.getBoolean("dndSync") && np.has("dnd")) np.getBoolean("dnd") else null,
                features = np.getJSONObject("features")?.let { f -> Asks.TO_DESK.filter { f.opt(it) is Boolean }.associateWith { f.getBoolean(it) } },
                prompts = np.getString("prompts", "on") != "off",
            )
        }

        /** The phone may ask the desk to stay awake only when the desk says it is allowed (false and absent both mean no). */
        fun keepAwakeUsable(state: DeskSettingsState?): Boolean = state?.keepAwakeAllowed == true

        /** What a switch shows: the asked value while pending, else the desk's. */
        fun shown(key: String, reported: Boolean, pending: Map<String, Boolean>): Boolean = pending[key] ?: reported

        /** `{set: {key: value}}`, or null for a key the desk would ignore. */
        fun requestPacket(key: String, value: Boolean): NetworkPacket? {
            if (key !in SETTABLE) return null
            return NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST).apply {
                this["set"] = JSONObject().put(key, value)
            }
        }
    }
}
