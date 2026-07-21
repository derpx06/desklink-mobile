/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.lockdevice

import android.app.KeyguardManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.fragment.app.DialogFragment
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.plugins.mousereceiver.MouseReceiverService
import org.desklink.mobile.ui.MainActivity
import org.desklink.mobile.ui.StartActivityAlertDialogFragment

@LoadablePlugin
@RequiresApi(Build.VERSION_CODES.P)
class LockDevicePlugin : Plugin() {
    override val displayName: String
        get() = context.getString(R.string.pref_plugin_lockdevice)

    override val description: String
        get() = context.getString(R.string.pref_plugin_lockdevice_desc)

    override val minSdk: Int
        get() = Build.VERSION_CODES.P

    override fun checkRequiredPermissions(): Boolean = MouseReceiverService.instance != null

    override fun loadPluginWhenRequiredPermissionsMissing(): Boolean = true

    override val permissionExplanationDialog: DialogFragment
        get() = StartActivityAlertDialogFragment.Builder()
            .setTitle(R.string.pref_plugin_lockdevice)
            .setMessage(R.string.lockdevice_no_permissions)
            .setPositiveButton(R.string.open_settings)
            .setNegativeButton(R.string.cancel)
            .setIntentAction(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .setStartForResult(true)
            .setRequestCode(MainActivity.RESULT_NEEDS_RELOAD)
            .create()

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != PACKET_TYPE_LOCK_REQUEST && np.type != PACKET_TYPE_LOCK) {
            Log.e(LOG_TAG, "Invalid packet type for LockDevicePlugin: ${np.type}")
            return false
        }

        if (np.has("requestLocked")) {
            sendLockState()
        }

        val requestedLockState = when {
            np.has("setLocked") -> np.getBoolean("setLocked")
            np.has("isLocked") -> np.getBoolean("isLocked")
            else -> null
        }

        return when (requestedLockState) {
            true -> {
                val locked = MouseReceiverService.powerButton()
                sendLockResult(locked)
                true
            }
            false -> {
                sendLockResult(false)
                false
            }
            null -> np.has("requestLocked")
        }
    }

    override fun getUiMenuEntries(): List<PluginUiMenuEntry> = listOf(
        PluginUiMenuEntry(context.getString(R.string.lock_remote_device)) {
            val np = NetworkPacket(PACKET_TYPE_LOCK_REQUEST)
            np["setLocked"] = true
            device.sendPacket(np)
        }
    )

    private fun sendLockResult(result: Boolean) {
        val resultPacket = NetworkPacket(PACKET_TYPE_LOCK)
        resultPacket["lockResult"] = result
        device.sendPacket(resultPacket)
        sendLockState()
    }

    private fun sendLockState() {
        val statePacket = NetworkPacket(PACKET_TYPE_LOCK)
        statePacket["isLocked"] = isDeviceLocked()
        device.sendPacket(statePacket)
    }

    private fun isDeviceLocked(): Boolean {
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        return keyguardManager?.isKeyguardLocked ?: false
    }

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_LOCK, PACKET_TYPE_LOCK_REQUEST)

    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_LOCK, PACKET_TYPE_LOCK_REQUEST)

    companion object {
        private const val LOG_TAG = "LockDevicePlugin"
        private const val PACKET_TYPE_LOCK = "desklink.lock"
        private const val PACKET_TYPE_LOCK_REQUEST = "desklink.lock.request"
    }
}
