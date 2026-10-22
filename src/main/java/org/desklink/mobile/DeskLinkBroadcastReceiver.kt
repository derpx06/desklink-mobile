/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.desklink.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log

class DeskLinkBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Log.e("DeskLink", "Broadcast event: "+intent.getAction());

        val action = intent.action

        when (action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i("DeskLink", "MyUpdateReceiver")
                BackgroundService.Start(context)
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i("DeskLink", "DeskLinkBroadcastReceiver")
                // Settings → Connect on startup; on unless the person turned it off.
                if (!androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean(org.desklink.mobile.ui.compose.screen.settings.KEY_CONNECT_ON_STARTUP, true)
                ) {
                    return
                }
                try {
                    BackgroundService.Start(context)
                } catch (e: IllegalStateException) { // To catch ForegroundServiceStartNotAllowedException
                    Log.w("BroadcastReceiver", "Couldn't start the foreground service.", e)
                }
            }

            WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION, WifiManager.WIFI_STATE_CHANGED_ACTION, ConnectivityManager.CONNECTIVITY_ACTION -> {
                Log.i("DeskLink", "Connection state changed, trying to connect")
                BackgroundService.ForceRefreshConnections(context)
            }

            Intent.ACTION_SCREEN_ON -> {
                // First: the desk stops treating this phone as dozing (contract §11), and every
                // live peer is probed, rebuilt if it does not answer in 3 s (B2).
                sendDozeState()
                org.desklink.mobile.helpers.ThreadHelper.execute { runCatching { DeskLinkApplication.getInstance().onScreenOn() } }
                try {
                    BackgroundService.ForceRefreshConnections(context)
                } catch (e: IllegalStateException) { // To catch ForegroundServiceStartNotAllowedException
                    Log.w("BroadcastReceiver", "Couldn't start the foreground service.", e)
                }
            }

            Intent.ACTION_SCREEN_OFF -> {
                sendDozeState()
                org.desklink.mobile.helpers.ThreadHelper.execute { runCatching { DeskLinkApplication.getInstance().onScreenOff() } }
            }

            else -> Log.i("BroadcastReceiver", "Ignoring broadcast event: ${intent.action}")
        }
    }

    private fun sendDozeState() {
        // Off the main thread: the LAN write may block.
        org.desklink.mobile.helpers.ThreadHelper.execute {
            runCatching { DeskLinkApplication.getInstance().devices.values.forEach { it.sendDozeState() } }
                .onFailure { Log.d("BroadcastReceiver", "Could not report the screen state", it) }
        }
    }
}
