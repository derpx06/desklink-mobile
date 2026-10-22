/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.helpers.LifecycleHelper
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.helpers.TrustedDevices
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.plugins.contacts.ContactsPlugin
import org.desklink.mobile.plugins.notifications.NotificationReceiver
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import java.util.UUID

/**
 * Calls on the desk (contract T1–T3): the phone owns the call state and sends the whole of it
 * (`desklink.telephony {calls, muted, silenced}`) on every change and every feature-ready; a
 * missed call is a one-shot. The desk answers, declines, ends, silences, mutes or dials with
 * `desklink.telephony.request`; each is answered with a fresh snapshot or `{error, action}`.
 *
 * Off by default ("Calls on <desk>"). The line state needs READ_PHONE_STATE; answering and
 * hanging up ANSWER_PHONE_CALLS; dialling CALL_PHONE; names READ_CONTACTS and only with "Show
 * caller on <desk>". No call-log access: who is calling comes from the dialer's own notification.
 */
@LoadablePlugin
class TelephonyPlugin : Plugin(), NotificationReceiver.NotificationListener {
    private val tracker by lazy {
        CallTracker(System::currentTimeMillis, CallTracker.decode(settings.getString(KEY_LIVE_CALL, null))) { UUID.randomUUID().toString() }
    }
    private val settings get() = TrustedDevices.getDeviceSettings(context, device.deviceId)
    private val flags = CallFlags()
    private var unregister: (() -> Unit)? = null

