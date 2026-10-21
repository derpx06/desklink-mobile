/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import java.util.concurrent.ConcurrentHashMap

/**
 * The DeskLink feature profile: the packet types this application carries over the authenticated
 * WebRTC feature bridge.
 *
 * The desktop keeps its own list (`WEBRTC_PROFILE` in `features/registry.rs`). They need not be
 * identical: after the handover the types used with a peer are the **intersection** of the two.
 * A type is sent to a peer only when the peer's handover listed it as incoming, and accepted from a
 * peer only when its handover listed it as outgoing ([PeerProfile]). So updating one application
 * before the other loses only what the older one lacks, never ping and the rest. Both lists are
 * still pinned by tests, so a one-sided edit is noticed.
 *
 * `desklink.screen.*` is advertised so this application loads its screen plugin, but those types
 * never cross the bridge: the screen control plane is `desklink.remote-session.v1` on the control
 * channel.
 */
object WebRtcFeatureProfile {
    private val enabled = setOf(
        DeskLinkProtocol.PACKET_TYPE_BATTERY,
        DeskLinkProtocol.PACKET_TYPE_CLIPBOARD,
        DeskLinkProtocol.PACKET_TYPE_CLIPBOARD_CONNECT,
        DeskLinkProtocol.PACKET_TYPE_DIGITIZER,
        DeskLinkProtocol.PACKET_TYPE_DIGITIZER_SESSION,
        DeskLinkProtocol.PACKET_TYPE_FINDMYPHONE_REQUEST,
        DeskLinkProtocol.PACKET_TYPE_HANDOFF,
        DeskLinkProtocol.PACKET_TYPE_LOCK,
        DeskLinkProtocol.PACKET_TYPE_LOCK_REQUEST,
        DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST,
        // "Type on <phone>" (C2): whether a field has focus here.
        DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE,
        DeskLinkProtocol.PACKET_TYPE_MPRIS,
        DeskLinkProtocol.PACKET_TYPE_MPRIS_REQUEST,
        DeskLinkProtocol.PACKET_TYPE_NOTIFICATION,
        DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_ACTION,
        DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_REPLY,
        DeskLinkProtocol.PACKET_TYPE_NOTIFICATION_REQUEST,
        DeskLinkProtocol.PACKET_TYPE_PING,
        DeskLinkProtocol.PACKET_TYPE_RUNCOMMAND,
        DeskLinkProtocol.PACKET_TYPE_RUNCOMMAND_REQUEST,
        DeskLinkProtocol.PACKET_TYPE_SETTINGS,
        DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST,
        DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST,
        // Calls (contract T): the phone's call state, and the desk's answer/decline/dial.
        DeskLinkProtocol.PACKET_TYPE_TELEPHONY,
        DeskLinkProtocol.PACKET_TYPE_TELEPHONY_REQUEST,
        DeskLinkProtocol.PACKET_TYPE_UNLOCK_REQUEST,
    )

    fun allows(packetType: String): Boolean = packetType in enabled

    fun capabilities(advertised: Collection<String>?): List<String> = advertised
        .orEmpty()
        .filter(::allows)
        .distinct()
        .sorted()

    /** What a peer confirmed in its handover, filtered by this profile. */
    data class PeerProfile(val incoming: Set<String>, val outgoing: Set<String>)

    private val peers = ConcurrentHashMap<String, PeerProfile>()

    /**
     * A peer's handover list may stand for its identity when, both filtered by this profile, it
     * claims nothing the authenticated identity did not. It may claim less (an older or newer build).
     */
    fun handoverListIsConsistent(handover: Collection<String>, identity: Collection<String>?): Boolean {
        val allowed = capabilities(identity).toSet()
        return capabilities(handover).all { it in allowed }
    }

    /** Records what [deviceId] confirmed in its handover; from then on only the intersection is used. */
    fun recordPeer(deviceId: String, incoming: Collection<String>, outgoing: Collection<String>) {
        peers[deviceId] = PeerProfile(capabilities(incoming).toSet(), capabilities(outgoing).toSet())
    }

    fun forgetPeer(deviceId: String) {
        peers.remove(deviceId)
    }

    fun peer(deviceId: String): PeerProfile? = peers[deviceId]

    /**
     * Whether [packetType] may go to [deviceId]: in this profile, and in what the peer said it
     * receives. Before any handover only this profile applies (nothing is sent before it anyway).
     */
    fun peerAccepts(deviceId: String, packetType: String): Boolean = allows(packetType, peers[deviceId]?.incoming)

    /** Whether [packetType] from [deviceId] is accepted: in this profile, and in what the peer said it sends. */
    fun peerSends(deviceId: String, packetType: String): Boolean = allows(packetType, peers[deviceId]?.outgoing)

    internal fun allows(packetType: String, peerList: Set<String>?): Boolean =
        allows(packetType) && (peerList == null || packetType in peerList)
}
