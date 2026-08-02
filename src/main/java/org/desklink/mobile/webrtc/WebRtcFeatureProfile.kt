/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

/**
 * The production-safe first DeskLink WebRTC feature profile.
 *
 * Paired feature traffic has no LAN fallback.  Keep this list limited to the
 * three capabilities that are exercised by the initial cross-device handover.
 * File bytes use the dedicated authenticated file channels; this packet type
 * merely grants the Share plugin permission to offer a transfer. Every later
 * feature must be added here only with its own end-to-end tests.
 */
object WebRtcFeatureProfile {
    private val enabled = setOf(
        DeskLinkProtocol.PACKET_TYPE_PING,
        DeskLinkProtocol.PACKET_TYPE_FINDMYPHONE_REQUEST,
        DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST,
    )

    fun allows(packetType: String): Boolean = packetType in enabled

    fun capabilities(advertised: Set<String>?): List<String> = advertised
        .orEmpty()
        .filter(::allows)
        .sorted()
}
