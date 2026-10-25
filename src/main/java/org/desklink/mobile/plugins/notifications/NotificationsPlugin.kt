/*
 * SPDX-FileCopyrightText: 2026 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.notifications

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.service.notification.StatusBarNotification
import android.text.SpannableString
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.fragment.app.DialogFragment
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.helpers.AppsHelper.appNameLookup
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.ui.MainActivity
import org.desklink.mobile.ui.StartActivityAlertDialogFragment
import org.desklink.mobile.R
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import androidx.core.graphics.createBitmap
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.webrtc.SyncDigest

@LoadablePlugin
class NotificationsPlugin : Plugin(), NotificationReceiver.NotificationListener {
    private lateinit var appDatabase: AppDatabase
    private val currentNotifications = mutableSetOf<String>()
    // Here we will map every notification to it's icon(hash)
    /**
     * Delivery callbacks run on DeskLink's outbound worker, while Android
     * notification callbacks run on the main thread. Keep synchronization
     * bookkeeping coherent across those two boundaries.
     */
    private val notificationDeliveryLock = Any()
    private val postedNotifications = mutableSetOf<String>()
    private val pendingIntents = PendingReplies<RepliableNotification>(MAX_PENDING_REPLIES)
    private val pendingActions = ArrayListValuedHashMap<String, Notification.Action>()
    private var serviceReady = false
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var mainHandler: Handler
    private val postedNotificationsLock = Any()

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_notifications)

    override val description: String
        get() = context.getString(R.string.pref_plugin_notifications_desc)

    /** Which apps' notifications are shared: its own screen (Settings → Notifications → Choose apps). */
    fun openAppFilter(activity: Activity) {
        val intent = Intent(activity, NotificationFilterActivity::class.java)
        intent.putExtra(PREFERENCE_KEY, this.sharedPreferencesName)
        activity.startActivity(intent)
    }

    override fun checkRequiredPermissions(): Boolean {
        return NotificationReceiver.hasReadNotificationsPermission(context)
    }

    /** Holds notifications until the per-app settings are in memory (and retries a failed load); see [NotificationGate]. */
    private val gate: NotificationGate<Pair<StatusBarNotification, Boolean>> by lazy {
        NotificationGate(
            load = { appDatabase.preload() },
            runWorker = { task -> org.desklink.mobile.helpers.ThreadHelper.execute(Runnable { task() }) },
            postLater = { delay, task -> mainHandler.postDelayed({ task() }, delay) },
            onReady = { held ->
                mainHandler.post {
                    // What was held back goes out now, by the user's settings; and the snapshot is sent again if one was asked for meanwhile.
                    held.forEach { (sbn, preexisting) -> sendNotification(sbn, preexisting) }
                    if (resyncWhenReady) {
                        resyncWhenReady = false
                        NotificationReceiver.WithService(context) { service -> if (service.isConnected) sendCurrentNotifications(service, resync = true) }
                    }
                }
            },
        )
    }
    @Volatile private var resyncWhenReady = false

    override fun onCreate(): Boolean {
        appDatabase = AppDatabase.getInstance(context)
        // The per-app tables into memory, off the main thread: the listener asks about every notification there.
        sharedPreferences = context.getSharedPreferences(sharedPreferencesName, Context.MODE_PRIVATE)
        keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        mainHandler = Handler(Looper.getMainLooper())
        NotificationReceiver.WithService(context) { service ->
            service.addListener(this@NotificationsPlugin)
            serviceReady = service.isConnected
        }
        gate.start() // the per-app settings into memory, off the main thread
        return true
    }

    override fun onDestroy() {
        gate.stop()
        synchronized(notificationDeliveryLock) {
            currentNotifications.clear()
        }
        pendingIntents.clear()
        pendingActions.clear()

        synchronized(postedNotificationsLock) {
            mainHandler.removeCallbacksAndMessages(null)
            postedNotifications.clear()
        }

        NotificationReceiver.WithService(context) { service ->
            service.removeListener(this@NotificationsPlugin)
        }
    }

    override fun onListenerConnected(service: NotificationReceiver?) {
        serviceReady = true
        if (!appDatabase.isReady()) {
            resyncWhenReady = true
            gate.start()
            return
        }
        if (service != null && isDeviceInitialized) {
            // Listener reconnection is a synchronization boundary. Re-send
            // the active set so desktop state does not depend on missed post
            // callbacks while Android was reconnecting the listener.
            sendCurrentNotifications(service)
        }
    }

    /**
     * Feature-ready: the full snapshot (contract S1). The ids first, so the desk drops whatever was
     * dismissed while the link was down (no retried cancellations needed), then each body silent.
     */
    override fun onFeatureReady() {
        if (!appDatabase.isReady()) {
            resyncWhenReady = true // the snapshot lists only what is mirrored: sent when the settings are known
            gate.start()
            return
        }
        if (!isDeviceInitialized || !NotificationReceiver.hasReadNotificationsPermission(context)) {
            sendSnapshot(emptyList(), resync = true)
            return
        }
        NotificationReceiver.WithService(context) { service ->
            serviceReady = service.isConnected
            if (serviceReady) sendCurrentNotifications(service, resync = true)
        }
    }

    /** Turned off: the desk drops every notification it shows from this phone. */
    override fun onTurnedOff() = sendSnapshot(emptyList())

    /** [resync]: a new link, where the desk may already hold exactly this set. */
    private fun sendSnapshot(ids: List<String>, resync: Boolean = false) {
        if (resync) {
            device.sendSnapshot(SyncDigest.NOTIFICATIONS, snapshotRev(ids.map { it to 0L }), listOf(snapshotPacket(ids)))
        } else {
            device.sendPacket(snapshotPacket(ids))
        }
    }

    override fun onNotificationRemoved(statusBarNotification: StatusBarNotification?) {
        if (statusBarNotification == null) {
            Log.w(TAG, "onNotificationRemoved: notification is null")
            return
        }

        val id = getNotificationKeyCompat(statusBarNotification)

        synchronized(postedNotificationsLock) {
            postedNotifications.remove(id)
            cancelDelayedNotification(id)
        }

        pendingActions.remove(id)
        pendingIntents.removeKey(id)

        if (appDatabase.isReady() && !appDatabase.isEnabled(statusBarNotification.packageName)) {
            synchronized(notificationDeliveryLock) {
                currentNotifications.remove(id)
            }
            return
        }

        // (A cancellation carries no content: sent even while the settings are still loading.)
        sendNotificationCancellation(id)
        synchronized(notificationDeliveryLock) {
            currentNotifications.remove(id)
        }
    }

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
        val key = getNotificationKeyCompat(statusBarNotification)
        synchronized(postedNotificationsLock) {
            postedNotifications.add(key)
        }

        val sendOnlyIfLocked = sharedPreferences.getBoolean(PREF_NOTIFICATION_SCREEN_OFF, false)
        val isLocked = keyguardManager.isKeyguardLocked
        if (!sendOnlyIfLocked || isLocked) {
            sendNotificationWithDelay(statusBarNotification)
        }
    }

    private fun sendNotificationWithDelay(statusBarNotification: StatusBarNotification) {
        val key = getNotificationKeyCompat(statusBarNotification)
        synchronized(postedNotificationsLock) {
            cancelDelayedNotification(key)
            if (!postedNotifications.contains(key)) {
                return
            }
            val delayedNotification = Message.obtain(mainHandler, Runnable {
                synchronized(postedNotificationsLock) {
                    if (!postedNotifications.contains(key)) {
                        return@Runnable
                    }
                }
                sendNotification(statusBarNotification, false)
            })
            delayedNotification.obj = key
            mainHandler.sendMessageDelayed(delayedNotification, NOTIFICATION_SYNC_DELAY_MS)
        }
    }

    private fun cancelDelayedNotification(key: String) {
        mainHandler.removeCallbacksAndMessages(key)
    }

    /** Whether [sendNotification] would mirror it: also what the snapshot's `ids` list. */
    private fun isMirrored(statusBarNotification: StatusBarNotification): Boolean {
        val notification = statusBarNotification.notification
        if ((notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
            || (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
            || (notification.flags and Notification.FLAG_LOCAL_ONLY) != 0
            || (notification.flags and NotificationCompat.FLAG_GROUP_SUMMARY) != 0
        ) {
            return false
        }
        val packageName = statusBarNotification.packageName
        if (!appDatabase.isEnabled(packageName)) return false
        // Android's own "secret" mark: never copied (one-time codes, banking).
        if (privacyOf(notification) == NotificationPrivacy.Mirror.SKIP) return false
        if ("com.android.systemui" == packageName) {
            if ("low_battery" == statusBarNotification.tag) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && "MediaOngoingActivity" == notification.channelId) return false
        }
        if ("org.desklink.mobile" == packageName || "org.desklink.mobile.debug" == packageName) return false
        // T3: who is calling reaches the desk only with "Show caller on <desk>"; the dialer's
        // call and missed-call notifications would otherwise tell it anyway.
        if (isCallNotification(statusBarNotification) &&
            !org.desklink.mobile.plugins.telephony.TelephonyPlugin.showCaller(context, device.deviceId)
        ) {
            return false
        }
        return true
    }

    private fun privacyOf(notification: Notification): NotificationPrivacy.Mirror = NotificationPrivacy.decide(
        notification.visibility, keyguardManager.isKeyguardSecure, NotificationPrivacy.showPrivate(context, device.deviceId),
    )

    private fun isCallNotification(statusBarNotification: StatusBarNotification): Boolean {
        val category = statusBarNotification.notification.category
        if (category == Notification.CATEGORY_CALL || category == "missed_call") return true
        val dialer = runCatching {
            context.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()
        return dialer != null && dialer == statusBarNotification.packageName
    }

    // isPreexisting is true for notifications that we are sending in response to a request command
    // and that we want to send with the "silent" flag set
    private fun sendNotification(
        statusBarNotification: StatusBarNotification,
        isPreexisting: Boolean
    ) {
        val notification = statusBarNotification.notification
        // One filter for what is mirrored and what the snapshot lists.
        // Settings not in memory yet: nothing is mirrored on a guess. It is held and sent once they are.
        if (!appDatabase.isReady()) {
            gate.hold(statusBarNotification to isPreexisting)
            return
        }
        if (!isMirrored(statusBarNotification)) return
        val packageName = statusBarNotification.packageName

        val key = getNotificationKeyCompat(statusBarNotification)
        val np = NetworkPacket(PACKET_TYPE_NOTIFICATION)
        // No icon is rendered, encoded or sent: the computer never shows it (the transport strips a notification's payload).
        // Doing it cost a bitmap render and a PNG encode on the main thread for every notification of a resync.

        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)

        val isConversation = messagingStyle != null
        val isGroupConversation = isConversation && messagingStyle.isGroupConversation() && messagingStyle.conversationTitle != null

        np["actions"] = extractActions(notification, key)

        np["id"] = key
        np["isClearable"] = statusBarNotification.isClearable
        val appName = appNameLookup(context, packageName)
        np["appName"] = appName ?: packageName
        np["time"] = statusBarNotification.postTime.toString()
        np["silent"] = isPreexisting

        val hideContents = privacyOf(notification) == NotificationPrivacy.Mirror.HIDDEN
        if (hideContents) {
            // PRIVATE on a secured lock screen: what the lock screen itself shows (the public version), else only the app.
            val shown = notification.publicVersion
            np["title"] = shown?.let { extractStringFromExtra(it.extras, NotificationCompat.EXTRA_TITLE) } ?: (appName ?: packageName)
            np["text"] = shown?.let { extractText(it) } ?: context.getString(R.string.notification_private_hidden)
        } else if (!appDatabase.getPrivacy(packageName, AppDatabase.PrivacyOptions.BLOCK_CONTENTS)) {
            val rn = extractRepliableNotification(statusBarNotification)
            if (rn != null) {
                np["requestReplyId"] = rn.id
                pendingIntents.put(key, rn.id, rn)
            }

            np["ticker"] = getTickerText(notification)

            var conversationLength = 0
            if (isConversation) {
                val conversation = extractConversation(messagingStyle)
                conversationLength = conversation.length()
                if (conversationLength != 0) {
                    np["conversation"] = conversation
                }
            }

            // even if it's a conversation, we set `title` and `text` for compatibility.
            if (isGroupConversation) {
                val groupName = messagingStyle.conversationTitle.toString()
                // FIXME: When there're more than one message in group conversation and the user didn't reply, the number of messages appears between "()" next to the name of the group
                // We don't want to show them because Android doesn't show them.
                np["groupName"] = groupName
                // HACK: To differentiate between groups messages and DMs, we set `title` to be the group name and `text` to be `<sender>: <message>`.
                np["title"] = groupName
                val (sender, content) = getMessageAt(messagingStyle, conversationLength - 1)
                np["text"] = "$sender: $content"
            } else {
                val title = extractStringFromExtra(notification.extras, NotificationCompat.EXTRA_TITLE)
                if (title != null) {
                    np["title"] = title
                }
                val text = extractText(notification)
                if (text != null) {
                    np["text"] = text
                }
            }
        }
        fitToWire(np)
        // `sendPacket` only queues work. Its return cannot prove that the
        // authenticated WebRTC event channel accepted the notification. Mark
        // it synchronized only from the completion callback so a failed
        // handover/channel write remains eligible for the next resync.
        device.sendPacket(np, object : org.desklink.mobile.Device.SendPacketStatusCallback() {
            override fun onSuccess() {
                synchronized(notificationDeliveryLock) {
                    currentNotifications.add(key)
                }
            }

            override fun onFailure(e: Throwable) {
                synchronized(notificationDeliveryLock) {
                    currentNotifications.remove(key)
                }
                Log.w(TAG, "Unable to synchronize notification $key; it will be retried on resync", e)
            }
        })
    }

    /** Best effort: a dismissal lost while the link is down is corrected by the next snapshot. */
    private fun sendNotificationCancellation(id: String) {
        if (id.isBlank()) return
        val packet = NetworkPacket(PACKET_TYPE_NOTIFICATION)
        packet["id"] = id
        packet["isCancel"] = true
        device.sendPacket(packet)
    }

    private fun extractText(notification: Notification): String? {
        val extras = notification.extras
        if (extras.containsKey(NotificationCompat.EXTRA_BIG_TEXT)) {
            return extractStringFromExtra(extras, NotificationCompat.EXTRA_BIG_TEXT)
        }
        return extractStringFromExtra(extras, NotificationCompat.EXTRA_TEXT)
    }

    private fun getMessageAt(
        messagingStyle: NotificationCompat.MessagingStyle,
        index: Int
    ): Pair<String, String> {
        val message = messagingStyle.messages.getOrNull(index)
        val sender = message?.person?.name?.toString() ?: context.getString(R.string.unknown_sender)
        val content = message?.text?.toString() ?: ""
        return Pair(sender, content)
    }

    private fun extractActions(notification: Notification, key: String): JSONArray? {
        val notiActions = notification.actions
        if (notiActions.isNullOrEmpty()) {
            return null
        }

        val jsonArray = JSONArray()
        // An update replaces the actions of the key, it does not add to them.
        synchronized(pendingActions) { pendingActions.remove(key) }
        for (action in notiActions) {
            val title = action.title
                ?: continue

            // Check whether it is a reply action. We have special treatment for them
            if (action.remoteInputs?.isNotEmpty() == true) {
                continue
            }

            jsonArray.put(title.toString())

            // A list is automatically created if it doesn't already exist.
            pendingActions.put(key, action)
        }

        return jsonArray
    }

    private fun extractConversation(messagingStyle: NotificationCompat.MessagingStyle?): JSONArray {
        if (messagingStyle == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return JSONArray()
        }
        val messages = messagingStyle.messages
        val conversation = JSONArray()
        for (message in messages) {
            val sender = message?.person?.name?.toString() ?: context.getString(R.string.unknown_sender)
            val content = message.text?.toString() ?: ""
            conversation.put(JSONObject().apply {
                put("sender", sender)
                put("content", content)
            })
        }
        return conversation
    }

    private fun replyToNotification(id: String, message: String) {
        val repliableNotification = pendingIntents.get(id)
        if (repliableNotification == null) {
            Log.e(TAG, "No such notification")
            return
        }
        val remoteInputs = arrayOfNulls<RemoteInput>(repliableNotification.remoteInputs.size)

        val localIntent = Intent()
        localIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val localBundle = Bundle()
        repliableNotification.remoteInputs.forEachIndexed { i, remoteInput ->
            remoteInputs[i] = remoteInput
            localBundle.putCharSequence(remoteInput.resultKey, message)
        }
        RemoteInput.addResultsToIntent(remoteInputs, localIntent, localBundle)

        try {
            repliableNotification.pendingIntent.send(context, 0, localIntent)
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "replyToNotification error (" + e.javaClass.getSimpleName() + ")")
        }
        pendingIntents.take(id)
    }

    private fun extractRepliableNotification(statusBarNotification: StatusBarNotification): RepliableNotification? {
        val notiActions = statusBarNotification.notification.actions
            ?: return null

        for (action in notiActions) {
            if (action != null && action.remoteInputs != null && action.actionIntent != null) {
                // Is a reply
                return RepliableNotification(
                    pendingIntent = action.actionIntent,
                    remoteInputs = action.remoteInputs.toList(),
                    packageName = statusBarNotification.packageName,
                )
            }
        }

        return null
    }

    /**
     * Returns the the title and text of the notification.
     */
    private fun getTickerText(notification: Notification): String {
        var ticker = ""

        try {
            val extras = notification.extras
            val extraTitle = extractStringFromExtra(extras, NotificationCompat.EXTRA_TITLE)
            val extraText = extractStringFromExtra(extras, NotificationCompat.EXTRA_TEXT)

            if (extraTitle != null && !TextUtils.isEmpty(extraText)) {
                ticker = "$extraTitle: $extraText"
            } else if (extraTitle != null) {
                ticker = extraTitle
            } else if (extraText != null) {
                ticker = extraText
            }
        } catch (e: Exception) {
            Log.e(TAG, "problem parsing notification extras", e)
        }

        if (ticker.isEmpty()) {
            ticker = if (notification.tickerText != null) notification.tickerText.toString() else ""
        }

        return ticker
    }

    private fun sendCurrentNotifications(service: NotificationReceiver, resync: Boolean = false) {
        if (!NotificationReceiver.hasReadNotificationsPermission(context)) {
            return
        }
        val notifications= try {
            service.getActiveNotifications()
        } catch (_: SecurityException) {
            return
        }
        if (notifications == null) {
            return // Can happen only on API 23 and lower
        }
        val mirrored = notifications.filter { runCatching { isMirrored(it) }.getOrDefault(false) }
        // The ids first: the desk drops every other notification it holds from this phone.
        val ids = mirrored.map(::getNotificationKeyCompat)
        if (resync) {
            // A new link where the desk already holds this exact set (same ids, same post times): nothing to send.
            val rev = snapshotRev(mirrored.map { getNotificationKeyCompat(it) to it.postTime })
            if (!device.sync.shouldSend(SyncDigest.NOTIFICATIONS, rev)) return
            device.sendSnapshot(SyncDigest.NOTIFICATIONS, rev, listOf(snapshotPacket(ids)))
        } else {
            sendSnapshot(ids)
        }
        for (notification in mirrored) {
            // A single malformed third-party notification must not prevent
            // the rest of the active notification set from being mirrored.
            try {
                sendNotification(notification, true)
            } catch (error: Exception) {
                Log.w(TAG, "Skipping notification during resync (${error.javaClass.simpleName})")
            }
        }
    }

    /**
     * Routes strictly on `np.type`.
     *
     * This used to be a chain of body-field tests with only the first arm
     * type-checked, so the action taken had nothing to do with the packet type
     * declared. A `desklink.notification.request` carrying `requestReplyId` and
     * `message` **sent a reply** — firing a messaging app's reply intent — and a
     * `desklink.notification.reply` carrying `cancel` dismissed a notification.
     * A peer picked which side effect to trigger purely by choosing body keys.
     */
    override fun onPacketReceived(np: NetworkPacket): Boolean {
        when (np.type) {
            PACKET_TYPE_NOTIFICATION_ACTION -> triggerNotificationAction(np)

            // A request either asks for a full resync or dismisses one
            // notification; both spellings have always arrived on this type.
            PACKET_TYPE_NOTIFICATION_REQUEST -> when {
                np.has("cancel") -> dismissNotification(np.getString("cancel"))
                np.getBoolean("request") -> if (serviceReady) {
                    NotificationReceiver.WithService(context) { service ->
                        this.sendCurrentNotifications(service)
                    }
                }
            }

            PACKET_TYPE_NOTIFICATION_REPLY -> {
                if (np.has("requestReplyId") && np.has("message")) {
                    replyToNotification(np.getString("requestReplyId"), np.getString("message"))
                }
            }
        }

        return true
    }

    private fun triggerNotificationAction(np: NetworkPacket) {
        val key = np.getString("key")
        val title = np.getString("action")
        var intent: PendingIntent? = null

        for (a in pendingActions.get(key)) {
            if (a.title == title) {
                intent = a.actionIntent
                break
            }
        }

        if (intent != null) {
            try {
                intent.send()
            } catch (e: PendingIntent.CanceledException) {
                Log.e(TAG, "Triggering action failed", e)
            }
        }
    }

    private fun dismissNotification(dismissedId: String) {
        synchronized(notificationDeliveryLock) {
            currentNotifications.remove(dismissedId)
        }
        NotificationReceiver.WithService(context) { service ->
            service.cancelNotification(dismissedId)
        }
    }

    override val permissionExplanationDialog: DialogFragment
        get() = StartActivityAlertDialogFragment.Builder()
            .setTitle(R.string.pref_plugin_notifications)
            .setMessage(R.string.no_permissions)
            .setPositiveButton(R.string.open_settings)
            .setNegativeButton(R.string.cancel)
            .setIntentAction("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            .setStartForResult(true)
            .setRequestCode(MainActivity.RESULT_NEEDS_RELOAD)
            .create()

    override val supportedPacketTypes = arrayOf(
            PACKET_TYPE_NOTIFICATION_REQUEST,
            PACKET_TYPE_NOTIFICATION_REPLY,
            PACKET_TYPE_NOTIFICATION_ACTION
        )

    override val outgoingPacketTypes = arrayOf(PACKET_TYPE_NOTIFICATION)


    companion object {
        /** `desklink.notification {snapshot: true, ids}` (contract S1). */
        /** A notification's longest texts are cut so its packet crosses the data channel (wire size fix). */
        const val MAX_TEXT_CHARS = 4_000

        fun fitToWire(np: NetworkPacket) {
            fun fits() = np.serialize().toByteArray().size <= org.desklink.mobile.webrtc.WebRtcEnvelope.FEATURE_PACKET_BUDGET
            if (fits()) return
            for (key in listOf("text", "ticker", "title", "groupName")) {
                np.getStringOrNull(key)?.takeIf { it.length > MAX_TEXT_CHARS }?.let { np[key] = it.take(MAX_TEXT_CHARS) + "…" }
            }
            // The conversation keeps its newest messages that fit.
            val conversation = np.getJSONArray("conversation")
            while (!fits() && conversation != null && conversation.length() > 0) {
                conversation.remove(0)
                np["conversation"] = conversation
            }
            if (!fits()) np["conversation"] = null as org.json.JSONArray?
        }

        /** The snapshot's rev: the ids with their post times (an update re-posts, so it changes the rev). */
        fun snapshotRev(entries: List<Pair<String, Long>>): String =
            SyncDigest.rev(entries.sortedBy { it.first }.map { "${it.first}@${it.second}" })

        /** S1: every posted id. Ids past [SNAPSHOT_BUDGET] are left out (a packet must cross the data channel). */
        fun snapshotPacket(ids: List<String>) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_NOTIFICATION).apply {
            this["snapshot"] = true
            var size = 0
            this["ids"] = org.json.JSONArray(ids.takeWhile { id -> (size + org.json.JSONObject.quote(id).length + 1).also { size = it } <= SNAPSHOT_BUDGET })
        }

        /** The snapshot's ids, serialized (about 400 notifications; Android keeps far fewer). */
        const val SNAPSHOT_BUDGET = org.desklink.mobile.webrtc.WebRtcEnvelope.FEATURE_PACKET_BUDGET - 1024

        private const val PACKET_TYPE_NOTIFICATION = DeskLinkProtocol.PACKET_TYPE_NOTIFICATION
        private const val PACKET_TYPE_NOTIFICATION_REQUEST = DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_REQUEST
        private const val PACKET_TYPE_NOTIFICATION_REPLY = DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_REPLY
        private const val PACKET_TYPE_NOTIFICATION_ACTION = DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_ACTION
        const val PREFERENCE_KEY = "prefKey"
        const val MAX_PENDING_REPLIES = 200
        const val PREF_NOTIFICATION_SCREEN_OFF = "pref_notification_screen_off"
        private const val NOTIFICATION_SYNC_DELAY_MS = 50L

        private const val TAG = "DeskLink/NotificationsPlugin"

        private fun extractStringFromExtra(extras: Bundle, key: String): String? {
            val extra = extras.get(key)
            return when (extra) {
                null -> null
                is String -> extra
                is SpannableString -> extra.toString()
                else -> {
                    Log.e(TAG, "Don't know how to extract text from extra of type: " + extra.javaClass.getCanonicalName())
                    null
                }
            }
        }

        private fun getNotificationKeyCompat(statusBarNotification: StatusBarNotification): String {
            // first check if it's one of our remoteIds
            val tag = statusBarNotification.tag
            return if (tag != null && tag.startsWith("kdeconnectId:")) {
                statusBarNotification.id.toString()
            } else {
                statusBarNotification.key
            }
        }

    }
}
