/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.backends

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

/**
 * Which carrier a pairing may travel over, and the one rule that decides it. Mirrors the desktop's
 * `pairing_transport.rs`.
 *
 * Shipped pairing runs over the LAN only. A relay link is a bootstrap link and carries signaling,
 * heartbeats and the `pair:false` unpair notice, never a pairing (docs/RELAY.md section 3).
 *
 * This is the seam for pairing away from the LAN later (docs/PAIRING-OFF-LAN.md): a new carrier is
 * one new [BaseLink] subclass that returns its own [Kind] from [BaseLink.pairingTransport] and gets
 * its arm in [packetMayCross]; [org.desklink.mobile.PairingHandler] and the trust record are not
 * touched. It stays unreachable until [OFF_LAN_PAIRING_COMPILED] is turned on in source and the
 * hidden `off_lan_pairing` preference is true ([offLanPairingEnabled]).
 */
object PairingTransport {
    /** Compile-time switch: `false` in every shipped build, so no preference can open the path. */
    const val OFF_LAN_PAIRING_COMPILED = false

    /** The hidden preference (default preferences; no settings row). */
    const val OFF_LAN_PREFERENCE = "off_lan_pairing"

    enum class Kind { LAN, RELAY }

    /** The hidden preference's value, loaded once at start-up by the application. */
    @Volatile
    @JvmStatic
    var requested: Boolean = false

    /** Both switches, as a pure function so a test can try every combination. */
    @JvmStatic
    fun flagOpen(compiled: Boolean, requested: Boolean): Boolean = compiled && requested

    @JvmStatic
    fun offLanPairingEnabled(): Boolean = flagOpen(OFF_LAN_PAIRING_COMPILED, requested)

    /** Whether [packet] may cross a link of [kind]; [offLan] is [offLanPairingEnabled] unless a test says otherwise. */
    @JvmStatic
    @JvmOverloads
    fun packetMayCross(kind: Kind, packet: NetworkPacket, offLan: Boolean = offLanPairingEnabled()): Boolean = when (kind) {
        Kind.LAN -> true
        Kind.RELAY -> when (packet.type) {
            DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1, DeskLinkProtocol.PACKET_TYPE_HEARTBEAT -> true
            DeskLinkProtocol.PACKET_TYPE_PAIR -> offLan || (packet.isBoolean("pair") && !packet.getBoolean("pair", true))
            DeskLinkProtocol.PACKET_TYPE_PAIR_QR -> offLan
            else -> false
        }
    }
}
