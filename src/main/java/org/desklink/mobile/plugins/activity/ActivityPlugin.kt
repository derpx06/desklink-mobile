/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.activity

import android.app.KeyguardManager
import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import org.desklink.mobile.R
import org.desklink.mobile.helpers.AppsHelper.appNameLookup
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.plugins.notifications.NotificationAppFilter
import org.desklink.mobile.plugins.notifications.NotificationPrivacy
import org.desklink.mobile.plugins.notifications.NotificationReceiver
import org.desklink.mobile.plugins.notifications.NotificationsPlugin
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.webrtc.SyncDigest
import org.json.JSONArray

/**
 * Live activities (W13): timers, progress, navigation and calls that are ongoing on this phone, shown on the computer. Off unless the
 * person turned on "Show ongoing items on <computer>"; and nothing is sent while "Send this phone's notifications" is off.
 */
@LoadablePlugin
class ActivityPlugin : Plugin(), NotificationReceiver.NotificationListener {
    private lateinit var appDatabase: NotificationAppFilter
    private lateinit var mainHandler: Handler
    private val keyguard: KeyguardManager by lazy { context.getSystemService(KeyguardManager::class.java) }
    private var service: NotificationReceiver? = null
    private var lastSentRev: String? = null
    private val send = Runnable { sendChange() }
    /** The notification switch may have been turned off or on: what is shown is worked out again. */
    private val pluginsChanged = org.desklink.mobile.Device.PluginsChangedListener { schedule() }

    override val displayName: String get() = context.getString(R.string.pref_plugin_activity)
    override val description: String get() = context.getString(R.string.pref_plugin_activity_desc)
    override val isEnabledByDefault: Boolean = false
    override val supportedPacketTypes: Array<String> = emptyArray()
    override val outgoingPacketTypes: Array<String> = arrayOf(DeskLinkProtocol.PACKET_TYPE_ACTIVITY)

    override fun checkRequiredPermissions(): Boolean = NotificationReceiver.hasReadNotificationsPermission(context)

    override fun onCreate(): Boolean {
        appDatabase = NotificationAppFilter(context)
        mainHandler = Handler(Looper.getMainLooper())
        device.addPluginsChangedListener(pluginsChanged)
        org.desklink.mobile.helpers.ThreadHelper.execute { appDatabase.preload(); mainHandler.post(::schedule) }
        NotificationReceiver.WithService(context) { s ->
            s.addListener(this)
            service = s
        }
        return true
    }

    override fun onDestroy() {
        device.removePluginsChangedListener(pluginsChanged)
        if (::mainHandler.isInitialized) mainHandler.removeCallbacks(send)
        NotificationReceiver.WithService(context) { s -> s.removeListener(this) }
    }

    override fun onListenerConnected(service: NotificationReceiver?) {
        this.service = service
        schedule()
    }

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification) = schedule()

    override fun onNotificationRemoved(statusBarNotification: StatusBarNotification?) = schedule()

    /** Feature-ready: the snapshot, unless the computer says it already holds exactly this one. */
    override fun onFeatureReady() {
        val items = snapshot()
        val rev = ActivityItems.rev(items)
        lastSentRev = rev
        device.sendSnapshot(SyncDigest.ACTIVITY, rev, listOf(ActivityItems.packet(items, System.currentTimeMillis())))
    }

    /** Turned off: the computer drops what it shows from this phone. */
    override fun onTurnedOff() {
        lastSentRev = ActivityItems.rev(JSONArray())
        device.sendPacket(ActivityItems.packet(JSONArray(), System.currentTimeMillis()).also { it["rev"] = lastSentRev!! })
    }

    /** At most one snapshot a second, and only when what is shown changed. */
    private fun schedule() {
        if (!::mainHandler.isInitialized) return
        mainHandler.removeCallbacks(send)
        mainHandler.postDelayed(send, DEBOUNCE_MS)
    }

    private fun sendChange() {
        val items = snapshot()
        val rev = ActivityItems.rev(items)
        if (rev == lastSentRev) return
        lastSentRev = rev
        device.sendPacket(ActivityItems.packet(items, System.currentTimeMillis()).also { it["rev"] = rev })
    }

    private fun notificationsOn() = device.isPluginEnabled(getPluginKey(NotificationsPlugin::class.java))

    private fun snapshot(): JSONArray {
        val active = service?.takeIf { it.isConnected }?.activeNotifications
        if (!notificationsOn() || active == null || !appDatabase.isReady()) return JSONArray()
        val items = active.mapNotNull { candidate(it) }
            .sortedWith(compareBy({ ActivityItems.importance(it.first.kind) }, { -it.second }))
            .map { ActivityItems.json(it.first, it.third) }
        return ActivityItems.sanitize(JSONArray(items))
    }

    /** (the item, its post time, whether its contents are hidden), or null when it is not an activity or not to be sent. */
    private fun candidate(sbn: StatusBarNotification): Triple<ActivityItems.Candidate, Long, Boolean>? {
        val n = sbn.notification
        val extras = n.extras
        val pkg = sbn.packageName
        val isCall = n.category == Notification.CATEGORY_CALL
        val show = ActivityItems.decide(
            ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            media = n.category == Notification.CATEGORY_TRANSPORT || extras.containsKey(Notification.EXTRA_MEDIA_SESSION),
            ownApp = pkg.startsWith("org.desklink.mobile"),
            appEnabled = appDatabase.isEnabled(pkg),
            privacy = NotificationPrivacy.decide(
                n.visibility, keyguard.isKeyguardSecure, NotificationPrivacy.showPrivate(context, device.deviceId),
                NotificationPrivacy.systemAllowsPrivate(context), NotificationReceiver.channelLockscreenVisibility(sbn),
            ),
            hiddenCall = isCall && !org.desklink.mobile.plugins.telephony.TelephonyPlugin.showCaller(context, device.deviceId),
            blockContents = appDatabase.blocksContents(pkg),
        )
        if (show == ActivityItems.Show.SKIP) return null
        val chronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
        val kind = ActivityItems.kindOf(isCall, n.category == Notification.CATEGORY_NAVIGATION, chronometer, max > 0) ?: return null
        val label = appNameLookup(context, pkg) ?: pkg
        val progress = if (max > 0) extras.getInt(Notification.EXTRA_PROGRESS) * 100 / max else -1
        val candidate = ActivityItems.Candidate(
            id = "${sbn.id}|$pkg|${sbn.tag.orEmpty()}", appLabel = label,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            progress = progress, chronometerBase = if (chronometer) n.`when` else -1L,
            countdown = chronometer && extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN), kind = kind,
        )
        return Triple(candidate, sbn.postTime, show == ActivityItems.Show.HIDDEN)
    }

    private companion object {
        const val DEBOUNCE_MS = 1_000L
    }
}
