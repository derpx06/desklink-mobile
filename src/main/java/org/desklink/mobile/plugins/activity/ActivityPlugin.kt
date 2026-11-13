/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.activity

import android.app.KeyguardManager
import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private lateinit var appFilter: NotificationAppFilter
    private lateinit var mainHandler: Handler
    private val keyguard: KeyguardManager by lazy { context.getSystemService(KeyguardManager::class.java) }
    @Volatile private var service: NotificationReceiver? = null
    private var lastSentRev: String? = null
    private val throttle = ActivityItems.Throttle()

    /** The notification switch may have been turned off or on: what is shown is worked out again. */
    private val pluginsChanged = org.desklink.mobile.Device.PluginsChangedListener { schedule() }

    /** The per-app choices (Choose apps, hide contents) and the private-contents switch changed: the same. */
    private val filterChanged = Runnable { schedule() }
    private val prefsChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == NotificationPrivacy.key(device.deviceId)) schedule()
    }

    override val displayName: String get() = context.getString(R.string.pref_plugin_activity)
    override val description: String get() = context.getString(R.string.pref_plugin_activity_desc)
    override val isEnabledByDefault: Boolean = false
    override val supportedPacketTypes: Array<String> = emptyArray()
    override val outgoingPacketTypes: Array<String> = arrayOf(DeskLinkProtocol.PACKET_TYPE_ACTIVITY)

    override fun checkRequiredPermissions(): Boolean = NotificationReceiver.hasReadNotificationsPermission(context)

    override fun onCreate(): Boolean {
        appFilter = NotificationAppFilter(context)
        mainHandler = Handler(Looper.getMainLooper())
        device.addPluginsChangedListener(pluginsChanged)
        NotificationAppFilter.addChangeListener(filterChanged)
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener(prefsChanged)
        org.desklink.mobile.helpers.ThreadHelper.execute { appFilter.preload(); schedule() }
        NotificationReceiver.WithService(context) { s ->
            s.addListener(this)
            service = s
        }
        return true
    }

    override fun onDestroy() {
        device.removePluginsChangedListener(pluginsChanged)
        if (::appFilter.isInitialized) NotificationAppFilter.removeChangeListener(filterChanged)
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).unregisterOnSharedPreferenceChangeListener(prefsChanged)
        if (::mainHandler.isInitialized) mainHandler.removeCallbacksAndMessages(null)
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
        org.desklink.mobile.helpers.ThreadHelper.execute {
            val items = snapshot() ?: JSONArray()
            val rev = ActivityItems.rev(items)
            synchronized(this) { lastSentRev = rev }
            device.sendSnapshot(SyncDigest.ACTIVITY, rev, listOf(ActivityItems.packet(items, System.currentTimeMillis())))
        }
    }

    /** Turned off: the computer drops what it shows from this phone. */
    override fun onTurnedOff() {
        val empty = ActivityItems.rev(JSONArray())
        synchronized(this) { lastSentRev = empty }
        device.sendPacket(ActivityItems.packet(JSONArray(), System.currentTimeMillis()).also { it["rev"] = empty })
    }

    /** At most one snapshot a second (one in five with the screen off): the first change goes at once, then one trailing send. */
    private fun schedule() {
        if (!::mainHandler.isInitialized) return
        mainHandler.post {
            val screenOn = context.getSystemService(android.os.PowerManager::class.java)?.isInteractive != false
            val interval = if (screenOn) 1_000L else ActivityItems.SCREEN_OFF_INTERVAL_MS
            val wait = throttle.onChange(SystemClock.elapsedRealtime(), interval) ?: return@post
            if (wait == 0L) sendChange() else mainHandler.postDelayed({ throttle.onFired(SystemClock.elapsedRealtime()); sendChange() }, wait)
        }
    }

    /** Android is asked and the rules applied off the main thread; only a changed snapshot is sent. */
    private fun sendChange() {
        org.desklink.mobile.helpers.ThreadHelper.execute {
            val items = snapshot() ?: return@execute
            val rev = ActivityItems.rev(items)
            synchronized(this) { if (rev == lastSentRev) return@execute; lastSentRev = rev }
            device.sendPacket(ActivityItems.packet(items, System.currentTimeMillis()).also { it["rev"] = rev })
        }
    }

    private fun notificationsOn() = device.isPluginEnabled(getPluginKey(NotificationsPlugin::class.java))

    /** Null when Android cannot be asked (access lost, listener gone): nothing is sent on a guess. */
    private fun snapshot(): JSONArray? {
        if (!notificationsOn()) return JSONArray()
        val active = runCatching { service?.takeIf { it.isConnected }?.activeNotifications }.getOrNull() ?: return null
        if (!appFilter.isReady()) return null
        val items = active.mapNotNull { runCatching { candidate(it) }.getOrNull() }
            .sortedWith(compareBy({ ActivityItems.importance(it.first.kind) }, { -it.second }))
            .map { ActivityItems.json(it.first, it.third) }
        return ActivityItems.sanitize(JSONArray(items))
    }

    /** (the item, its post time, whether its contents are hidden), or null when it is not an activity or not to be sent. */
    private fun candidate(sbn: StatusBarNotification): Triple<ActivityItems.Candidate, Long, Boolean>? {
        val n = sbn.notification
        val extras = n.extras
        val pkg = sbn.packageName
        val dialer = runCatching { context.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
        val isCall = ActivityItems.isCall(n.category, pkg, dialer)
        val show = ActivityItems.decide(
            ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0 && n.flags and Notification.FLAG_LOCAL_ONLY == 0,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            media = n.category == Notification.CATEGORY_TRANSPORT || extras.containsKey(Notification.EXTRA_MEDIA_SESSION),
            ownApp = pkg.startsWith("org.desklink.mobile"),
            appEnabled = appFilter.isEnabled(pkg),
            privacy = NotificationPrivacy.decide(
                n.visibility, keyguard.isKeyguardSecure, NotificationPrivacy.showPrivate(context, device.deviceId),
                NotificationPrivacy.systemAllowsPrivate(context), NotificationReceiver.channelLockscreenVisibility(sbn),
            ),
            hiddenCall = isCall && !org.desklink.mobile.plugins.telephony.TelephonyPlugin.showCaller(context, device.deviceId),
            blockContents = appFilter.blocksContents(pkg),
        )
        if (show == ActivityItems.Show.SKIP) return null
        val chronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
        val kind = ActivityItems.kindOf(isCall, n.category == Notification.CATEGORY_NAVIGATION, chronometer, max > 0) ?: return null
        val label = appNameLookup(context, pkg) ?: pkg
        val candidate = ActivityItems.Candidate(
            id = ActivityItems.id(sbn.id, pkg, sbn.tag), appLabel = label,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            progress = ActivityItems.percent(extras.getInt(Notification.EXTRA_PROGRESS), max), chronometerBase = if (chronometer) n.`when` else -1L,
            countdown = chronometer && extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN), kind = kind,
        )
        return Triple(candidate, sbn.postTime, show == ActivityItems.Show.HIDDEN)
    }
}
