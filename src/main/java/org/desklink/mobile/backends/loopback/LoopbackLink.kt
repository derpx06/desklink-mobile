/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.desklink.mobile.backends.loopback

import android.content.Context
import androidx.annotation.WorkerThread
import org.desklink.mobile.backends.BaseLink
import org.desklink.mobile.backends.BaseLinkProvider
import org.desklink.mobile.Device
import org.desklink.mobile.DeviceInfo
import org.desklink.mobile.helpers.DeviceHelper.getDeviceInfo
import org.desklink.mobile.NetworkPacket

class LoopbackLink : BaseLink {
    constructor(context: Context, linkProvider: BaseLinkProvider) : super(context, linkProvider)

    override fun getName(): String = "LoopbackLink"
    override fun getDeviceInfo(): DeviceInfo = getDeviceInfo(context)

    @WorkerThread
    override fun sendPacket(packet: NetworkPacket, callback: Device.SendPacketStatusCallback, sendPayloadFromSameThread: Boolean): Boolean {
        packetReceived(packet)
        if (packet.hasPayload()) {
            callback.onPayloadProgressChanged(0)
            packet.payload = packet.payload // this triggers logic in the setter
            callback.onPayloadProgressChanged(100)
        }
        callback.onSuccess()
        return true
    }
}
