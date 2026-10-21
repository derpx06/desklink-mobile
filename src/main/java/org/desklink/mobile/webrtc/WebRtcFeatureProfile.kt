/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

/**
 * The production DeskLink feature profile.
 *
 * Every entry has a working handler on this application *and* on the DeskLink
 * desktop client. This set must stay byte-identical to `WEBRTC_PROFILE` in the
 * desktop's `features/registry.rs` — **not** to `DESKTOP_INCOMING` union
 * `DESKTOP_OUTGOING`, which is a larger set. The advertised capabilities
 * include `desklink.screen.*` so that this application loads its screen plugin,
 * but those types never cross the feature bridge; the screen control plane is
 * `desklink.remote-session.v1` on the control channel.
 *
 * The handover compares each peer's filtered capability list for exact
 * equality, so a profile that differs by a single entry fails the handshake
 * outright and leaves both applications with no feature transport at all.
 *
 * Deliberately absent:
 *  - `desklink.mpris*`, `desklink.systemvolume*`, `desklink.runcommand*` —
 *    implemented here in the phone-controls-desktop direction only, and the
 *    desktop has no backend to answer them.
 *  - `desklink.screen*`, `desklink.sms*`, `desklink.contacts*`,
 *    `desklink.telephony*`, `desklink.digitizer*` — no desktop counterpart.
 */
object WebRtcFeatureProfile {
    private val enabled = setOf(
        DeskLinkProtocol.PACKET_TYPE_BATTERY,
        DeskLinkProtocol.PACKET_TYPE_CLIPBOARD,
        DeskLinkProtocol.PACKET_TYPE_CLIPBOARD_CONNECT,
        DeskLinkProtocol.PACKET_TYPE_FINDMYPHONE_REQUEST,
        DeskLinkProtocol.PACKET_TYPE_LOCK,
        DeskLinkProtocol.PACKET_TYPE_LOCK_REQUEST,
        DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST,
        DeskLinkProtocol.PACKET_TYPE_NOTIFICATION,
        DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_ACTION,
        DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_REPLY,
        DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_REQUEST,
        DeskLinkProtocol.PACKET_TYPE_PING,
        DeskLinkProtocol.PACKET_TYPE_SFTP,
        DeskLinkProtocol.PACKET_TYPE_SFTP_REQUEST,
        DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST,
    )

    fun allows(packetType: String): Boolean = packetType in enabled

    fun capabilities(advertised: Set<String>?): List<String> = advertised
        .orEmpty()
        .filter(::allows)
        .sorted()
}
