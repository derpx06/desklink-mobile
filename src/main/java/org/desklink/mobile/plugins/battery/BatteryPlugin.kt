/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.battery

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.annotation.VisibleForTesting
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.R
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

@LoadablePlugin
class BatteryPlugin : Plugin() {
    private val batteryInfo = NetworkPacket(PACKET_TYPE_BATTERY)

    /** W14: what the computer told the phone about Do Not Disturb sync, and the guards that keep the two ends from echoing. */
    private val dndLoop = DndSync.Loop()
    @Volatile private var dndSyncOn = false

    /** The state packet: the charge, and `dnd` / `dndCanSet` only while the computer's Do Not Disturb switch is on. */
    private fun outgoing(): NetworkPacket = NetworkPacket(PACKET_TYPE_BATTERY).also { np ->
        np["currentCharge"] = batteryInfo.getInt("currentCharge")
        np["isCharging"] = batteryInfo.getBoolean("isCharging")
        np["thresholdEvent"] = batteryInfo.getInt("thresholdEvent")
        np["chargeTimeRemaining"] = batteryInfo.getLong("chargeTimeRemaining", -1)
        DndSync.keys(dndSyncOn, DndSync.phoneDnd(context), DndSync.canSet(context)).forEach { (k, v) -> np[k] = v }
    }

    /** The computer's `dndSync` / `dnd` arrived (every settings report): report ours, and follow its change when allowed. */
    fun deskDndChanged(syncOn: Boolean, deskDnd: Boolean?) {
        val was = dndSyncOn
        dndSyncOn = syncOn
        if (!syncOn) {
            dndLoop.reset()
            DndSync.Restore.restoreNow(context, device.deviceId) // what DeskLink changed is put back
            if (was) device.sendPacket(outgoing()) // the keys go away: the computer stops following this phone
            return
        }
        // The computer's Do Not Disturb is off and DeskLink still holds a note of putting the phone back (it ended while the link was
        // down, or this process restarted): put it back now. The first report is otherwise ignored as an "off", and the note would keep
        // the phone in Do Not Disturb. It changes nothing if the person changed the filter since.
        if (deskDnd == false && DndSync.Restore.has(context, device.deviceId)) DndSync.Restore.restoreNow(context, device.deviceId)
        val toSet = dndLoop.fromDesk(deskDnd, DndSync.phoneDnd(context), DndSync.canSet(context))
        if (toSet != null) DndSync.apply(context, device.deviceId, toSet)
        if (!was) device.sendPacket(outgoing()) // the first report after the switch turned on
    }

