/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.desksettings

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.desklink.mobile.Device
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ask on phone / Ask on computer (shared fixture asks.json): one mechanism, both ways.
 *
 * Incoming: the desk asks this phone to turn on a [Feature] with `desklink.settings.request {set: {<key>: true}}`. Nothing is turned
 * on from the packet: a heads-up prompt (Allow / Not now) and a banner in the app wait for the person, and only Allow (through
 * [AskConsentActivity]) turns the feature on. The phone's state goes back in its own `desklink.settings` report ([reportPacket]).
 *
 * Outgoing: [AskBook] is where an "Ask on computer" stands; the desk answers in its settings report.
 */
object Asks {
    private const val TAG = "DeskLink/Asks"
    private const val NOTICE_ID = 4418

    /** asks.json waitSeconds: the prompt goes, and the asker stops waiting, after this long. */
    const val WAIT_MS = 60_000L

    /** asks.json reaskSeconds: an ask nobody pressed for, at most this often. */
    const val REASK_MS = 300_000L

    /** asks.json dedupeSeconds: the same ask again within this long is a repeat, dropped. */
    const val DEDUPE_MS = 5_000L

    /** What Allow has to do besides turning the per-desk setting on. */
    enum class Kind { SETTING, PERMISSION, SPECIAL }

    /** asks.json toPhone: what a desk may ask this phone to turn on, with the words "<desk> asks to <phrase>". */
    enum class Feature(val key: String, @StringRes val phrase: Int, val kind: Kind) {
        GLIDE("glide", R.string.ask_feature_glide, Kind.SPECIAL),
        CONTACTS("contacts", R.string.ask_feature_contacts, Kind.PERMISSION),
        NOTIFICATIONS("notifications", R.string.ask_feature_notifications, Kind.SPECIAL),
        CALLS("calls", R.string.ask_feature_calls, Kind.PERMISSION),
        MEDIA("media", R.string.ask_feature_media, Kind.SPECIAL),
        PHOTOS("photos", R.string.ask_feature_photos, Kind.SETTING),
        DND("dnd", R.string.ask_feature_dnd, Kind.SPECIAL),
        CLIPBOARD("clipboard", R.string.ask_feature_clipboard, Kind.SETTING),
        ;

