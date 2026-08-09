/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.session

import org.desklink.mobile.transport.DisconnectReason
import org.desklink.mobile.transport.SessionTransport

/**
 * Mutable state for one logical device. The object is synchronized internally;
 * callers never need to hold the manager lock while closing a transport.
 */
class DeviceSession internal constructor(
    val sessionId: Long,
    val deviceId: String,
    initialPairingState: PairingState,
) {
    private val lock = Any()

    @Volatile
    var pairingState: PairingState = initialPairingState
        private set

    @Volatile
    var connectionGeneration: Long = 0
        private set

    @Volatile
    var state: SessionState = SessionState.DISCONNECTED
        private set

    @Volatile
    var activeTransport: SessionTransport? = null
        private set

    @Volatile
    var reconnectAttempt: Int = 0
        private set

    @Volatile
    var lastDisconnectReason: DisconnectReason? = null
        private set

    internal fun replaceTransport(newTransport: SessionTransport): Replacement {
        synchronized(lock) {
            check(state != SessionState.TERMINATED) { "Session $deviceId is terminated" }
            val oldTransport = activeTransport
            connectionGeneration += 1
            activeTransport = newTransport
            state = SessionState.ACTIVE
            reconnectAttempt = 0
            lastDisconnectReason = null
            return Replacement(
                SessionBinding(deviceId, sessionId, connectionGeneration, newTransport),
                oldTransport,
            )
        }
    }

    internal fun isCurrent(binding: SessionBinding): Boolean = synchronized(lock) {
        state == SessionState.ACTIVE &&
            connectionGeneration == binding.connectionGeneration &&
            activeTransport?.transportId == binding.transport.transportId
    }

    internal fun disconnectIfCurrent(binding: SessionBinding, reason: DisconnectReason): Boolean =
        synchronized(lock) {
            if (!isCurrentLocked(binding)) return@synchronized false
            activeTransport = null
            state = SessionState.DISCONNECTED
            lastDisconnectReason = reason
            true
        }

    internal fun terminate(): SessionTransport? = synchronized(lock) {
        if (state == SessionState.TERMINATED) return@synchronized null
        val oldTransport = activeTransport
        activeTransport = null
        state = SessionState.TERMINATED
        lastDisconnectReason = DisconnectReason.SERVICE_STOPPED
        oldTransport
    }

    internal fun updatePairingState(newState: PairingState) {
        synchronized(lock) {
            if (state != SessionState.TERMINATED) pairingState = newState
        }
    }

    /**
     * Revokes feature authorization but retains the LAN bootstrap transport
     * for pair=false delivery and a later re-pair.
     */
    internal fun revokePairing() {
        synchronized(lock) {
            if (state != SessionState.TERMINATED) {
                pairingState = PairingState.NOT_PAIRED
                reconnectAttempt = 0
                lastDisconnectReason = DisconnectReason.USER_REQUESTED
            }
        }
    }

    internal fun markReconnectAttempt(attempt: Int) {
        synchronized(lock) {
            if (state != SessionState.TERMINATED) {
                reconnectAttempt = attempt
                state = SessionState.DISCONNECTED
            }
        }
    }

    private fun isCurrentLocked(binding: SessionBinding): Boolean =
        state == SessionState.ACTIVE &&
            connectionGeneration == binding.connectionGeneration &&
            activeTransport?.transportId == binding.transport.transportId

    internal data class Replacement(
        val binding: SessionBinding,
        val replacedTransport: SessionTransport?,
    )
}
