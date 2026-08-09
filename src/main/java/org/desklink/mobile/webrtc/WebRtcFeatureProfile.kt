/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

/**
 * The production DeskLink WebRTC feature profile.
 *
 * Paired feature traffic has no LAN fallback. Keep this first production slice
 * limited to the two handlers exercised by the current cross-platform
 * handshake. Other packet constants remain available for later migrations but
 * are deliberately soft-disabled from capability advertisement.
 */
object WebRtcFeatureProfile {
    private val enabled = setOf(
        DeskLinkProtocol.PACKET_TYPE_PING,
        DeskLinkProtocol.PACKET_TYPE_FINDMYPHONE_REQUEST,
    )

    fun allows(packetType: String): Boolean = packetType in enabled

    fun capabilities(advertised: Set<String>?): List<String> = advertised
        .orEmpty()
        .filter(::allows)
        .sorted()
}