        /** The runtime permissions Allow asks Android for (empty: none, or a special access instead). */
        fun permissions(): List<String> = when (this) {
            CONTACTS -> listOf(Manifest.permission.READ_CONTACTS)
            CALLS -> buildList {
                add(Manifest.permission.READ_PHONE_STATE)
                add(Manifest.permission.CALL_PHONE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
            else -> emptyList()
        }

        /** The system page Allow opens for a special access (glide opens its own setup steps instead). */
        fun specialAction(): String? = when (this) {
            NOTIFICATIONS, MEDIA -> android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            DND -> android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            else -> null
        }

        companion object {
            fun of(key: String?): Feature? = entries.firstOrNull { it.key == key }
        }
    }

    /** asks.json toDesk: what this phone may ask a desk to turn on. Never one of the desk's settable keys. */
    val TO_DESK = listOf("terminalAccess", "unlockNearby", "galleryAccepted", "keepAwakeAllowed", "dndSync", "glide", "control")

    /** The keys a desk's request asks to turn on (`true` only; anything else asks nothing). */
    fun askedKeys(set: JSONObject?): List<Feature> =
        set?.keys()?.asSequence()?.filter { set.opt(it) == true }?.mapNotNull(Feature::of)?.toList().orEmpty()

    // ---- the phone's state ------------------------------------------------------------------------------------------------

    private fun granted(context: Context, permissions: List<String>) =
        permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    private fun pluginOn(device: Device, cls: Class<out Plugin>) = device.isPluginEnabled(Plugin.getPluginKey(cls))

    private fun listenerAccess(context: Context) =
        org.desklink.mobile.plugins.notifications.NotificationReceiver.hasReadNotificationsPermission(context)

    /** On for [device]: its per-desk setting AND what Android must grant for it. */
    fun isOn(context: Context, device: Device, feature: Feature): Boolean = runCatching {
        when (feature) {
            Feature.GLIDE -> org.desklink.mobile.plugins.inputdevicesreceiver.Glide.allowed(context, device.deviceId) &&
                org.desklink.mobile.plugins.mousereceiver.MouseReceiverService.instance != null
            Feature.CONTACTS -> pluginOn(device, org.desklink.mobile.plugins.contacts.ContactsPlugin::class.java) && granted(context, feature.permissions())
            Feature.NOTIFICATIONS -> pluginOn(device, org.desklink.mobile.plugins.notifications.NotificationsPlugin::class.java) && listenerAccess(context)
            Feature.CALLS -> pluginOn(device, org.desklink.mobile.plugins.telephony.TelephonyPlugin::class.java) && granted(context, feature.permissions())
            Feature.MEDIA -> pluginOn(device, org.desklink.mobile.plugins.mprisreceiver.MprisReceiverPlugin::class.java) && listenerAccess(context)
            Feature.PHOTOS -> org.desklink.mobile.plugins.gallery.GalleryWatcher.isOn(context, device.deviceId) &&
                org.desklink.mobile.plugins.gallery.GalleryWatcher.folders(context, device.deviceId).isNotEmpty()
            Feature.DND -> org.desklink.mobile.plugins.battery.DndSync.canSet(context)
            Feature.CLIPBOARD -> pluginOn(device, org.desklink.mobile.plugins.clipboard.ClipboardPlugin::class.java)
        }
    }.getOrDefault(false)

    /** The person tapped Allow: the per-desk setting goes on (what Android grants is asked for by [AskConsentActivity]). */
    fun turnOnSetting(context: Context, device: Device, feature: Feature) {
        fun plugin(cls: Class<out Plugin>) = device.setPluginEnabled(Plugin.getPluginKey(cls), true)
        when (feature) {
            Feature.GLIDE -> org.desklink.mobile.plugins.inputdevicesreceiver.Glide.setAllowed(context, device.deviceId, true)
            Feature.CONTACTS -> plugin(org.desklink.mobile.plugins.contacts.ContactsPlugin::class.java)
            Feature.NOTIFICATIONS -> plugin(org.desklink.mobile.plugins.notifications.NotificationsPlugin::class.java)
            Feature.CALLS -> plugin(org.desklink.mobile.plugins.telephony.TelephonyPlugin::class.java)
            Feature.MEDIA -> plugin(org.desklink.mobile.plugins.mprisreceiver.MprisReceiverPlugin::class.java)
            Feature.PHOTOS -> org.desklink.mobile.plugins.gallery.GalleryWatcher.setOn(context, device.deviceId, true)
            Feature.DND -> Unit // The switch is the desk's (dndSync); the phone only grants the access.
            Feature.CLIPBOARD -> plugin(org.desklink.mobile.plugins.clipboard.ClipboardPlugin::class.java)
        }
    }

    /**
     * Whether an ask can reach the person (asks.json prompts): the app in front shows its banner; otherwise notifications must be
     * allowed and the requests channel not switched off (a quiet channel still lists it in the shade).
     */
    fun promptsOn(context: Context): Boolean =
        org.desklink.mobile.helpers.RequestReach.check(context, NotificationHelper.Channels.REQUESTS) != org.desklink.mobile.helpers.RequestReach.Verdict.NOTIFICATIONS_OFF

    /** `desklink.settings` from the phone: a report of state, never a command (asks.json examples). */
    fun reportPacket(features: Map<String, Boolean>, promptsOn: Boolean, declined: String? = null): NetworkPacket =
        NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SETTINGS).apply {
            this["features"] = JSONObject().apply { features.forEach { (key, on) -> put(key, on) } }
            if (declined != null) this["declined"] = JSONArray().put(declined)
            this["prompts"] = if (promptsOn) "on" else "off"
        }

    fun features(context: Context, device: Device): Map<String, Boolean> =
        Feature.entries.associate { it.key to isOn(context, device, it) }

    // ---- incoming asks ------------------------------------------------------------------------------------------------------

    /** An ask waiting for the person, shown as a notification and as a banner in the app. */
    data class Incoming(val deskId: String, val deskName: String, val feature: Feature, val at: Long)

    /** Every ask still waiting: the app's banner reads it (Compose state). Replaced per (desk, feature), never stacked. */
    val incoming = mutableStateListOf<Incoming>()

    private val lastAsk = HashMap<String, Long>()

    fun tag(deskId: String, key: String) = "ask-$key-$deskId"

