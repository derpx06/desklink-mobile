/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.transport

/**
 * Java-compatible transport boundary for an authenticated device session.
 *
 * Packet and payload implementations remain free to use the existing Java
 * networking code behind this interface. New transports must not leak
 * suspend functions, Flow, or Kotlin-specific result types through it.
 */
interface SessionTransport {
    val transportId: String
    val transportType: TransportType
    val state: TransportState

    fun send(channel: LogicalChannel, payload: ByteArray, callback: TransportCallback)

    /** Reliable feature traffic. Legacy LAN maps this to its control packet path. */
    fun sendReliable(channel: LogicalChannel, payload: ByteArray, callback: TransportCallback) =
        send(channel, payload, callback)

    /** Realtime traffic may be dropped by WebRTC; legacy transports reject it. */
    fun sendRealtime(channel: LogicalChannel, payload: ByteArray, callback: TransportCallback) =
        send(channel, payload, callback)

    fun openLogicalStream(channel: LogicalChannel, callback: TransportCallback) {
        callback.onFailure(
            TransportError(
                TransportErrorCode.UNSUPPORTED_CHANNEL,
                "Logical streams are not available on this transport",
            ),
        )
    }

    fun requestMedia(kind: String, callback: TransportCallback) {
        callback.onFailure(
            TransportError(
                TransportErrorCode.UNSUPPORTED_CHANNEL,
                "Media is not available on this transport",
            ),
        )
    }

    fun stopMedia(kind: String) = Unit

    fun close(reason: DisconnectReason)
}
