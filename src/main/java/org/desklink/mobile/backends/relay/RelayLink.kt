/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.backends.relay

import android.content.Context
import org.desklink.mobile.Device
import org.desklink.mobile.DeviceInfo
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.backends.BaseLink
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import java.io.IOException

/**
 * A verified end-to-end relay link to one paired device, as a bootstrap link
 * like the LAN TLS link: signed signaling, heartbeats and unpair notices only
 * (docs/RELAY.md §3). Features never travel here.
 *
 * Created only after the handshake proved the peer holds the key of its
 * *stored* pairing certificate, and [getDeviceInfo] carries that stored
 * certificate — so `Device.addLink`'s pinning check holds by construction.
 */
class RelayLink internal constructor(
    context: Context,
    private val provider: RelayLinkProvider,
    /** The peer's relay address. */
    internal val fp: String,
    private val deviceInfo: DeviceInfo,
) : BaseLink(context, provider) {
    @Volatile
    internal var closed = false

    override fun getName(): String = "RelayLink"

    override fun getDeviceInfo(): DeviceInfo = deviceInfo

    override fun disconnect() = provider.dropLink(this)

    override fun sendPacket(np: NetworkPacket, callback: Device.SendPacketStatusCallback, sendPayloadFromSameThread: Boolean): Boolean {
        if (np.hasPayload() || !allowedOnRelay(np)) {
            np.payload?.close()
            callback.onFailure(IOException("${np.type} may not travel over a relay link"))
            return false
        }
        if (!provider.send(this, np.serialize().trimEnd('\n').toByteArray(Charsets.UTF_8))) {
            callback.onFailure(IOException("The relay link is closed"))
            return false
        }
        callback.onSuccess()
        return true
    }

    companion object {
        /** Whether [packet] may cross a relay link at all, in either direction. */
        @JvmStatic
        fun allowedOnRelay(packet: NetworkPacket): Boolean = when (packet.type) {
            DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1, DeskLinkProtocol.PACKET_TYPE_HEARTBEAT -> true
            // Only an unpair notice: pairing itself never happens off the LAN.
            DeskLinkProtocol.PACKET_TYPE_PAIR -> packet.has("pair") && !packet.getBoolean("pair", true)
            else -> false
        }
    }
}
