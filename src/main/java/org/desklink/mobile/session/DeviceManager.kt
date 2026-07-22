/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.session

import org.desklink.mobile.transport.DisconnectReason
import org.desklink.mobile.transport.SessionTransport
import java.util.concurrent.atomic.AtomicLong

/**
 * The sole owner of live mobile device sessions.
 *
 * Registration is generation based: a newly authenticated transport replaces
 * the previous one, while callbacks from the old transport become stale.
 */
class DeviceManager {
    private val lock = Any()
    private val nextSessionId = AtomicLong(1)
    private val sessions = HashMap<String, DeviceSession>()

    data class RegistrationResult(
        val binding: SessionBinding,
        val replacedTransport: SessionTransport?,
        val replacedGeneration: Long?,
    )

    data class DeviceSessionSnapshot(
        val deviceId: String,
        val sessionId: Long,
        val connectionGeneration: Long,
        val pairingState: PairingState,
        val state: SessionState,
        val transportId: String?,
        val reconnectAttempt: Int,
        val lastDisconnectReason: DisconnectReason?,
    )

    fun register(
        deviceId: String,
        transport: SessionTransport,
        pairingState: PairingState,
    ): RegistrationResult {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }

        val result = synchronized(lock) {
            val session = sessions[deviceId] ?: DeviceSession(
                sessionId = nextSessionId.getAndIncrement(),
                deviceId = deviceId,
                initialPairingState = pairingState,
            ).also { sessions[deviceId] = it }

            check(session.state != SessionState.TERMINATED) { "Session $deviceId is terminated" }
            val oldGeneration = session.connectionGeneration.takeIf { session.activeTransport != null }
            val replacement = session.replaceTransport(transport)
            RegistrationResult(replacement.binding, replacement.replacedTransport, oldGeneration)
        }

        // Do not close user/network resources while holding the manager lock.
        result.replacedTransport?.close(DisconnectReason.REPLACED)
        return result
    }

    fun currentBinding(deviceId: String): SessionBinding? = synchronized(lock) {
        sessions[deviceId]?.let { session ->
            val transport = session.activeTransport ?: return@let null
            if (session.state != SessionState.ACTIVE) return@let null
            SessionBinding(session.deviceId, session.sessionId, session.connectionGeneration, transport)
        }
    }

    fun isCurrent(binding: SessionBinding): Boolean = synchronized(lock) {
        sessions[binding.deviceId]?.isCurrent(binding) == true
    }

    fun disconnectIfCurrent(binding: SessionBinding, reason: DisconnectReason): Boolean {
        val disconnected = synchronized(lock) {
            sessions[binding.deviceId]?.disconnectIfCurrent(binding, reason) == true
        }
        if (disconnected) binding.transport.close(reason)
        return disconnected
    }

    fun updatePairingState(deviceId: String, pairingState: PairingState): Boolean = synchronized(lock) {
        val session = sessions[deviceId] ?: return@synchronized false
        session.updatePairingState(pairingState)
        true
    }

    fun markReconnectAttempt(deviceId: String, attempt: Int): Boolean = synchronized(lock) {
        val session = sessions[deviceId] ?: return@synchronized false
        if (session.pairingState != PairingState.PAIRED || session.state == SessionState.TERMINATED) {
            return@synchronized false
        }
        session.markReconnectAttempt(attempt)
        true
    }

    fun terminateAll(): List<SessionTransport> {
        val transports = synchronized(lock) {
            sessions.values.mapNotNull(DeviceSession::terminate)
        }
        transports.forEach { it.close(DisconnectReason.SERVICE_STOPPED) }
        return transports
    }

    fun sessionsSnapshot(): List<DeviceSessionSnapshot> = synchronized(lock) {
        sessions.values.map { session ->
            DeviceSessionSnapshot(
                deviceId = session.deviceId,
                sessionId = session.sessionId,
                connectionGeneration = session.connectionGeneration,
                pairingState = session.pairingState,
                state = session.state,
                transportId = session.activeTransport?.transportId,
                reconnectAttempt = session.reconnectAttempt,
                lastDisconnectReason = session.lastDisconnectReason,
            )
        }.sortedBy(DeviceSessionSnapshot::deviceId)
    }
}