    private val dndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!dndSyncOn) return
            val granted = intent.action == NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED
            // Access given after the computer's value arrived: that value is applied now.
            if (granted) dndLoop.reapply(DndSync.phoneDnd(context), DndSync.canSet(context))?.let { DndSync.apply(context, device.deviceId, it) }
            if (granted || dndLoop.localChange(DndSync.phoneDnd(context))) device.sendPacket(outgoing())
        }
    }

    /**
     * The latest battery information about the linked device. Will be null if the linked device
     * has not sent us any such information yet.
     *
     *
     * See [DeviceBatteryInfo] for info on which fields we expect to find.
     *
     *
     * @return the most recent packet received from the remote device. May be null
     */
    var remoteBatteryInfo: DeviceBatteryInfo? = null
        private set

    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_battery)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_battery_desc)

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        var isLowBattery = false
        var thresholdEvent = THRESHOLD_EVENT_NONE

        override fun onReceive(context: Context, batteryIntent: Intent) {
            if (batteryIntent.action != Intent.ACTION_BATTERY_CHANGED) {
                thresholdEvent = when (batteryIntent.action) {
                    Intent.ACTION_BATTERY_LOW if !isLowBattery -> THRESHOLD_EVENT_BATTERY_LOW
                    else -> THRESHOLD_EVENT_NONE
                }

                isLowBattery = when (batteryIntent.action) {
                    Intent.ACTION_BATTERY_OKAY -> false
                    Intent.ACTION_BATTERY_LOW -> true
                    else -> isLowBattery
                }

                // Intents with action ACTION_BATTERY_OKAY and ACTION_BATTERY_LOW do not have extras
                // Wait for next ACTION_BATTERY_CHANGED (which should come after) to send the threshold event.
                return
            }

            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

            val currentCharge = level * 100 / scale
            val isCharging = 0 != plugged
            val chargeTime = chargeTimeRemaining(isCharging, rawChargeTimeRemaining(context))

            if (isCharging != batteryInfo.getBoolean("isCharging") || currentCharge != batteryInfo.getInt("currentCharge") || thresholdEvent != batteryInfo.getInt(
                    "thresholdEvent"
                ) || chargeTime != batteryInfo.getLong("chargeTimeRemaining", -1)
            ) {
                batteryInfo["currentCharge"] = currentCharge
                batteryInfo["isCharging"] = isCharging
                batteryInfo["thresholdEvent"] = thresholdEvent
                batteryInfo["chargeTimeRemaining"] = chargeTime
                // The widget shows this phone's battery too.
                org.desklink.mobile.ui.surfaces.DeskSurfaces.refresh(context)
                device.sendPacket(outgoing())

                // We just send a possible threshold event so reset it so we not create notifications on each change
                thresholdEvent = THRESHOLD_EVENT_NONE
            }
        }
    }

    override fun onCreate(): Boolean {
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        val currentState = context.registerReceiver(receiver, intentFilter)
        receiver.onReceive(context, currentState)
        DndSync.Restore.cancel(device.deviceId) // the link is back within the minute: nothing is put back
        dndSyncOn = device.getPlugin(org.desklink.mobile.plugins.desksettings.DeskSettingsPlugin::class.java)?.settings?.dndSync == true
        context.registerReceiver(dndReceiver, IntentFilter().apply {
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
        })
        return true
    }

    /** Feature-ready: the current state, even when nothing changed since the last send (S2). */
    override fun onFeatureReady() {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { receiver.onReceive(context, it) }
        device.sendPacket(outgoing())
    }

    /** Turned off: the desk stops showing this phone's battery. */
    override fun onTurnedOff() {
        device.sendPacket(turnedOffPacket())
    }

    override fun onDestroy() {
        // It's okay to call this only once, even though we registered it for two filters
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { context.unregisterReceiver(dndReceiver) }
        DndSync.Restore.restoreLater(context, device.deviceId) // gone for a minute: the phone's own setting comes back
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (PACKET_TYPE_BATTERY != np.type) {
            return false
        }
        remoteBatteryInfo = DeviceBatteryInfo.fromPacket(np)
        device.onPluginsChanged()
        return true
    }

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_BATTERY)

    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_BATTERY)

    companion object {
        const val PACKET_TYPE_BATTERY = DeskLinkProtocol.PACKET_TYPE_BATTERY

        // keep these fields in sync with kdeconnect-kded:BatteryPlugin.h:ThresholdBatteryEvent
        private const val THRESHOLD_EVENT_NONE = 0
        private const val THRESHOLD_EVENT_BATTERY_LOW = 1

        /**
         * `chargeTimeRemaining`: ms until full, to the minute (the platform's estimate moves every
         * second and would resend the packet each time), or -1 when unknown or not charging.
         */
        fun chargeTimeRemaining(isCharging: Boolean, rawMillis: Long): Long =
            if (!isCharging || rawMillis <= 0) -1 else rawMillis / 60_000 * 60_000

        /** The platform's estimate (Android 9+), or -1. */
        private fun rawChargeTimeRemaining(context: Context): Long =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                runCatching { context.getSystemService(BatteryManager::class.java)?.computeChargeTimeRemaining() }.getOrNull() ?: -1
            } else {
                -1
            }

        /** `currentCharge: -1`: the desk clears the battery it shows (contract S2). */
        fun turnedOffPacket() = NetworkPacket(PACKET_TYPE_BATTERY).apply {
            this["currentCharge"] = -1
            this["isCharging"] = false
            this["thresholdEvent"] = THRESHOLD_EVENT_NONE
        }

        fun isLowBattery(info: DeviceBatteryInfo): Boolean {
            return info.thresholdEvent == THRESHOLD_EVENT_BATTERY_LOW
        }
    }
}