    /** What the banner shows now: the asks younger than [WAIT_MS]. */
    fun waiting(now: Long = System.currentTimeMillis()): List<Incoming> = synchronized(incoming) { incoming.filter { now - it.at < WAIT_MS } }

    /**
     * The desk asked for [feature]: a prompt, unless it is already on (then only the report answers) or this is a repeat within
     * [DEDUPE_MS]. Returns whether a prompt was put up.
     */
    fun onAsked(context: Context, deskId: String, deskName: String, feature: Feature, on: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        if (on) {
            forget(context, deskId, feature.key)
            return false
        }
        val id = tag(deskId, feature.key)
        synchronized(lastAsk) {
            val last = lastAsk[id]
            if (last != null && now - last in 0 until DEDUPE_MS) return false
            lastAsk[id] = now
        }
        synchronized(incoming) {
            incoming.removeAll { it.deskId == deskId && it.feature == feature }
            incoming += Incoming(deskId, deskName, feature, now)
        }
        Log.i(TAG, "$deskId asks for ${feature.key}")
        @android.annotation.SuppressLint("MissingPermission")
        fun post() = NotificationManagerCompat.from(context).notify(id, NOTICE_ID, notice(context, deskId, deskName, feature))
        // Notifications blocked: the report says prompts "off" and the banner waits for the app to be opened.
        runCatching { post() }.onFailure { Log.w(TAG, "The prompt could not be shown (${it.javaClass.simpleName})") }
        return true
    }

    /** The prompt and banner for [key] from [deskId] go (answered, turned on another way, or the desk gone). */
    fun forget(context: Context, deskId: String, key: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(tag(deskId, key), NOTICE_ID) }
        // Answered: the next ask is a fresh one, never a "repeat".
        synchronized(lastAsk) { lastAsk.remove(tag(deskId, key)) }
        synchronized(incoming) { incoming.removeAll { it.deskId == deskId && it.feature.key == key } }
    }

    const val ACTION_NOT_NOW = "org.desklink.mobile.ask.NOT_NOW"
    const val EXTRA_DESK = "deskId"
    const val EXTRA_KEY = "key"

    /** Allow: the consent screen, from the notification's own PendingIntent (Android 14+ allows that; never a background start). */
    fun allowIntent(context: Context, deskId: String, key: String): Intent =
        Intent(context, AskConsentActivity::class.java)
            .putExtra(EXTRA_DESK, deskId)
            .putExtra(EXTRA_KEY, key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notice(context: Context, deskId: String, deskName: String, feature: Feature): Notification {
        val id = tag(deskId, feature.key)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val allow = PendingIntent.getActivity(context, id.hashCode(), allowIntent(context, deskId, feature.key), flags)
        val notNow = PendingIntent.getBroadcast(
            context, id.hashCode() + 1,
            Intent(context, AskReceiver::class.java).setAction(ACTION_NOT_NOW).putExtra(EXTRA_DESK, deskId).putExtra(EXTRA_KEY, feature.key),
            flags,
        )
        val open = PendingIntent.getActivity(
            context, id.hashCode() + 2,
            Intent(context, org.desklink.mobile.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            flags,
        )
        val title = context.getString(R.string.ask_prompt, deskName, context.getString(feature.phrase))
        val text = context.getString(R.string.ask_prompt_text)
        // On the lock screen: that something asks, never who or what.
        val public = NotificationCompat.Builder(context, NotificationHelper.Channels.REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.ask_public_title))
            .setContentText(context.getString(R.string.ask_public_text))
            .build()
        return NotificationCompat.Builder(context, NotificationHelper.Channels.REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // Heads-up: the channel is high importance; the priority is the same for Android 7.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .addAction(
                NotificationCompat.Action.Builder(0, context.getString(R.string.ask_allow), allow)
                    // Never from the lock screen: unlock first, then it carries on.
                    .setAuthenticationRequired(true)
                    .build(),
            )
            .addAction(0, context.getString(R.string.ask_not_now), notNow)
            .setAutoCancel(true)
            .setTimeoutAfter(WAIT_MS)
            .build()
    }

    private fun device(deskId: String): Device? = runCatching { DeskLinkApplication.getInstance().getDevice(deskId) }.getOrNull()

    /** Not now (the notification or the banner): the desk hears it was declined. Nothing changes here. */
    fun notNow(context: Context, deskId: String, key: String) {
        forget(context, deskId, key)
        val device = device(deskId) ?: return
        org.desklink.mobile.helpers.ThreadHelper.execute {
            runCatching {
                device.getPlugin(DeskSettingsPlugin::class.java)?.sendReport(declined = key)
                // Glide's own report says it too (the desk's glide row reads that one).
                if (key == Feature.GLIDE.key) device.getPlugin(org.desklink.mobile.plugins.inputdevicesreceiver.InputDevicesReceiverPlugin::class.java)?.reportDeclined()
            }
        }
    }

    /** After Allow: the desk hears the new state (on, or still off if Android's part was refused). */
    fun report(deskId: String) {
        val device = device(deskId) ?: return
        org.desklink.mobile.helpers.ThreadHelper.execute {
            runCatching {
                device.getPlugin(DeskSettingsPlugin::class.java)?.sendReport(force = true)
                device.getPlugin(org.desklink.mobile.plugins.inputdevicesreceiver.InputDevicesReceiverPlugin::class.java)?.reportAllowed()
            }
        }
    }

    /** Every connected desk hears this phone's state if it changed (the app came to the front: a permission may have changed). */
    fun reportAll() {
        val devices = runCatching { DeskLinkApplication.getInstance().devices.values.filter { it.isPaired } }.getOrDefault(emptyList())
        if (devices.isEmpty()) return
        org.desklink.mobile.helpers.ThreadHelper.execute {
            devices.forEach { runCatching { it.getPlugin(DeskSettingsPlugin::class.java)?.sendReport() } }
        }
    }

    /** Not now on the prompt. Not exported: no other app may answer for the person. */
    class AskReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_NOT_NOW) return
            val desk = intent.getStringExtra(EXTRA_DESK) ?: return
            val key = intent.getStringExtra(EXTRA_KEY)?.takeIf { Feature.of(it) != null } ?: return
            notNow(context, desk, key)
        }
    }
}

