/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.automations

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import org.desklink.mobile.helpers.TrustedNetworkHelper
import org.desklink.mobile.plugins.notifications.NotificationReceiver

/**
 * F7 phone triggers, all pushed by Android (broadcasts and callbacks), none polled: charging,
 * battery level, Wi-Fi, notifications. Calls come from the telephony plugin and NFC tags from the
 * deep link (see [Automations.fire] callers). Started with the background service.
 */
object AutomationTriggers {
    private var started = false
    private var lastLevel = -1
    @Volatile private var lastSsid: String? = null

    private val power = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> Automations.fire(context, Event(Rules.TRIGGER_CHARGING_STARTED))
                Intent.ACTION_POWER_DISCONNECTED -> Automations.fire(context, Event(Rules.TRIGGER_CHARGING_STOPPED))
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
                    if (level < 0) return
                    val percent = level * 100 / scale
                    val previous = lastLevel
                    lastLevel = percent
                    // Only a drop can cross a "battery ≤ N" threshold.
                    if (previous >= 0 && percent < previous) {
                        Automations.fire(context, Event(Rules.TRIGGER_BATTERY_LOW, mapOf("level" to "$percent", "previous" to "$previous")))
                    }
                }
            }
        }
    }

    private val wifi = object : ConnectivityManager.NetworkCallback() {
        private var context: Context? = null

        fun attach(context: Context) = apply { this.context = context }

        override fun onAvailable(network: Network) {
            val context = context ?: return
            val ssid = runCatching { TrustedNetworkHelper(context).takeIf { it.hasPermissions }?.currentSSID }.getOrNull()
            lastSsid = ssid
            Automations.fire(context, Event(Rules.TRIGGER_WIFI_CONNECTED, ssid?.let { mapOf("ssid" to it) }.orEmpty()))
        }

        override fun onLost(network: Network) {
            val context = context ?: return
            Automations.fire(context, Event(Rules.TRIGGER_WIFI_DISCONNECTED, lastSsid?.let { mapOf("ssid" to it) }.orEmpty()))
            lastSsid = null
        }
    }

    private val notifications = object : NotificationReceiver.NotificationListener {
        var context: Context? = null

        override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
            val context = context ?: return
            if (statusBarNotification.isOngoing || statusBarNotification.packageName.startsWith("org.desklink.mobile")) return
            if (statusBarNotification.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            // Only the app: the notification's contents never reach a rule or a log.
            Automations.fire(context, Event(Rules.TRIGGER_NOTIFICATION, mapOf("package" to statusBarNotification.packageName)))
        }

        override fun onNotificationRemoved(statusBarNotification: StatusBarNotification) = Unit

        override fun onListenerConnected(service: NotificationReceiver) = Unit
    }

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        ContextCompat.registerReceiver(app, power, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                wifi.attach(app),
            )
        }
        if (NotificationReceiver.hasReadNotificationsPermission(app)) {
            notifications.context = app
            runCatching { NotificationReceiver.RunCommand(app) { it.addListener(notifications) } }
        }
    }
}