    /** The dialer's call notification: who, and its Decline / Hang up buttons. */
    @Volatile private var dialerNotification: StatusBarNotification? = null
    private var ringerMuted = false

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_calls)

    override val description: String
        get() = context.getString(R.string.pref_plugin_calls_desc)

    override val isEnabledByDefault: Boolean = false

    override val requiredPermissions: Array<String> = arrayOf(Manifest.permission.READ_PHONE_STATE)

    override val optionalPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(Manifest.permission.ANSWER_PHONE_CALLS)
            add(Manifest.permission.CALL_PHONE)
            if (showCaller(context, device.deviceId)) add(Manifest.permission.READ_CONTACTS)
        }.toTypedArray()

    override val supportedPacketTypes: Array<String> = arrayOf(DeskLinkProtocol.PACKET_TYPE_TELEPHONY_REQUEST)
    override val outgoingPacketTypes: Array<String> = arrayOf(DeskLinkProtocol.PACKET_TYPE_TELEPHONY)

    @SuppressLint("MissingPermission")
    override fun onCreate(): Boolean {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return false
        unregister = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = onLine(state)
            }
            runCatching { telephony.registerTelephonyCallback(ContextCompat.getMainExecutor(context), callback) }
                .onFailure { Log.w(TAG, "No call state", it) }
            ({ telephony.unregisterTelephonyCallback(callback) })
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = onLine(state)
            }
            @Suppress("DEPRECATION")
            telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            @Suppress("DEPRECATION")
            ({ telephony.listen(listener, PhoneStateListener.LISTEN_NONE) })
        }
        if (NotificationReceiver.hasReadNotificationsPermission(context)) {
            NotificationReceiver.RunCommand(context) { it.addListener(this) }
        }
        return true
    }

    override fun onDestroy() {
        unregister?.invoke()
        unregister = null
        restoreRinger()
        NotificationReceiver.RunCommand(context) { it.removeListener(this) }
    }

    /** The full snapshot on every new link (Phase 1). */
    override fun onFeatureReady() = sendSnapshot()

    /** Turned off: the desk drops its call card. */
    override fun onTurnedOff() {
        device.sendPacket(CallTracker.emptySnapshot())
    }

    private fun onLine(state: Int) {
        val line = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> LineState.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> LineState.OFFHOOK
            else -> LineState.IDLE
        }
        val missed = tracker.onLine(line)
        if (line != LineState.RINGING) {
            restoreRinger()
            flags.silenced = false
        }
        if (line == LineState.IDLE) flags.muted = false
        identify()
        // Kept while off the hook, so an app restart mid-call keeps its id and timer.
        val live = CallTracker.toSave(tracker.calls).takeIf { line == LineState.OFFHOOK }
        settings.edit { if (live != null) putString(KEY_LIVE_CALL, CallTracker.encode(live)) else remove(KEY_LIVE_CALL) }
        sendSnapshot()
        missed?.let { device.sendPacket(CallTracker.missedPacket(it, System.currentTimeMillis(), showCaller(context, device.deviceId))) }
    }

    private fun sendSnapshot() {
        device.sendPacket(CallTracker.snapshotPacket(tracker.calls, flags.muted, flags.silenced, showCaller(context, device.deviceId)))
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != DeskLinkProtocol.PACKET_TYPE_TELEPHONY_REQUEST) return false
        val action = np.getString("action", "")
        val error = CallRequests.handle(action, np.getStringOrNull("number"), tracker, controls, flags)
        if (error != null) {
            device.sendPacket(CallTracker.errorPacket(error, action))
        } else {
            if (action == "answer") notifyAnswered()
            sendSnapshot()
        }
        return true
    }

    // ---- the platform behind CallRequests --------------------------------------------------------

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private val controls = object : CallControls {
        private val telecom get() = context.getSystemService(TelecomManager::class.java)
        private val audio get() = context.getSystemService(AudioManager::class.java)

        override fun canAnswer() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && granted(Manifest.permission.ANSWER_PHONE_CALLS)

        override fun canDial() = granted(Manifest.permission.CALL_PHONE)

        @SuppressLint("MissingPermission")
        override fun acceptRingingCall(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            runCatching { telecom?.acceptRingingCall() != null }.getOrDefault(false)

        @SuppressLint("MissingPermission")
        override fun endCall(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && canAnswer() &&
            runCatching {
                @Suppress("DEPRECATION")
                telecom?.endCall() == true
            }.getOrDefault(false)

        override fun notificationAction(hangUp: Boolean): Boolean {
            val actions = dialerNotification?.notification?.actions ?: return false
            val action = actions.firstOrNull { DECLINE_WORDS.any { w -> it.title?.toString()?.contains(w, ignoreCase = true) == true } } ?: return false
            return runCatching { action.actionIntent.send(); true }.getOrDefault(false)
        }

        override fun muteRinger(): Boolean = runCatching {
            audio?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            ringerMuted = true
            true
        }.getOrDefault(false)

        override fun setMicrophoneMute(mute: Boolean): Boolean = runCatching {
            val manager = audio ?: return false
            manager.isMicrophoneMute = mute
            // Some phones only honour this for their own dialer: report what actually happened.
            manager.isMicrophoneMute == mute
        }.getOrDefault(false)

        @SuppressLint("MissingPermission")
        override fun placeCall(number: String): Boolean = runCatching {
            telecom?.placeCall(Uri.fromParts("tel", number, null), Bundle()) != null
        }.getOrDefault(false)

        /**
         * K5: the messaging app's compose screen. In front it opens at once; from the background
         * Android may refuse an activity start, so a heads-up opens it on a tap.
         */
        @SuppressLint("MissingPermission")
        override fun openMessages(number: String): Boolean {
            val compose = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (compose.resolveActivity(context.packageManager) == null) return false
            if (LifecycleHelper.isInForeground && runCatching { context.startActivity(compose) }.isSuccess) return true
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return false
            val tap = PendingIntent.getActivity(context, number.hashCode(), compose, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.telephony_message_title, number))
                .setContentText(context.getString(R.string.telephony_message_text, device.name))
                .setContentIntent(tap)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setTimeoutAfter(120_000)
                .build()
            return runCatching { manager.notify("message:${device.deviceId}", 7331, notification) }.isSuccess
        }
    }

    private fun restoreRinger() {
        if (!ringerMuted) return
        ringerMuted = false
        runCatching { context.getSystemService(AudioManager::class.java)?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0) }
    }

    // ---- who is calling ------------------------------------------------------------------------

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
        if (statusBarNotification.notification.category != Notification.CATEGORY_CALL) return
        if (statusBarNotification.packageName.startsWith("org.desklink.mobile")) return
        dialerNotification = statusBarNotification
        // The dialer names the caller after the line changed: send the names once they are known.
        if (tracker.hasCall() && (showCaller(context, device.deviceId) || sharesContacts())) {
            identify()
            sendSnapshot()
        }
    }

    override fun onNotificationRemoved(statusBarNotification: StatusBarNotification) {
        if (dialerNotification?.key == statusBarNotification.key) dialerNotification = null
    }

    override fun onListenerConnected(service: NotificationReceiver) = Unit

    /**
     * Who is calling, from the dialer notification's title: a number is looked up in contacts. The
     * name and number go to the desk only with "Show caller"; the shared contact's uid (F5 K4) goes
     * whenever this phone shares its contacts with the desk, which then names the call itself.
     */
    private fun identify() {
        val show = showCaller(context, device.deviceId)
        val share = sharesContacts()
        if (!show && !share) return
        val title = dialerNotification?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.let(CallTracker::plain)?.trim()
            ?.takeIf(String::isNotEmpty) ?: return
        val number = CallTracker.dialable(title)
        val uid = if (share) ContactsPlugin.uidFor(context, number, title.takeIf { number == null }) else null
        if (show) {
            tracker.annotate(if (number != null) contactName(number) else title, number, uid)
        } else {
            tracker.annotate(null, null, uid)
        }
    }

    private fun sharesContacts() = ContactsPlugin.sharesWith(context, device)

    private fun contactName(number: String): String? {
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    /** The heads-up that the desk took the call (so the phone ringing then stopping is explained). */
    @SuppressLint("MissingPermission")
    private fun notifyAnswered() {
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.telephony_answered_on, device.name))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setTimeoutAfter(6_000)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify("answered:${device.deviceId}", 7330, notification) }
    }

    companion object {
        private const val TAG = "DeskLink/Telephony"
        private const val KEY_SHOW_CALLER = "telephony_show_caller"
        private const val KEY_LIVE_CALL = "telephony_live_call"

        /** Words of the dialer's Decline / Hang up buttons (AOSP and the common OEM dialers). */
        private val DECLINE_WORDS = listOf("decline", "hang up", "end", "reject", "dismiss")

        /** "Show caller on <desk>": off unless the person turned it on (T3). */
        fun showCaller(context: Context, deviceId: String): Boolean =
            TrustedDevices.getDeviceSettings(context, deviceId).getBoolean(KEY_SHOW_CALLER, false)

        fun setShowCaller(context: Context, deviceId: String, on: Boolean) =
            TrustedDevices.getDeviceSettings(context, deviceId).edit { putBoolean(KEY_SHOW_CALLER, on) }
    }
}
