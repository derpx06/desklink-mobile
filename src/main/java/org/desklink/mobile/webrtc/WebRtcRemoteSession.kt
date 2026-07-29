/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.desklink.mobile.NetworkPacket
import org.json.JSONObject
import java.util.UUID

/** Wire-compatible with the desktop remote-session control contract. */
enum class WebRtcRemoteSessionState {
    IDLE,
    REQUESTING_VIEW,
    VIEWING,
    REQUESTING_CONTROL,
    CONTROLLING,
    PAUSED_LOCKED,
    RECONNECTING,
    DENIED,
    FAILED,
    STOPPED,
}

enum class WebRtcScreenDirection(val wireName: String) {
    PHONE_TO_DESKTOP("phone-to-desktop"),
    DESKTOP_TO_PHONE("desktop-to-phone"),
    ;

    companion object {
        fun fromWireName(value: String): WebRtcScreenDirection =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unknown DeskLink screen direction: $value")
    }
}

enum class WebRtcRemoteSessionControlKind(val wireName: String) {
    REQUEST_VIEW("request-view"),
    VIEW_GRANTED("view-granted"),
    VIEW_DENIED("view-denied"),
    REQUEST_CONTROL("request-control"),
    CONTROL_GRANTED("control-granted"),
    CONTROL_DENIED("control-denied"),
    TAKEOVER_REQUEST("takeover-request"),
    TAKEOVER_GRANTED("takeover-granted"),
    RELEASE("release"),
    HEARTBEAT("heartbeat"),
    SCREEN_READY("screen-ready"),
    SCREEN_STOPPED("screen-stopped"),
    SCREEN_ERROR("screen-error"),
    PAUSE_LOCKED("pause-locked"),
    RESUME("resume"),
    ;

    companion object {
        fun fromWireName(value: String): WebRtcRemoteSessionControlKind =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unknown DeskLink remote-session message: $value")
    }
}

