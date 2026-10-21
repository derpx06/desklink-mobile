/*
 * SPDX-FileCopyrightText: 2026 Saul Cintero Chocarro <scintero@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.extensions.device

import org.desklink.mobile.webrtc.WebRtcConnectionPath

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
        connection = deviceConnection(isReachable, hasActiveWebRtcFeatureTransport, connectionPath),
        batteryPercent = battery?.currentCharge,
        charging = battery?.isCharging == true,
        wantsToPair = pairStatus == PairingHandler.PairState.RequestedByPeer,
    )
}

/**
 * From the live feature transport and its ICE path, as the desk decides "on this network": LOCAL
 * only for a LAN pair, AWAY for a direct or relayed one, plain CONNECTED until the path is known.
 * The transport alive means connected even while the bootstrap link is being re-established.
 */
fun deviceConnection(reachable: Boolean, featuresReady: Boolean, path: WebRtcConnectionPath?): DeviceConnection = when {
    featuresReady -> when (path) {
        WebRtcConnectionPath.LAN -> DeviceConnection.LOCAL
        WebRtcConnectionPath.DIRECT, WebRtcConnectionPath.TURN -> DeviceConnection.AWAY
        null -> DeviceConnection.CONNECTED
    }
    !reachable -> DeviceConnection.OFFLINE
    else -> DeviceConnection.CONNECTING
}
