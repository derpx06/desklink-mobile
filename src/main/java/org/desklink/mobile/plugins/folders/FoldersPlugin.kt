/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.folders

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

/** F8 with one desk: link up on every feature-ready, and the desk's packets to [BucketEngine]. */
@LoadablePlugin
class FoldersPlugin : Plugin() {
    override val displayName: String
        get() = context.getString(R.string.bucket_title)

    override val description: String
        get() = context.getString(R.string.bucket_subtitle_short)

    override val supportedPacketTypes: Array<String> = arrayOf(DeskLinkProtocol.PACKET_TYPE_FOLDERS)

    override val outgoingPacketTypes: Array<String> = supportedPacketTypes

    override fun onCreate(): Boolean {
        BucketEngine.start(context)
        return true
    }

    override fun onFeatureReady() = BucketEngine.link(device.deviceId, up = true)

    override fun onDestroy() = BucketEngine.link(device.deviceId, up = false)

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != DeskLinkProtocol.PACKET_TYPE_FOLDERS) return false
        BucketEngine.onPacket(device.deviceId, np)
        return true
    }
}
