/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.automations

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.helpers.ThreadHelper
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

/** F7 with one desk: the rule index on every feature-ready, and the desk's packets to [Automations]. */
@LoadablePlugin
class AutomationsPlugin : Plugin() {
    override val displayName: String
        get() = context.getString(R.string.automations_title)

    override val description: String
        get() = context.getString(R.string.automations_subtitle)

    override val supportedPacketTypes: Array<String> = arrayOf(DeskLinkProtocol.PACKET_TYPE_AUTOMATIONS)

    override val outgoingPacketTypes: Array<String> = supportedPacketTypes

    override fun onFeatureReady() {
        ThreadHelper.execute { Automations.index(context, device) }
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != DeskLinkProtocol.PACKET_TYPE_AUTOMATIONS) return false
        ThreadHelper.execute { Automations.onPacket(context, device, np) }
        return true
    }
}
