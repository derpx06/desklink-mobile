/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.transport

import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.backends.lan.LanLink
import org.json.JSONException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * Adapter around the existing authenticated Java LAN link.
 *
 * This adapter only transports control packets for now. Payload jobs continue
 * to use the existing NetworkPacket/LanLink path until they can be migrated
 * without changing payload framing or transfer behavior.
 */
class LegacyLanTransport(
    private val link: LanLink,
) : SessionTransport {
    override val transportId: String =
        "legacy-lan:${link.deviceId}:${System.identityHashCode(link)}"

    override val transportType: TransportType = TransportType.LEGACY_LAN

    private val stateRef = AtomicReference(TransportState.CONNECTED)

    override val state: TransportState
        get() = stateRef.get()

    override fun send(channel: LogicalChannel, payload: ByteArray, callback: TransportCallback) {
        if (channel != LogicalChannel.CONTROL) {
            callback.onFailure(
                TransportError(
                    TransportErrorCode.UNSUPPORTED_CHANNEL,
                    "Legacy LAN transport does not own the $channel channel yet",
                ),
            )
            return
        }
        if (state != TransportState.CONNECTED) {
            callback.onFailure(
                TransportError(TransportErrorCode.CLOSED, "Legacy LAN transport is not connected"),
            )
            return
        }

        val packet = try {
            NetworkPacket.unserialize(String(payload, StandardCharsets.UTF_8))
        } catch (error: JSONException) {
            callback.onFailure(
                TransportError(TransportErrorCode.INVALID_PACKET, "Invalid control packet", error),
            )
            return
        }

        try {
            val sent = link.sendPacket(
                packet,
                object : Device.SendPacketStatusCallback() {
                    override fun onSuccess() = callback.onSuccess()

                    override fun onFailure(e: Throwable) {
                        callback.onFailure(
                            TransportError(TransportErrorCode.SEND_FAILED, "Control packet send failed", e),
                        )
                    }

                    override fun onPayloadProgressChanged(percent: Int) {
                        callback.onProgress(percent)
                    }
                },
                false,
            )
            if (!sent) {
                callback.onFailure(
                    TransportError(TransportErrorCode.SEND_FAILED, "LAN link rejected control packet"),
                )
            }
        } catch (error: Exception) {
            callback.onFailure(
                TransportError(TransportErrorCode.SEND_FAILED, "LAN link send failed", error),
            )
        }
    }

    override fun close(reason: DisconnectReason) {
        val previous = stateRef.getAndSet(TransportState.CLOSING)
        if (previous == TransportState.CLOSED || previous == TransportState.CLOSING) return
        runCatching { link.disconnect() }
        stateRef.set(TransportState.CLOSED)
    }
}
