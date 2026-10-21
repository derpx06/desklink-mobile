/*
 * SPDX-FileCopyrightText: 2026 Saul Cintero Chocarro <scintero@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.extensions.device

import org.desklink.mobile.Device
import org.desklink.mobile.PairingHandler
import org.desklink.mobile.plugins.battery.BatteryPlugin
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.model.device.DeviceUiModel
import org.desklink.mobile.R

fun Device.toUiModel(): DeviceUiModel {
    val battery = getPlugin(BatteryPlugin::class.java)?.remoteBatteryInfo
    return DeviceUiModel(
        id = deviceId,
        icon = iconDrawable,
        name = name,
        summaryRes = if (compareProtocolVersion() > 0) R.string.protocol_version_newer else 0,
        isReachable = isReachable,
        isPaired = isPaired,
        connection = deviceConnection(isReachable, hasActiveWebRtcFeatureTransport, hasLanLink, relayLink != null),
        batteryPercent = battery?.currentCharge,
        charging = battery?.isCharging == true,
        wantsToPair = pairStatus == PairingHandler.PairState.RequestedByPeer,
    )
}

/** A LAN link wins over the relay: when both exist the handover prefers the LAN. */
fun deviceConnection(reachable: Boolean, featuresReady: Boolean, lan: Boolean, relay: Boolean): DeviceConnection = when {
    !reachable -> DeviceConnection.OFFLINE
    !featuresReady -> DeviceConnection.CONNECTING
    lan -> DeviceConnection.LOCAL
    relay -> DeviceConnection.AWAY
    else -> DeviceConnection.CONNECTED
}
