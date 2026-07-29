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

    fun close(reason: DisconnectReason)
}
