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
    /** Desktop → phone report: a text input on the desktop gained or lost
     * focus, so the phone raises or drops its keyboard. */
    INPUT_FOCUS("input-focus"),
    /** Phone → desktop: show the next shared screen. */
    SWITCH_SCREEN("switch-screen"),
    /**
     * Phone → desktop: this phone offers its own screen without being asked. The desk adopts the
     * phone-chosen `remoteSessionId` and answers with an ordinary `request-view` for it (or
     * `view-denied` "busy"); the phone treats that request as already consented.
     */
    OFFER_VIEW("offer-view"),
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
    /** `input-focus`: whether the focused desktop element takes typing. */
    val editable: Boolean? = null,
    /** `screen-ready`: how many screens the desktop shares. */
    val screenCount: Int? = null,
    /** `request-view` / `request-control`: the desktop's session password, when one is known
     * (see [SessionPassword]). Omitted, never empty. */
    val password: String? = null,
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
            editable?.let { value.put("editable", it) }
            screenCount?.let { value.put("screenCount", it) }
            password?.takeIf(String::isNotEmpty)?.let { value.put("password", it) }
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
                editable = value.takeIf { it.has("editable") }?.getBoolean("editable"),
                screenCount = value.takeIf { it.has("screenCount") }?.getInt("screenCount")?.coerceIn(0, 16),
                password = value.optString("password").takeIf(String::isNotEmpty),
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
    private var screenWidth: Int? = null
    private var screenHeight: Int? = null

    private data class Lease(
        val id: String,
        val ownerDeviceId: String,
        val generation: Long,
        var expiresAt: Long,
        val sequenceWindow: InputSequenceWindow = InputSequenceWindow(),
    )

    /** Bounded replay protection that tolerates delivery reordering between
     * the realtime pointer and reliable discrete-input WebRTC channels. */
    private class InputSequenceWindow {
        private var highest = 0L
        private val seen = hashSetOf<Long>()

        fun accept(sequence: Long): Boolean {
            if (sequence <= 0) return false
            if (sequence > highest) {
                highest = sequence
                seen += sequence
                val oldest = highest - WIDTH + 1
                seen.removeAll { it < oldest }
                return true
            }
            if (highest - sequence >= WIDTH) return false
            return seen.add(sequence)
        }

        private companion object {
            const val WIDTH = 128L
        }
    }

    data class Snapshot(
        val state: WebRtcRemoteSessionState,
        val remoteSessionId: String?,
        val direction: WebRtcScreenDirection?,
        val hasControlLease: Boolean,
        /** The desktop's own coordinate space, from its `screen-ready`. Input
         * must be mapped into this, not into the (downscaled) decoded frame. */
        val screenWidth: Int? = null,
        val screenHeight: Int? = null,
    )

    @Synchronized
    fun snapshot(): Snapshot =
        Snapshot(state, sessionId, direction, lease != null, screenWidth, screenHeight)

    @Synchronized
    fun beginView(direction: WebRtcScreenDirection): String {
        clearLease()
        clearGeometry()
        val id = UUID.randomUUID().toString()
        sessionId = id
        this.direction = direction
        state = WebRtcRemoteSessionState.REQUESTING_VIEW
        return id
    }

    /** The phone's own offer of its screen ([WebRtcRemoteSessionControlKind.OFFER_VIEW]): a new id, phone to desktop. */
    @Synchronized
    fun beginOffer(): String = beginView(WebRtcScreenDirection.PHONE_TO_DESKTOP).also { offered = it }

    /** Whether a desk `request-view` answers this phone's own offer (and so needs no second consent). Consumed once. */
    @Synchronized
    fun answersOffer(remoteSessionId: String, direction: WebRtcScreenDirection?): Boolean =
        (direction == WebRtcScreenDirection.PHONE_TO_DESKTOP && offered == remoteSessionId).also { if (it) offered = null }

    private var offered: String? = null

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
        password: String? = null,
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
        password = password,
    )

    /** Applies a validated peer control message and returns the automatic
     * paired-device acknowledgement, if a policy-free acknowledgement exists.
     * Permission-dependent grants are made by the platform coordinator.
     *
     * Returns false when the message belongs to a session this controller has
     * already left (a delayed release, denial or error for a previous view). It
     * changed nothing and the caller must not act on it either. */
    @Synchronized
    fun accept(
        message: WebRtcRemoteSessionControlMessage,
        wire: WebRtcWireBinding,
        attemptId: String,
    ): Boolean {
        message.validate(wire, attemptId, System.currentTimeMillis())
        when (message.kind) {
            WebRtcRemoteSessionControlKind.REQUEST_VIEW -> {
                clearLease()
                clearGeometry()
                sessionId = message.remoteSessionId
                direction = requireNotNull(message.direction) { "DeskLink remote view has no direction" }
                state = WebRtcRemoteSessionState.REQUESTING_VIEW
            }
            WebRtcRemoteSessionControlKind.VIEW_GRANTED,
            WebRtcRemoteSessionControlKind.SCREEN_READY -> {
                requireSession(message.remoteSessionId)
                direction = requireNotNull(message.direction) { "DeskLink remote view has no direction" }
                if (message.screenWidth != null && message.screenHeight != null) {
                    screenWidth = message.screenWidth
                    screenHeight = message.screenHeight
                }
                // A screen-ready for a session already under control is the
                // desktop announcing another screen (Switch screen): only the
                // geometry changes. Dropping to VIEWING here lost the control
                // lease, and every tap after a switch went nowhere.
                if (state != WebRtcRemoteSessionState.CONTROLLING) {
                    state = WebRtcRemoteSessionState.VIEWING
                }
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
                val current = lease ?: return false
                require(current.id == message.leaseId && current.ownerDeviceId == message.deviceId) {
                    "DeskLink remote-control heartbeat does not own the lease"
                }
                require(current.sequenceWindow.accept(message.sequence)) {
                    "Replayed DeskLink remote-control heartbeat"
                }
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
            // A report about the desktop's focus; it changes no session state,
            // and only counts for the session in progress.
            WebRtcRemoteSessionControlKind.INPUT_FOCUS -> {
                if (!isCurrent(message)) return false
            }
            // Sent by this phone, never received.
            WebRtcRemoteSessionControlKind.SWITCH_SCREEN ->
                throw IllegalArgumentException("switch-screen is a request to the desktop")
            WebRtcRemoteSessionControlKind.OFFER_VIEW ->
                throw IllegalArgumentException("offer-view is made by the phone")
            WebRtcRemoteSessionControlKind.RELEASE,
            WebRtcRemoteSessionControlKind.SCREEN_STOPPED -> {
                if (!isCurrent(message)) return false
                stopInternal(WebRtcRemoteSessionState.STOPPED)
            }
            WebRtcRemoteSessionControlKind.CONTROL_DENIED -> {
                if (!isCurrent(message)) return false
                // Failure to grant input must not tear down a working screen
                // view. Retain its identity so the user can retry after
                // changing the desktop's input-permission decision.
                clearLease()
                state = WebRtcRemoteSessionState.VIEWING
            }
            WebRtcRemoteSessionControlKind.VIEW_DENIED -> {
                if (!isCurrent(message)) return false
                stopInternal(WebRtcRemoteSessionState.DENIED)
            }
            WebRtcRemoteSessionControlKind.SCREEN_ERROR -> {
                if (!isCurrent(message)) return false
                stopInternal(WebRtcRemoteSessionState.FAILED)
            }
        }
        return true
    }

    private fun isCurrent(message: WebRtcRemoteSessionControlMessage) =
        sessionId == message.remoteSessionId

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

    /** A lease that ran out leaves the session viewing, so control can be
     * asked for again. True when one was dropped. */
    @Synchronized
    fun dropExpiredLease(now: Long = System.currentTimeMillis()): Boolean {
        val current = lease ?: return false
        if (current.expiresAt > now) return false
        lease = null
        if (state == WebRtcRemoteSessionState.CONTROLLING) state = WebRtcRemoteSessionState.VIEWING
        return true
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
        // The desktop extends its copy of the lease when this heartbeat arrives.
        // The owner never receives its own, so it must extend its own here, or
        // its copy expires at the desktop's initial grant and the phone starts
        // refusing every gesture while the UI still shows control as enabled.
        current.expiresAt = System.currentTimeMillis() + LEASE_MILLIS
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
        require(current.sequenceWindow.accept(sequence)) { "Replayed DeskLink remote input" }
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

    /**
     * The desktop never answered a view request. Ends it, but only if that exact
     * request is still the pending one — a grant that landed in the meantime, or
     * a newer request, must not be torn down by a stale timer.
     */
    @Synchronized
    fun viewRequestTimedOut(remoteSessionId: String): Boolean {
        if (sessionId != remoteSessionId || state != WebRtcRemoteSessionState.REQUESTING_VIEW) {
            return false
        }
        stopInternal(WebRtcRemoteSessionState.FAILED)
        return true
    }

    /** The desktop never answered a control request: fall back to the view, so
     * the user can simply ask again. */
    @Synchronized
    fun controlRequestTimedOut(remoteSessionId: String): Boolean {
        if (sessionId != remoteSessionId || state != WebRtcRemoteSessionState.REQUESTING_CONTROL) {
            return false
        }
        state = WebRtcRemoteSessionState.VIEWING
        return true
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

    private fun clearGeometry() {
        screenWidth = null
        screenHeight = null
    }

    private fun stopInternal(newState: WebRtcRemoteSessionState) {
        clearLease()
        clearGeometry()
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