data class WebRtcRemoteSessionControlMessage(
    val remoteSessionVersion: Int,
    val kind: WebRtcRemoteSessionControlKind,
    val sessionAttemptId: String,
    val deviceId: String,
    val sessionId: Long,
    val connectionGeneration: Long,
    val remoteSessionId: String,
    val leaseId: String? = null,
    val direction: WebRtcScreenDirection? = null,
    val ownerDeviceId: String? = null,
    val sequence: Long,
    val leaseExpiresAt: Long? = null,
    val timestamp: Long,
    val reason: String? = null,
    /** Native coordinate space of the capture source. Present only with a
     * screen-ready control message, so a remote viewer can map input through
     * its letterboxed VP8 frame without guessing the phone display size. */
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val screenRotation: Int? = null,
) {
    fun validate(wire: WebRtcWireBinding, attemptId: String, now: Long) {
        require(remoteSessionVersion == VERSION) { "Unsupported DeskLink remote-session version" }
        require(
            sessionAttemptId == attemptId &&
                deviceId == wire.peerDeviceId &&
                sessionId == wire.sessionId &&
                connectionGeneration == wire.generation,
        ) { "DeskLink remote-session binding mismatch" }
        require(runCatching { UUID.fromString(remoteSessionId) }.isSuccess && sequence > 0) {
            "Malformed DeskLink remote-session control message"
        }
        require(kotlin.math.abs(now - timestamp) <= MAX_AGE_MILLIS) {
            "Expired DeskLink remote-session control message"
        }
        require(reason == null || reason.length <= 1024) { "DeskLink remote-session error is too large" }
        require((screenWidth == null) == (screenHeight == null)) {
            "DeskLink remote screen geometry is incomplete"
        }
        require(screenRotation == null || screenWidth != null) {
            "DeskLink remote screen rotation has no geometry"
        }
        if (screenWidth != null && screenHeight != null) {
            require(screenWidth in 1..8192 && screenHeight in 1..8192) {
                "DeskLink remote screen geometry is invalid"
            }
            require(screenRotation == null || screenRotation in setOf(0, 90, 180, 270)) {
                "DeskLink remote screen rotation is invalid"
            }
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("remoteSessionVersion", remoteSessionVersion)
        .put("kind", kind.wireName)
        .put("sessionAttemptId", sessionAttemptId)
        .put("deviceId", deviceId)
        .put("sessionId", sessionId)
        .put("connectionGeneration", connectionGeneration)
        .put("remoteSessionId", remoteSessionId)
        .put("sequence", sequence)
        .put("timestamp", timestamp)
        .also { value ->
            leaseId?.let { value.put("leaseId", it) }
            direction?.let { value.put("direction", it.wireName) }
            ownerDeviceId?.let { value.put("ownerDeviceId", it) }
            leaseExpiresAt?.let { value.put("leaseExpiresAt", it) }
            reason?.let { value.put("reason", it) }
            screenWidth?.let { value.put("screenWidth", it) }
            screenHeight?.let { value.put("screenHeight", it) }
            screenRotation?.let { value.put("screenRotation", it) }
        }

    companion object {
        const val VERSION = 1
        const val MESSAGE_TYPE = "desklink.remote-session.v1"
        private const val MAX_AGE_MILLIS = 60_000L

        fun fromJson(value: JSONObject): WebRtcRemoteSessionControlMessage =
            WebRtcRemoteSessionControlMessage(
                remoteSessionVersion = value.getInt("remoteSessionVersion"),
                kind = WebRtcRemoteSessionControlKind.fromWireName(value.getString("kind")),
                sessionAttemptId = value.getString("sessionAttemptId"),
                deviceId = value.getString("deviceId"),
                sessionId = value.getLong("sessionId"),
                connectionGeneration = value.getLong("connectionGeneration"),
                remoteSessionId = value.getString("remoteSessionId"),
                leaseId = value.optString("leaseId").takeIf(String::isNotEmpty),
                direction = value.optString("direction").takeIf(String::isNotEmpty)
                    ?.let(WebRtcScreenDirection::fromWireName),
                ownerDeviceId = value.optString("ownerDeviceId").takeIf(String::isNotEmpty),
                sequence = value.getLong("sequence"),
                leaseExpiresAt = value.takeIf { it.has("leaseExpiresAt") }?.getLong("leaseExpiresAt"),
                timestamp = value.getLong("timestamp"),
                reason = value.optString("reason").takeIf(String::isNotEmpty),
                screenWidth = value.takeIf { it.has("screenWidth") }?.getInt("screenWidth"),
                screenHeight = value.takeIf { it.has("screenHeight") }?.getInt("screenHeight"),
                screenRotation = value.takeIf { it.has("screenRotation") }?.getInt("screenRotation"),
            )
    }
}

/**
 * A single authenticated lease for a remote session. The controller owns this
 * state rather than UI or plugins so the AccessibilityService never receives
 * input from a stale peer generation.
 */
class WebRtcRemoteSessionController(
    private val localDeviceId: () -> String,
) {
    private var state = WebRtcRemoteSessionState.IDLE
    private var sessionId: String? = null
    private var direction: WebRtcScreenDirection? = null
    private var lease: Lease? = null
    private var nextSequence = 1L

    private data class Lease(
        val id: String,
        val ownerDeviceId: String,
        val generation: Long,
        var expiresAt: Long,
        var lastSequence: Long = 0,
    )

    data class Snapshot(
        val state: WebRtcRemoteSessionState,
        val remoteSessionId: String?,
        val direction: WebRtcScreenDirection?,
        val hasControlLease: Boolean,
    )

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(state, sessionId, direction, lease != null)

    @Synchronized
    fun beginView(direction: WebRtcScreenDirection): String {
        clearLease()
        val id = UUID.randomUUID().toString()
        sessionId = id
        this.direction = direction
        state = WebRtcRemoteSessionState.REQUESTING_VIEW
        return id
    }

    @Synchronized
    fun requestControl(): String {
        require(state == WebRtcRemoteSessionState.VIEWING || state == WebRtcRemoteSessionState.REQUESTING_CONTROL) {
            "DeskLink control requires an active remote view"
        }
        state = WebRtcRemoteSessionState.REQUESTING_CONTROL
        return requireNotNull(sessionId)
    }

    @Synchronized
    fun markScreenReady(expectedDirection: WebRtcScreenDirection): String {
        val current = requireNotNull(sessionId) { "DeskLink remote view has no session ID" }
        require(direction == expectedDirection) { "DeskLink screen direction changed before capture started" }
        state = WebRtcRemoteSessionState.VIEWING
        return current
    }

    /** MediaProjection can stop on lock or revocation while the authenticated
     * WebRTC peer remains healthy. Preserve the view identity so the user can
     * explicitly resume sharing after unlock. */
    @Synchronized
    fun pauseLocked(): String? {
        clearLease()
        state = WebRtcRemoteSessionState.PAUSED_LOCKED
        return sessionId
    }

    @Synchronized
    fun makeMessage(
        kind: WebRtcRemoteSessionControlKind,
        attemptId: String,
        wire: WebRtcWireBinding,
        remoteSessionId: String = requireNotNull(sessionId),
        leaseId: String? = lease?.id,
        ownerDeviceId: String? = lease?.ownerDeviceId,
        leaseExpiresAt: Long? = lease?.expiresAt,
        reason: String? = null,
        screenWidth: Int? = null,
        screenHeight: Int? = null,
        screenRotation: Int? = null,
    ): WebRtcRemoteSessionControlMessage = WebRtcRemoteSessionControlMessage(
        remoteSessionVersion = WebRtcRemoteSessionControlMessage.VERSION,
        kind = kind,
        sessionAttemptId = attemptId,
        deviceId = localDeviceId(),
        sessionId = wire.sessionId,
        connectionGeneration = wire.generation,
        remoteSessionId = remoteSessionId,
        leaseId = leaseId,
        direction = direction,
        ownerDeviceId = ownerDeviceId,
        sequence = nextSequence(),
        leaseExpiresAt = leaseExpiresAt,
        timestamp = System.currentTimeMillis(),
        reason = reason,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        screenRotation = screenRotation,
    )

    /** Applies a validated peer control message and returns the automatic
     * paired-device acknowledgement, if a policy-free acknowledgement exists.
     * Permission-dependent grants are made by the platform coordinator. */
    @Synchronized
    fun accept(
        message: WebRtcRemoteSessionControlMessage,
        wire: WebRtcWireBinding,
        attemptId: String,
    ) {
        message.validate(wire, attemptId, System.currentTimeMillis())
        when (message.kind) {
            WebRtcRemoteSessionControlKind.REQUEST_VIEW -> {
                clearLease()
                sessionId = message.remoteSessionId
                direction = requireNotNull(message.direction) { "DeskLink remote view has no direction" }
                state = WebRtcRemoteSessionState.REQUESTING_VIEW
            }
            WebRtcRemoteSessionControlKind.VIEW_GRANTED,
            WebRtcRemoteSessionControlKind.SCREEN_READY -> {
                requireSession(message.remoteSessionId)
                direction = requireNotNull(message.direction) { "DeskLink remote view has no direction" }
                state = WebRtcRemoteSessionState.VIEWING
            }
            WebRtcRemoteSessionControlKind.REQUEST_CONTROL,
            WebRtcRemoteSessionControlKind.TAKEOVER_REQUEST -> {
                requireSession(message.remoteSessionId)
                state = WebRtcRemoteSessionState.REQUESTING_CONTROL
            }
            WebRtcRemoteSessionControlKind.CONTROL_GRANTED,
            WebRtcRemoteSessionControlKind.TAKEOVER_GRANTED -> {
                requireSession(message.remoteSessionId)
                val expiresAt = requireNotNull(message.leaseExpiresAt) { "DeskLink control grant has no expiry" }
                require(expiresAt > System.currentTimeMillis()) { "DeskLink control grant has expired" }
                lease = Lease(
                    id = requireNotNull(message.leaseId) { "DeskLink control grant has no lease" },
                    ownerDeviceId = message.ownerDeviceId ?: localDeviceId(),
                    generation = wire.generation,
                    expiresAt = expiresAt,
                )
                state = WebRtcRemoteSessionState.CONTROLLING
            }
            WebRtcRemoteSessionControlKind.HEARTBEAT -> {
                val current = lease ?: return
                require(current.id == message.leaseId && current.ownerDeviceId == message.deviceId) {
                    "DeskLink remote-control heartbeat does not own the lease"
                }
                require(message.sequence > current.lastSequence) { "Replayed DeskLink remote-control heartbeat" }
                current.lastSequence = message.sequence
                current.expiresAt = System.currentTimeMillis() + LEASE_MILLIS
            }
            WebRtcRemoteSessionControlKind.PAUSE_LOCKED -> {
                requireSession(message.remoteSessionId)
                clearLease()
                state = WebRtcRemoteSessionState.PAUSED_LOCKED
            }
            WebRtcRemoteSessionControlKind.RESUME -> {
                requireSession(message.remoteSessionId)
                state = WebRtcRemoteSessionState.VIEWING
            }
            WebRtcRemoteSessionControlKind.RELEASE,
            WebRtcRemoteSessionControlKind.SCREEN_STOPPED -> stopInternal(WebRtcRemoteSessionState.STOPPED)
            WebRtcRemoteSessionControlKind.VIEW_DENIED,
            WebRtcRemoteSessionControlKind.CONTROL_DENIED -> stopInternal(WebRtcRemoteSessionState.DENIED)
            WebRtcRemoteSessionControlKind.SCREEN_ERROR -> stopInternal(WebRtcRemoteSessionState.FAILED)
        }
    }

    /** Adds required lease metadata to a local input packet. */
    @Synchronized
    fun prepareOutboundInput(packet: NetworkPacket, generation: Long): NetworkPacket {
        val current = requireNotNull(lease) { "DeskLink remote control is not enabled" }
        require(state == WebRtcRemoteSessionState.CONTROLLING) { "DeskLink remote control is not active" }
        require(current.ownerDeviceId == localDeviceId() && current.generation == generation) {
            "DeskLink remote-control lease does not belong to this session"
        }
        require(current.expiresAt > System.currentTimeMillis()) { "DeskLink remote-control lease expired" }
        packet[REMOTE_SESSION_ID_FIELD] = requireNotNull(sessionId)
        packet[LEASE_ID_FIELD] = current.id
        packet[INPUT_SEQUENCE_FIELD] = nextSequence()
        return packet
    }

    /** Returns a control heartbeat only when this endpoint owns the active
     * lease. The coordinator sends it after releasing its state lock. */
    @Synchronized
    fun makeLocalHeartbeat(
        attemptId: String,
        wire: WebRtcWireBinding,
    ): WebRtcRemoteSessionControlMessage? {
        val current = lease ?: return null
        if (
            state != WebRtcRemoteSessionState.CONTROLLING ||
            current.ownerDeviceId != localDeviceId() ||
            current.generation != wire.generation ||
            current.expiresAt <= System.currentTimeMillis()
        ) {
            return null
        }
        return makeMessage(
            WebRtcRemoteSessionControlKind.HEARTBEAT,
            attemptId,
            wire,
            leaseId = current.id,
            ownerDeviceId = current.ownerDeviceId,
            leaseExpiresAt = null,
        )
    }

    /**
     * Checks local ownership without allocating an input/control sequence.
     * Schedulers use this before they install a repeating heartbeat task; the
     * actual heartbeat is the only operation allowed to advance the sequence.
     */
    @Synchronized
    fun hasLocalControlLease(generation: Long): Boolean {
        val current = lease ?: return false
        return state == WebRtcRemoteSessionState.CONTROLLING &&
            current.ownerDeviceId == localDeviceId() &&
            current.generation == generation &&
            current.expiresAt > System.currentTimeMillis()
    }

    /** Verifies an inbound input packet before it reaches AccessibilityService. */
    @Synchronized
    fun verifyInboundInput(packet: NetworkPacket, wire: WebRtcWireBinding) {
        val current = requireNotNull(lease) { "DeskLink remote input has no active lease" }
        require(state == WebRtcRemoteSessionState.CONTROLLING) { "DeskLink remote input is not active" }
        require(current.ownerDeviceId == wire.peerDeviceId && current.generation == wire.generation) {
            "DeskLink remote input lease is not owned by this peer"
        }
        require(current.expiresAt > System.currentTimeMillis()) { "DeskLink remote input lease expired" }
        require(packet.getString(REMOTE_SESSION_ID_FIELD) == sessionId && packet.getString(LEASE_ID_FIELD) == current.id) {
            "DeskLink remote input session or lease mismatch"
        }
        val sequence = packet.getLong(INPUT_SEQUENCE_FIELD, 0)
        require(sequence > current.lastSequence) { "Replayed DeskLink remote input" }
        current.lastSequence = sequence
        current.expiresAt = System.currentTimeMillis() + LEASE_MILLIS
    }

    @Synchronized
    fun grantPeerControl(remoteSessionId: String, wire: WebRtcWireBinding) {
        requireSession(remoteSessionId)
        val newLease = Lease(
            id = UUID.randomUUID().toString(),
            ownerDeviceId = wire.peerDeviceId,
            generation = wire.generation,
            expiresAt = System.currentTimeMillis() + LEASE_MILLIS,
        )
        lease = newLease
        state = WebRtcRemoteSessionState.CONTROLLING
    }

    @Synchronized
    fun stop() = stopInternal(WebRtcRemoteSessionState.STOPPED)

    private fun requireSession(value: String) {
        require(sessionId == value) { "DeskLink remote-session ID is stale" }
    }

    private fun nextSequence(): Long {
        val value = nextSequence
        nextSequence = if (nextSequence == Long.MAX_VALUE) 1 else nextSequence + 1
        return value
    }

    private fun clearLease() {
        lease = null
    }

    private fun stopInternal(newState: WebRtcRemoteSessionState) {
        clearLease()
        sessionId = null
        direction = null
        state = newState
    }

    companion object {
        const val REMOTE_SESSION_ID_FIELD = "desklinkRemoteSessionId"
        const val LEASE_ID_FIELD = "desklinkLeaseId"
        const val INPUT_SEQUENCE_FIELD = "desklinkInputSequence"
        private const val LEASE_MILLIS = 30_000L
    }
}