/**
 * Where an "Ask on computer" stands, per toDesk key (asks.json states): waiting from the ask until an answer or [Asks.WAIT_MS];
 * declined; timed out; undelivered (not sent, retried once when the link is ready); blocked (the desk's prompts are off). A feature
 * the desk reports on ends it in any state, a late answer included.
 */
class AskBook(private val now: () -> Long = System::currentTimeMillis) {
    enum class State { WAITING, DECLINED, TIMED_OUT, UNDELIVERED, BLOCKED }

    private data class Asked(val at: Long, val declined: Boolean = false, val undelivered: Boolean = false, val retried: Boolean = false)

    private val asks = HashMap<String, Asked>()

    @Synchronized
    fun state(key: String, promptsOff: Boolean = false): State? {
        val ask = asks[key] ?: return null
        return when {
            ask.undelivered -> State.UNDELIVERED
            ask.declined -> State.DECLINED
            now() - ask.at >= Asks.WAIT_MS -> State.TIMED_OUT
            promptsOff -> State.BLOCKED
            else -> State.WAITING
        }
    }

    /** One pending ask per key; an ask nobody pressed for ([manual] false) at most every [Asks.REASK_MS]. */
    @Synchronized
    fun mayAsk(key: String, manual: Boolean): Boolean {
        val ask = asks[key] ?: return true
        if (state(key) == State.WAITING) return false
        return manual || now() - ask.at >= Asks.REASK_MS
    }

    @Synchronized
    fun asked(key: String, delivered: Boolean) {
        asks[key] = Asked(now(), undelivered = !delivered)
    }

    @Synchronized
    fun failed(key: String) {
        asks[key]?.let { asks[key] = it.copy(undelivered = true) }
    }

    /** The keys to send again now the link is ready: each undelivered ask once. */
    @Synchronized
    fun toRetry(): List<String> {
        val keys = asks.filter { it.value.undelivered && !it.value.retried }.keys.toList()
        keys.forEach { asks[it] = Asked(now(), retried = true) }
        return keys
    }

    /** The desk's report: what is on ends its ask; what the person declined there says so. */
    @Synchronized
    fun onReport(features: Map<String, Boolean>, declined: List<String>) {
        features.filterValues { it }.keys.forEach(asks::remove)
        declined.forEach { key -> asks[key]?.let { asks[key] = it.copy(declined = true, undelivered = false) } }
    }

    @Synchronized
    fun clear() = asks.clear()
}
