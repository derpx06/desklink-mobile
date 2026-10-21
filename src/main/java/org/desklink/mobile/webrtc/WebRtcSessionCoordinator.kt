/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import android.content.Context
import android.content.Intent
import android.preference.PreferenceManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.backends.relay.RelayLinkProvider
import org.desklink.mobile.backends.relay.RelaySettings
import org.desklink.mobile.helpers.DeviceHelper
import org.desklink.mobile.helpers.security.RsaHelper
import org.desklink.mobile.plugins.screen.ScreenControlPlugin
import org.desklink.mobile.plugins.screen.PhoneScreenCaptureService
import org.desklink.mobile.plugins.mousereceiver.MouseReceiverService
import org.desklink.mobile.plugins.notifications.NotificationsPlugin
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.session.DeviceManager
import org.desklink.mobile.session.SessionBinding
import org.desklink.mobile.plugins.terminal.TerminalSinks
import org.desklink.mobile.transport.TransportCallback
import org.desklink.mobile.transport.TransportError
import org.desklink.mobile.transport.TransportErrorCode
import org.desklink.mobile.webrtc.terminal.TerminalClient
import org.desklink.mobile.webrtc.terminal.TerminalProtocol
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.security.MessageDigest
import java.net.URI
import java.util.Base64
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.webrtc.PeerConnection

/**
 * Paired-session WebRTC signaling coordinator. The existing LAN TLS link is
 * retained only for discovery, pairing, identity, and signed signaling.
 */
class WebRtcSessionCoordinator(
    private val context: Context,
    private val device: Device,
    private val sessions: DeviceManager,
    /**
     * The desktop's session password ([SessionPassword]) for this device, once the person gave it.
     * Memory only, shared by the screen and [terminal]. Owned by the application so it outlives
     * this coordinator: a reconnect offers it again instead of asking. Cleared when the desktop
     * refuses it, and on unpair.
     */
    val sessionPassword: AtomicReference<String?> = AtomicReference(),
    /**
     * Set when this peer goes away while the person is viewing the desktop, so the next peer
     * asks for the view again once it is feature-ready (docs/RELAY.md §6). Application-owned,
     * like [sessionPassword]; cleared by an explicit stop.
     */
    private val resumeScreen: AtomicBoolean = AtomicBoolean(),
    private val onStateChanged: (String, String?) -> Unit,
) : Device.ControlPacketHandler {
    private val lock = Any()
    private val seenRequestIds = LinkedHashSet<String>()
    private val seenEnvelopeIds = LinkedHashSet<String>()
    private var activeAttemptId: String? = null
    /** The desktop's last offered attempt, named in a restart request so it is not taken for stale. */
    private var lastPeerAttemptId: String? = null
    private var activeBinding: SessionBinding? = null
    private var activeWireBinding: WebRtcWireBinding? = null
    private var transport: WebRtcTransport? = null
    private var handover = HandoverRuntime()
    private var closed = false
    private var recoveryInProgress = false
    private var offerWatchdog: ScheduledFuture<*>? = null
    private var answerWatchdog: ScheduledFuture<*>? = null
    private var disconnectedGrace: ScheduledFuture<*>? = null
    private var disconnectedGraceAttemptId: String? = null
    private var remoteHeartbeat: ScheduledFuture<*>? = null
    // Data-channel liveness (mirrors the desktop's PEER_PROBE_AFTER /
    // PEER_DEAD_AFTER). Only armed while feature-ready; see startLivenessLocked.
    private val peerLiveness = PeerLiveness(PEER_PROBE_AFTER_MS, PEER_DEAD_AFTER_MS)
    // When the current initiator attempt began; a heartbeat may only replace
    // an attempt that has visibly stalled (see onBootstrapReconnected).
    private var attemptStartedAt = 0L
    private var liveness: ScheduledFuture<*>? = null
    /** Increments whenever a control lease is replaced, paused, or released.
     * A queued timer from an older lease must never cancel or extend a newer
     * one. */
    private var remoteHeartbeatEpoch = 0L
    private val recovery = WebRtcRecoveryPolicy()
    private val recoveryExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "DeskLink-WebRTC-Recovery-${device.deviceId}").apply { isDaemon = true }
    }
    private val transportCleanup = DeferredTaskExecutor(
        "DeskLink-WebRTC-Cleanup-${device.deviceId}",
    ) { error -> Log.w(TAG, "Failed to dispose the previous WebRTC transport", error) }
    /** libwebrtc callbacks run here, never on its shared signaling thread. */
    private val peerEvents = DeferredTaskExecutor(
        "DeskLink-WebRTC-Events-${device.deviceId}",
    ) { error -> Log.w(TAG, "WebRTC peer callback failed", error) }
    private val remoteActionExecutor = DeferredTaskExecutor(
        "DeskLink-WebRTC-RemoteAction-${device.deviceId}",
    ) { error ->
        Log.w(TAG, "Remote session action failed", error)
        publish("RemoteSessionFailed", error.message)
    }
    private val fileExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DeskLink-WebRTC-File-${device.deviceId}").apply { isDaemon = true }
    }
    private val fileTransfers = WebRtcFileTransferManager(context, device, ::sendFileMessage)

    /**
     * Terminal sessions on `desklink-terminal-v1`. Not a packet type: it is routed by
     * [handleTerminalEnvelope] before the packet bridge and is deliberately absent from
     * [WebRtcFeatureProfile]. The client runs on its own thread and never calls back here
     * synchronously, so it is safe to reach from libwebrtc's callback thread.
     */
    val terminal = TerminalClient(
        sender = ::sendTerminalMessage,
        sinkFactory = TerminalSinks.factory(context, device.deviceId),
        executor = TerminalClient.newExecutor("DeskLink-Terminal-${device.deviceId}"),
        discard = { message, error -> Log.w(TAG, message, error) },
        password = sessionPassword,
        onJobFinished = { finished -> org.desklink.mobile.plugins.terminal.TerminalAttentionNotifier.jobFinished(context, device.deviceId, finished) },
    )

    /** Link up/down events for [terminal], queued here so they are never delivered under [lock]. */
    private val terminalLink = DeferredTaskExecutor(
        "DeskLink-WebRTC-TerminalLink-${device.deviceId}",
    ) { error -> Log.w(TAG, "Terminal link event failed", error) }
    /** Sole authority for WebRTC remote-view and remote-input lease state. */
    private val remoteSession = WebRtcRemoteSessionController {
        DeviceHelper.getDeviceId(context)
    }

    init {
        device.setWebRtcPayloadHandler { packet, callback ->
            val wire = synchronized(lock) {
                val current = transport
                require(current != null && current.isReadyForPackets()) {
                    "DeskLink WebRTC file transport is not ready"
                }
                requireNotNull(activeWireBinding) { "No active DeskLink WebRTC wire binding" }
            }
            fileTransfers.sendPayload(wire, packet, callback)
        }
        device.setWebRtcRemoteInputPreparer { packet ->
            synchronized(lock) {
                val wire = requireNotNull(activeWireBinding) {
                    "DeskLink remote-control session is not ready"
                }
                remoteSession.prepareOutboundInput(packet, wire.generation)
            }
        }
    }

    private data class HandoverRuntime(
        var offerSdp: String? = null,
        var answerSdp: String? = null,
        var localNonce: String? = null,
        var remoteNonce: String? = null,
        var authenticationTimestamp: Long? = null,
        var peerAuthenticated: Boolean = false,
        var localCapabilitiesSent: Boolean = false,
        var remoteCapabilitiesReceived: Boolean = false,
        var localFeatureReadySent: Boolean = false,
        var remoteFeatureReadyReceived: Boolean = false,
    )

    fun beginIfSupported() {
        val binding = sessions.currentBinding(device.deviceId) ?: return
        if (!device.isPaired || !isEnabled() || !remoteAcceptsSignal()) return
        val localDeviceId = DeviceHelper.getDeviceId(context)
        if (localDeviceId >= device.deviceId) {
            publish("WaitingForOffer", "The paired desktop is the deterministic WebRTC initiator")
            return
        }
        val negotiating = synchronized(lock) {
            closed || (activeAttemptId != null && activeBinding?.let(sessions::isCurrent) == true)
        }
        if (negotiating) return
        withIceServers { iceServers ->
            synchronized(lock) {
                if (closed || !sessions.isCurrent(binding)) return@synchronized
                if (activeAttemptId != null && activeBinding?.let(sessions::isCurrent) == true) return@synchronized
                // A replacement LAN bootstrap connection invalidates the old
                // binding. Dispose the old peer before starting a new signed
                // attempt, otherwise its callbacks can keep a stale session alive.
                if (activeAttemptId != null) closeLocked()
                startInitiatorLocked(binding, iceServers)
            }
        }
    }

    /**
     * Runs [start] with this path's ICE servers. The relay's TURN grant can take
     * up to 2 s to arrive, so it is fetched before [lock] is taken and never on
     * the main thread (main-thread callbacks take [lock]).
     */
    private fun withIceServers(start: (List<PeerConnection.IceServer>) -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            runCatching { recoveryExecutor.execute { start(iceServersForPath()) } }
                .onFailure { Log.d(TAG, "WebRTC coordinator is closed", it) }
        } else {
            start(iceServersForPath())
        }
    }

    /**
     * A newly authenticated bootstrap socket after the previous one was lost
     * is strong evidence that the peer process or network path was replaced.
     * The deterministic initiator must not keep an apparently-connected old
     * PeerConnection until ICE eventually times out: doing so leaves the UI
     * showing Connected while every feature targets a dead peer.
     *
     * LAN carries only the new signed SDP/ICE exchange. Paired feature data
     * remains blocked until the replacement peer reaches mutual
     * `feature-ready`.
     */
    fun onBootstrapReconnected() {
        val binding = sessions.currentBinding(device.deviceId) ?: return
        val localDeviceId = DeviceHelper.getDeviceId(context)
        // The usual case: a LAN heartbeat while the peer is fine. Publishing it fired a device-list
        // change (notification, lease, UI) on every heartbeat and every thaw of a frozen process.
        if (hasActiveFeatureTransport()) return
        if (!device.isPaired) return
        // A heartbeat arrives every ~10 s. Restarting on each one tore down a
        // negotiation still in progress and reset the backoff, so on any
        // network where ICE needs more than ~10 s it never finished. Only an
        // attempt that has visibly stalled, or a pending recovery that will
        // start its own attempt, is left alone here.
        val inFlight = synchronized(lock) {
            recoveryInProgress || (activeAttemptId != null &&
                SystemClock.elapsedRealtime() - attemptStartedAt < ATTEMPT_STALL_MS)
        }
        if (inFlight) return
        if (!shouldRestartAfterBootstrapReconnect(
                localDeviceId,
                device.deviceId,
                false,
            )
        ) {
            beginIfSupported()
            return
        }
        withIceServers { iceServers ->
            synchronized(lock) {
                if (closed || !sessions.isCurrent(binding)) return@synchronized
                publish("BootstrapReconnected", "Replacing the stale WebRTC peer")
                closeLocked()
                recovery.reset()
                recoveryInProgress = false
                device.setWebRtcRecoveryPending(true)
                startInitiatorLocked(binding, iceServers)
            }
        }
    }

    /**
     * A fresh bootstrap path (a relay link away from home): the deterministic
     * initiator offers on its own, so the answering side asks it to with a
     * signed `restart_request` instead of waiting (docs/RELAY.md §4).
     */
    fun requestPeerRestart() {
        val binding = sessions.currentBinding(device.deviceId) ?: return
        if (!device.isPaired || !remoteAcceptsSignal()) return
        if (shouldInitiateForRestartRequest(DeviceHelper.getDeviceId(context), device.deviceId)) return
        if (hasActiveFeatureTransport()) return
        val attemptId = synchronized(lock) { activeAttemptId ?: lastPeerAttemptId } ?: UUID.randomUUID().toString()
        publish("RestartRequested", "Asking the desktop to offer over the new bootstrap link")
        sendSignal(binding, attemptId, SignalingMessageType.RESTART_REQUEST, JSONObject())
    }

    /**
     * The phone's interface was lost or re-addressed, so this peer's ICE
     * candidates point at an address that no longer exists. Rebuild now — via
     * the backoff ladder, telling the desktop — instead of waiting out the 15 s
     * silence budget. Never `restartIce()`: webrtcbin cannot do an ICE restart.
     */
    /** The peer's process restarted: its end of our peer is gone. */
    fun onPeerRestarted() = synchronized(lock) {
        if (closed) return@synchronized
        closeLocked()
        recovery.reset()
        recoveryInProgress = false
        device.setWebRtcRecoveryPending(false)
    }

    fun onLocalNetworkMoved() {
        val current = synchronized(lock) {
            val binding = activeBinding
            val attemptId = activeAttemptId
            if (closed || binding == null || attemptId == null) null else binding to attemptId
        } ?: return
        scheduleRecovery(current.first, current.second, "Local network changed", notifyPeer = true)
    }

    /**
     * Returns true only for a mutually authenticated, feature-ready peer
     * bound to the current logical device session.  The application lifecycle
     * uses this to distinguish a disposable LAN bootstrap link from the live
     * paired WebRTC connection when Android turns the screen off.
     */
    fun hasActiveFeatureTransport(): Boolean = synchronized(lock) {
        !closed &&
            activeBinding?.let(sessions::isCurrent) == true &&
            transport?.isReadyForPackets() == true
    }

    /**
     * A bootstrap socket may close while a peer is healthy, or while a bounded
     * recovery is actively asking discovery for a replacement signaling path.
     * In either case the application must retain the logical paired session;
     * otherwise a screen-off event races recovery by deleting its binding.
     */
    fun shouldRetainSessionAfterBootstrapLoss(): Boolean = synchronized(lock) {
        !closed && (
            recoveryInProgress ||
                (
                    activeBinding?.let(sessions::isCurrent) == true &&
                        transport?.isReadyForPackets() == true
                    )
            )
    }

    fun startPhoneScreenCapture(permissionData: android.content.Intent) {
        synchronized(lock) {
            val active = requireNotNull(transport) { "No active DeskLink WebRTC peer" }
            val binding = requireNotNull(activeBinding) { "No active DeskLink WebRTC binding" }
            val attemptId = requireNotNull(activeAttemptId) { "No active DeskLink WebRTC attempt" }
            val wire = requireNotNull(activeWireBinding) { "No active DeskLink WebRTC wire binding" }
            require(active.isReadyForPackets()) {
                "DeskLink WebRTC screen transport is not ready"
            }
            val display = context.resources.displayMetrics
            // Capture at the phone's real shape, not the 1280x720 landscape
            // default. A portrait phone captured into a 16:9 landscape surface
            // arrives squashed and letterboxed, and the desktop was
            // simultaneously being told the screen was 1080x2400 in
            // `screen-ready` below — so the geometry it mapped taps against did
            // not describe the pixels it was shown.
            //
            // The reported geometry stays the *logical* screen size on purpose:
            // the desktop scales between the decoded video's real dimensions
            // and this, so 16-pixel alignment shaving a few columns off the
            // capture does not have to be modelled on both sides.
            active.startScreenCapture(
                permissionData,
                width = display.widthPixels,
                height = display.heightPixels,
                fps = 30,
            )
            val remoteSessionId = remoteSession.markScreenReady(WebRtcScreenDirection.PHONE_TO_DESKTOP)
            @Suppress("DEPRECATION")
            val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.defaultDisplay
                ?.rotation
                ?.let { value ->
                when (value) {
                    android.view.Surface.ROTATION_90 -> 90
                    android.view.Surface.ROTATION_180 -> 180
                    android.view.Surface.ROTATION_270 -> 270
                    else -> 0
                }
            }
            sendRemoteSessionControlLocked(
                binding,
                attemptId,
                remoteSession.makeMessage(
                    WebRtcRemoteSessionControlKind.SCREEN_READY,
                    attemptId,
                    wire,
                    remoteSessionId = remoteSessionId,
                    screenWidth = display.widthPixels,
                    screenHeight = display.heightPixels,
                    screenRotation = rotation,
                ),
            )
            publish("ScreenCaptureReady", "VP8")
        }
    }

    fun stopPhoneScreenCapture() {
        synchronized(lock) {
            transport?.stopScreenCapture()
        }
    }

    /** Report a denied or failed MediaProjection request without treating the
     * authenticated data-channel peer as a failed connection. */
    fun onPhoneScreenCaptureFailure(reason: String) {
        device.getPlugin(ScreenControlPlugin::class.java)?.onPhoneCaptureStopped(reason)
        synchronized(lock) {
            val binding = activeBinding ?: return
            val attemptId = activeAttemptId ?: return
            val wire = activeWireBinding ?: return
            val active = transport ?: return
            val remoteSessionId = remoteSession.snapshot().remoteSessionId ?: return
            if (!active.isReadyForPackets()) return
            remoteSession.stop()
            cancelRemoteHeartbeatLocked()
            sendRemoteSessionControlLocked(
                binding,
                attemptId,
                remoteSession.makeMessage(
                    WebRtcRemoteSessionControlKind.SCREEN_ERROR,
                    attemptId,
                    wire,
                    remoteSessionId = remoteSessionId,
                    reason = reason,
                ),
            )
        }
        publish("ScreenFailed", reason)
    }

    /**
     * Share screen from the phone: offers this phone's screen to the desk (`offer-view`). The desk
     * answers with a `request-view` for the same id, which runs the usual consent and capture.
     */
    fun offerPhoneScreen() {
        remoteActionExecutor.execute {
            try {
                synchronized(lock) {
                    val binding = requireNotNull(activeBinding) { "DeskLink WebRTC session is not active" }
                    val attemptId = requireNotNull(activeAttemptId) { "DeskLink WebRTC session is not active" }
                    val wire = requireNotNull(activeWireBinding) { "DeskLink WebRTC session is not active" }
                    require(currentTransport(binding, attemptId).isReadyForPackets()) {
                        "DeskLink WebRTC feature transport is not ready"
                    }
                    remoteSession.snapshot().remoteSessionId?.let { previous -> sendReleaseLocked(previous) }
                    val remoteSessionId = remoteSession.beginOffer()
                    sendRemoteSessionControlLocked(
                        binding,
                        attemptId,
                        remoteSession.makeMessage(
                            WebRtcRemoteSessionControlKind.OFFER_VIEW,
                            attemptId,
                            wire,
                            remoteSessionId = remoteSessionId,
                        ),
                    )
                    publish("OfferingPhoneScreen", remoteSessionId)
                    scheduleRemoteRequestTimeout(control = false, remoteSessionId)
                }
            } catch (error: Exception) {
                surfaceRemoteFailure(control = false, error)
            }
        }
    }

    /** Identifies the live WebRTC connection (its session id), or null without one. */
    fun connectionKey(): Long? = synchronized(lock) { activeWireBinding?.sessionId }

    /** Called only from a user gesture in the remote-control UI. */
    fun requestRemoteView(direction: WebRtcScreenDirection) {
        remoteActionExecutor.execute {
            try {
                synchronized(lock) {
                    val binding = requireNotNull(activeBinding) { "DeskLink WebRTC session is not active" }
                    val attemptId = requireNotNull(activeAttemptId) { "DeskLink WebRTC session is not active" }
                    val wire = requireNotNull(activeWireBinding) { "DeskLink WebRTC session is not active" }
                    require(currentTransport(binding, attemptId).isReadyForPackets()) {
                        "DeskLink WebRTC feature transport is not ready"
                    }
                    // A retry on a live view must end the old one first. The
                    // desktop refuses a second view while one is active and
                    // would answer "denied", leaving its capture running for an
                    // id this side has already forgotten. The channel is
                    // ordered, so the release reaches it before the request.
                    remoteSession.snapshot().remoteSessionId?.let { previous ->
                        sendReleaseLocked(previous)
                    }
                    val remoteSessionId = remoteSession.beginView(direction)
                    sendRemoteSessionControlLocked(
                        binding,
                        attemptId,
                        remoteSession.makeMessage(
                            WebRtcRemoteSessionControlKind.REQUEST_VIEW,
                            attemptId,
                            wire,
                            remoteSessionId = remoteSessionId,
                            password = sessionPassword.get(),
                        ),
                    )
                    publish("RequestingRemoteView", direction.wireName)
                    scheduleRemoteRequestTimeout(control = false, remoteSessionId)
                }
            } catch (error: Exception) {
                surfaceRemoteFailure(control = false, error)
            }
        }
    }

    /** Called only from the explicit Enable control UI action. */
    fun requestRemoteControl() {
        remoteActionExecutor.execute {
            try {
                synchronized(lock) {
                    val binding = requireNotNull(activeBinding) { "DeskLink WebRTC session is not active" }
                    val attemptId = requireNotNull(activeAttemptId) { "DeskLink WebRTC session is not active" }
                    val wire = requireNotNull(activeWireBinding) { "DeskLink WebRTC session is not active" }
                    require(currentTransport(binding, attemptId).isReadyForPackets()) {
                        "DeskLink WebRTC feature transport is not ready"
                    }
                    val remoteSessionId = remoteSession.requestControl()
                    sendRemoteSessionControlLocked(
                        binding,
                        attemptId,
                        remoteSession.makeMessage(
                            WebRtcRemoteSessionControlKind.REQUEST_CONTROL,
                            attemptId,
                            wire,
                            remoteSessionId = remoteSessionId,
                            password = sessionPassword.get(),
                        ),
                    )
                    publish("RequestingRemoteControl", null)
                    scheduleRemoteRequestTimeout(control = true, remoteSessionId)
                }
            } catch (error: Exception) {
                surfaceRemoteFailure(control = true, error)
            }
        }
    }

    /** Sends a release for `remoteSessionId`. Caller holds `lock`. */
    private fun sendReleaseLocked(remoteSessionId: String) {
        val binding = activeBinding ?: return
        val attemptId = activeAttemptId ?: return
        val wire = activeWireBinding ?: return
        sendRemoteSessionControlLocked(
            binding,
            attemptId,
            remoteSession.makeMessage(
                WebRtcRemoteSessionControlKind.RELEASE,
                attemptId,
                wire,
                remoteSessionId = remoteSessionId,
            ),
        )
    }

    /** Nothing may stay at "requesting" forever. The desktop times its own
     * portal request out at two minutes and answers; this is the net under it
     * for a desktop that is silent (an old build, a lost message). */
    private fun scheduleRemoteRequestTimeout(control: Boolean, remoteSessionId: String) {
        recoveryExecutor.schedule({
            synchronized(lock) {
                if (closed) return@synchronized
                val timedOut = if (control) {
                    remoteSession.controlRequestTimedOut(remoteSessionId)
                } else {
                    remoteSession.viewRequestTimedOut(remoteSessionId)
                }
                if (!timedOut) return@synchronized
                // Tell the desktop to drop whatever it started, so a late
                // approval cannot begin a share nobody is waiting for.
                if (!control) runCatching { sendReleaseLocked(remoteSessionId) }
                device.getPlugin(ScreenControlPlugin::class.java)?.onRemoteRequestTimedOut(control)
                publish("RemoteRequestTimedOut", if (control) "control" else "view")
            }
        }, REMOTE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /** Puts a failed remote action in front of the user instead of only in the log. */
    private fun surfaceRemoteFailure(control: Boolean, error: Throwable) {
        Log.w(TAG, "Remote ${if (control) "control" else "view"} request failed", error)
        publish("RemoteSessionFailed", error.message)
        val plugin = device.getPlugin(ScreenControlPlugin::class.java) ?: return
        if (control) {
            plugin.onRemoteControlDenied(error.message)
        } else {
            // These are `require` failures ("session is not active", "transport
            // is not ready"); the user needs to know to wait and retry.
            plugin.onRemoteViewFailed(
                if (error is IllegalArgumentException || error is IllegalStateException) {
                    context.getString(R.string.remote_screen_not_ready)
                } else {
                    error.message
                },
            )
        }
    }

    fun remoteSessionSnapshot(): WebRtcRemoteSessionController.Snapshot = remoteSession.snapshot()

    /** Asks the desktop to show its next shared screen (a laptop and a
     * monitor, say). */
    fun switchRemoteScreen() {
        remoteActionExecutor.execute {
            synchronized(lock) {
                val binding = activeBinding ?: return@synchronized
                val attemptId = activeAttemptId ?: return@synchronized
                val wire = activeWireBinding ?: return@synchronized
                val remoteSessionId = remoteSession.snapshot().remoteSessionId ?: return@synchronized
                if (transport?.isReadyForPackets() != true) return@synchronized
                sendRemoteSessionControlLocked(
                    binding,
                    attemptId,
                    remoteSession.makeMessage(
                        WebRtcRemoteSessionControlKind.SWITCH_SCREEN,
                        attemptId,
                        wire,
                        remoteSessionId = remoteSessionId,
                    ),
                )
            }
        }
    }

    fun stopRemoteSession() {
        resumeScreen.set(false)
        remoteActionExecutor.execute {
            synchronized(lock) {
                val binding = activeBinding ?: run {
                    remoteSession.stop()
                    cancelRemoteHeartbeatLocked()
                    return@synchronized
                }
                val attemptId = activeAttemptId ?: run {
                    remoteSession.stop()
                    cancelRemoteHeartbeatLocked()
                    return@synchronized
                }
                val wire = activeWireBinding ?: run {
                    remoteSession.stop()
                    cancelRemoteHeartbeatLocked()
                    return@synchronized
                }
                val active = transport
                if (active?.isReadyForPackets() == true && remoteSession.snapshot().remoteSessionId != null) {
                    sendRemoteSessionControlLocked(
                        binding,
                        attemptId,
                        remoteSession.makeMessage(WebRtcRemoteSessionControlKind.RELEASE, attemptId, wire),
                    )
                }
                remoteSession.stop()
                cancelRemoteHeartbeatLocked()
                // The video track stays: libwebrtc delivers it once per peer, so
                // dropping it here left every later view black.
                stopPhoneScreenCapture()
            }
        }
    }

    override fun onControlPacket(packet: NetworkPacket): Boolean = runCatching {
        require(packet.type == DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1)
        val binding = sessions.currentBinding(device.deviceId)
            ?: error("No current DeskLink session for signaling")
        require(device.isPaired && isEnabled()) { "WebRTC signaling requires an enabled paired session" }
        val message = WebRtcSignalingMessage.fromNetworkPacket(packet)
        message.validateFor(DeviceHelper.getDeviceId(context), System.currentTimeMillis())
        require(message.fromDeviceId == device.deviceId) { "WebRTC signaling sender does not match paired device" }
        require(message.verify(device.certificate.publicKey)) { "Invalid paired identity signature on WebRTC signaling" }
        acceptRequestId(message.requestId)

        when (message.messageType) {
            SignalingMessageType.OFFER -> {
                require(DeviceHelper.getDeviceId(context) > device.deviceId) {
                    "Rejected unexpected WebRTC offer from non-initiator"
                }
                val sdp = requiredString(message.payload, "sdp", MAX_SDP_BYTES)
                // Fetched before the lock: the relay's TURN grant may take 2 s.
                val repeated = synchronized(lock) { activeAttemptId == message.sessionAttemptId }
                val iceServers = if (repeated) emptyList() else iceServersForPath()
                synchronized(lock) {
                    // An attempt ID is random and carries no ordering, so a
                    // second offer while one is already installed can only be a
                    // delayed or duplicated record. Tearing the peer down for it
                    // discarded a healthy connection; the desktop has always
                    // rejected this case and Android now matches.
                    val active = activeAttemptId
                    if (active == message.sessionAttemptId) {
                        // A repeated offer for the attempt already installed is
                        // ignored rather than re-applied.
                        //
                        // Re-applying is not safe here. If our answer round is
                        // still in flight, the pending `setLocalDescription`
                        // would land against a changed remote description and
                        // libwebrtc reports that as a set failure, which routes
                        // into `scheduleRecovery` and tears down a negotiation
                        // that was about to succeed. If the round has finished,
                        // a second answer changes `handover.answerSdp`, and that
                        // SDP is hashed into the authentication transcript both
                        // peers sign — so re-applying after authentication would
                        // invalidate a completed handover.
                        //
                        // The desktop does not produce this case: its
                        // `request_local_description` gates offer creation on a
                        // one-shot flag, so it emits exactly one offer per peer.
                        Log.i(TAG, "Ignoring a repeated offer for the current WebRTC attempt")
                        return@synchronized
                    }
                    // A new signed attempt from the paired initiator is a
                    // restart, never a stale record: the initiator sends one
                    // offer per attempt over one ordered TLS stream, so an
                    // older offer cannot overtake a newer one, and replay is
                    // already refused by `acceptRequestId` above (the request
                    // ID is covered by the signature). Refusing it while our
                    // transport still looked ready is what kept a restarted
                    // desktop locked out until ICE consent freshness expired.
                    // Mirrors the desktop's `offer_attempt_disposition`.
                    closeLocked()
                    activeAttemptId = message.sessionAttemptId
                    lastPeerAttemptId = message.sessionAttemptId
                    activeBinding = binding
                    activeWireBinding = WebRtcWireBinding.fromAttempt(
                        DeviceHelper.getDeviceId(context), device.deviceId, message.sessionAttemptId,
                    )
                    handover.offerSdp = sdp
                    transport = createTransport(binding, message.sessionAttemptId, createLocalChannels = false, iceServers)
                    publish("CreatingAnswer", null)
                    transport!!.setRemoteDescription(SessionDescription.Type.OFFER, sdp) {
                        transport?.createAnswer()
                    }
                }
            }
            SignalingMessageType.ANSWER -> {
                val sdp = requiredString(message.payload, "sdp", MAX_SDP_BYTES)
                synchronized(lock) {
                    handover.answerSdp = sdp
                    cancelAnswerWatchdogLocked()
                }
                currentTransport(binding, message.sessionAttemptId)
                    .setRemoteDescription(SessionDescription.Type.ANSWER, sdp)
            }
            SignalingMessageType.ICE_CANDIDATE -> {
                val candidate = requiredString(message.payload, "candidate", MAX_CANDIDATE_BYTES)
                val index = message.payload.getInt("sdpMLineIndex")
                currentTransport(binding, message.sessionAttemptId)
                    .addIceCandidate(IceCandidate(message.payload.optString("sdpMid"), index, candidate))
            }
            SignalingMessageType.END_OF_CANDIDATES -> currentTransport(binding, message.sessionAttemptId)
            SignalingMessageType.ICE_RESTART -> {
                currentTransport(binding, message.sessionAttemptId)
                scheduleRecovery(
                    binding,
                    message.sessionAttemptId,
                    "The peer requested WebRTC recovery",
                    notifyPeer = false,
                )
            }
            SignalingMessageType.RESTART_REQUEST -> {
                val localDeviceId = DeviceHelper.getDeviceId(context)
                require(shouldInitiateForRestartRequest(localDeviceId, device.deviceId)) {
                    "Only the deterministic DeskLink initiator may honor a restart request"
                }
                // An offer of ours still waiting for its answer already is the
                // restart the desktop asks for; replacing it only costs time.
                val inFlight = synchronized(lock) {
                    require(!closed) { "DeskLink WebRTC coordinator is closed" }
                    activeAttemptId != null && handover.answerSdp == null &&
                        SystemClock.elapsedRealtime() - attemptStartedAt < ANSWER_TIMEOUT_MS
                }
                if (inFlight) {
                    publish("RestartRequested", "An offer is already in flight")
                } else {
                    withIceServers { iceServers ->
                        synchronized(lock) {
                            if (closed) return@synchronized
                            closeLocked()
                            recovery.reset()
                            recoveryInProgress = false
                            device.setWebRtcRecoveryPending(false)
                            publish("RestartRequested", "Rebuilding WebRTC after the peer restarted")
                            startInitiatorLocked(binding, iceServers)
                        }
                    }
                }
            }
            SignalingMessageType.CLOSE -> synchronized(lock) {
                closeLocked()
                device.setWebRtcRecoveryPending(false)
                recoveryInProgress = false
                publish("Closed", "The peer closed the WebRTC session")
            }
        }
        true
    }.getOrElse { error ->
        Log.w(TAG, "Rejected WebRTC signaling from ${device.deviceId}: ${error.message}", error)
        publish("Failed", error.message)
        true // It was a control packet, never a plugin packet.
    }

    fun close() {
        synchronized(lock) {
            closed = true
            closeLocked()
            device.setWebRtcRecoveryPending(false)
            recoveryInProgress = false
        }
        recoveryExecutor.shutdownNow()
        fileExecutor.shutdownNow()
        transportCleanup.close()
        remoteActionExecutor.close()
        peerEvents.close()
        terminalLink.close()
        terminal.shutdown()
    }

    /**
     * Explicit-unpair close.  This permanently disables this coordinator
     * instance and clears all device callbacks so late peer events cannot
     * recreate WebRTC for an untrusted device.
     */
    fun closeForUnpair() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                closeLocked(reloadPlugins = false)
                device.clearWebRtcBindingsForUnpair()
                device.setWebRtcRecoveryPending(false)
                recoveryInProgress = false
            }
        }
        recoveryExecutor.shutdownNow()
        fileExecutor.shutdownNow()
        transportCleanup.close()
        remoteActionExecutor.close()
        peerEvents.close()
        terminalLink.close()
        terminal.shutdown()
    }

    /**
     * A LAN socket replacement is expected during Wi-Fi handshakes.  It must
     * not invalidate a healthy peer, but an incomplete offer/answer exchange
     * cannot safely continue on the old socket.  Rebuild one attempt through
     * the normal bounded recovery path instead of queueing stale SDP or ICE.
     */
    fun onBootstrapLinkReplaced() {
        val restart = synchronized(lock) {
            if (closed || transport?.isReadyForPackets() == true) {
                publish("BootstrapReplaced", "WebRTC feature transport retained")
                null
            } else {
                val binding = activeBinding?.takeIf(sessions::isCurrent)
                val attemptId = activeAttemptId
                if (binding == null || attemptId == null) {
                    null
                } else {
                    publish("BootstrapReplaced", "Restarting incomplete WebRTC negotiation")
                    binding to attemptId
                }
            }
        }
        if (restart != null) {
            scheduleRecovery(
                restart.first,
                restart.second,
                "LAN bootstrap socket was replaced during WebRTC negotiation",
                notifyPeer = false,
            )
        } else {
            beginIfSupported()
        }
    }

    private fun createTransport(
        binding: SessionBinding,
        attemptId: String,
        createLocalChannels: Boolean,
        iceServers: List<PeerConnection.IceServer>,
    ): WebRtcTransport = WebRtcTransport(
        context = context,
        factory = WebRtcRuntime.initialize(context),
        transportId = "webrtc:${device.deviceId}:${binding.sessionId}:${binding.connectionGeneration}",
        wireBinding = requireNotNull(activeWireBinding) { "WebRTC wire binding was not initialized" },
        iceServers = iceServers,
        createLocalChannels = createLocalChannels,
        relayOnly = RelaySettings.forceTurn(context),
        observer = OffThreadObserver(events = peerEvents, target = object : WebRtcTransport.Observer {
            override fun onSignalingNeeded(type: SignalingMessageType, payload: JSONObject) {
                when (type) {
                    SignalingMessageType.OFFER -> synchronized(lock) {
                        if (closed || activeAttemptId != attemptId || activeBinding != binding) {
                            Log.w(TAG, "Ignoring stale WebRTC offer callback")
                            return@synchronized
                        }
                        handover.offerSdp = payload.getString("sdp")
                        cancelOfferWatchdogLocked()
                        armAnswerWatchdogLocked(binding, attemptId)
                    }
                    SignalingMessageType.ANSWER -> synchronized(lock) {
                        // Same staleness guard the OFFER branch above has, and
                        // for a sharper reason: answerSdp is hashed into the
                        // signed authentication transcript. A late createAnswer
                        // callback from a torn-down transport overwrote the new
                        // attempt's answer, so the two peers computed different
                        // transcripts, every signature verification failed, and
                        // the handover froze there permanently.
                        if (closed || activeAttemptId != attemptId || activeBinding != binding) {
                            Log.w(TAG, "Ignoring stale WebRTC answer callback")
                            return@synchronized
                        }
                        handover.answerSdp = payload.getString("sdp")
                    }
                    else -> Unit
                }
                sendSignal(binding, attemptId, type, payload)
            }

            override fun onEnvelope(envelope: WebRtcEnvelope) {
                runCatching {
                    require(sessions.isCurrent(binding)) { "Stale DeskLink session" }
                    peerLiveness.onInbound(SystemClock.elapsedRealtime())
                    // Bind the envelope to the current wire before recording its
                    // ID, so a message that was never entitled to this session
                    // cannot burn an identifier. This deliberately does NOT
                    // decode the payload: `validate` base64-decodes up to 128 KB,
                    // and every branch below decodes again to obtain its bytes —
                    // doing it here too would double the cost of every file
                    // chunk on the network callback thread.
                    val wire = requireNotNull(synchronized(lock) { activeWireBinding }) {
                        "No active DeskLink WebRTC wire binding"
                    }
                    envelope.requireBoundTo(wire.peerDeviceId, wire.sessionId, wire.generation)
                    // Terminal traffic is routed before the replay cache: a busy terminal would
                    // wrap its 4096 entries in about a minute and evict every other feature's
                    // replay protection. Only control messages are recorded there (see below).
                    if (envelope.messageType.startsWith(TERMINAL_MESSAGE_PREFIX)) {
                        handleTerminalEnvelope(binding, attemptId, wire, envelope)
                        return@runCatching
                    }
                    // Accept each authenticated envelope exactly once. The
                    // desktop has always enforced this; without the matching
                    // check here a duplicated or replayed data-channel message
                    // was applied again, and the input-realtime channel is
                    // unordered so duplicates are not merely theoretical.
                    acceptEnvelopeId(attemptId, envelope.messageId)
                    if (
                        envelope.channel == WebRtcChannel.CONTROL.label &&
                        envelope.messageType == WebRtcHandoverControlMessage.MESSAGE_TYPE
                    ) {
                        handleHandoverMessage(binding, attemptId, envelope)
                        return@runCatching
                    }
                    require(currentTransport(binding, attemptId).handoverState().featuresAllowed) {
                        "DeskLink WebRTC feature handover is incomplete"
                    }
                    if (
                        envelope.channel == WebRtcChannel.CONTROL.label &&
                        envelope.messageType == WebRtcRemoteSessionControlMessage.MESSAGE_TYPE
                    ) {
                        handleRemoteSessionMessage(binding, attemptId, envelope)
                        return@runCatching
                    }
                    if (
                        envelope.messageType == WebRtcFileControl.CONTROL_MESSAGE_TYPE ||
                        envelope.messageType == WebRtcFileControl.CHUNK_MESSAGE_TYPE
                    ) {
                        val wire = requireNotNull(activeWireBinding) {
                            "No active DeskLink WebRTC wire binding"
                        }
                        val payload = envelope.validate(
                            wire.peerDeviceId,
                            wire.sessionId,
                            wire.generation,
                        )
                        fileExecutor.execute {
                            runCatching {
                                when (envelope.messageType) {
                                    WebRtcFileControl.CONTROL_MESSAGE_TYPE -> {
                                        require(envelope.channel == WebRtcChannel.FILE_CONTROL.label) {
                                            "WebRTC file control arrived on the wrong channel"
                                        }
                                        fileTransfers.handleControl(
                                            wire,
                                            WebRtcFileControl.fromJson(
                                                JSONObject(String(payload, Charsets.UTF_8)),
                                            ),
                                        )
                                    }
                                    WebRtcFileControl.CHUNK_MESSAGE_TYPE -> {
                                        require(envelope.channel == WebRtcChannel.FILE_DATA.label) {
                                            "WebRTC file chunk arrived on the wrong channel"
                                        }
                                        fileTransfers.handleChunk(wire, payload)
                                    }
                                }
                            }.onFailure { error ->
                                Log.w(TAG, "Rejected WebRTC file message", error)
                                publish("Failed", error.message)
                            }
                        }
                        return@runCatching
                    }
                    val packet = WebRtcPacketBridge.decode(
                        requireNotNull(activeWireBinding) { "No active DeskLink WebRTC wire binding" },
                        envelope,
                    )
                    if (
                        packet.type == DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST ||
                        packet.type == DeskLinkProtocol.PACKET_TYPE_PRESENTER
                    ) {
                        remoteSession.verifyInboundInput(
                            packet,
                            requireNotNull(activeWireBinding) {
                                "No active DeskLink WebRTC wire binding"
                            },
                        )
                    }
                    device.onWebRtcPacketReceived(packet)
                }.onFailure { error ->
                    Log.w(TAG, "Rejected WebRTC feature packet", error)
                    publish("Failed", error.message)
                }
            }

            override fun onControlChannelOpen() {
                if (sessions.isCurrent(binding)) {
                    beginHandover(binding, attemptId)
                }
            }

            override fun onRemoteVideoTrack(track: org.webrtc.VideoTrack) {
                // A track from a replaced or closed peer is disposed, and
                // installing it would give the screen UI something that throws.
                val current = synchronized(lock) {
                    !closed && activeAttemptId == attemptId && activeBinding == binding
                }
                if (!current) return
                device.getPlugin(ScreenControlPlugin::class.java)?.onRemoteVideoTrack(track)
                publish("ScreenTrackReady", track.id())
            }

            override fun onScreenCaptureStopped(reason: String) {
                device.getPlugin(ScreenControlPlugin::class.java)?.onPhoneCaptureStopped(reason)
                // The projection token is no longer usable. End only this
                // foreground capture service; the authenticated WebRTC peer
                // and pairing remain available for an explicit resume.
                context.stopService(Intent(context, PhoneScreenCaptureService::class.java))
                val paused = synchronized(lock) {
                    val active = transport
                    val wire = activeWireBinding
                    if (remoteSession.snapshot().remoteSessionId == null) {
                        return@synchronized false
                    }
                    val remoteSessionId = remoteSession.pauseLocked()
                    cancelRemoteHeartbeatLocked()
                    if (
                        active != null && active.isReadyForPackets() &&
                        wire != null && remoteSessionId != null &&
                        activeBinding == binding && activeAttemptId == attemptId
                    ) {
                        runCatching {
                            sendRemoteSessionControlLocked(
                                binding,
                                attemptId,
                                remoteSession.makeMessage(
                                    WebRtcRemoteSessionControlKind.PAUSE_LOCKED,
                                    attemptId,
                                    wire,
                                    remoteSessionId = remoteSessionId,
                                    reason = reason,
                                ),
                            )
                        }
                    }
                    true
                }
                // Projection revocation (including lock screen) is a normal
                // recoverable screen state. Do not replace the authenticated
                // peer or trigger an ICE-recovery loop.
                if (paused) publish("ScreenPaused", reason)
            }

            override fun onConnectionStateChanged(state: WebRtcPeerHealth) {
                when (state) {
                    WebRtcPeerHealth.CONNECTED -> {
                        synchronized(lock) {
                            if (!closed && activeAttemptId == attemptId && sessions.isCurrent(binding)) {
                                cancelDisconnectedGraceLocked()
                                recovery.reset()
                                // DTLS can be connected before the signed
                                // handover is complete. Keep the scoped power
                                // lease through that final control exchange;
                                // it is released only at feature-ready.
                                if (transport?.isReadyForPackets() == true) {
                                    device.setWebRtcRecoveryPending(false)
                                    recoveryInProgress = false
                                    // Sends queued during the blip: the same transport came
                                    // back, so setWebRtcTransport's flush never runs for it.
                                    device.flushPendingFeaturePackets()
                                    // The same peer recovering needs no new handshake; the
                                    // terminal client ignores this unless it saw the link drop.
                                    activeWireBinding?.let { recovered -> terminalLinkEvent { terminal.onLinkReady(recovered) } }
                                }
                            }
                        }
                        publish("Connected", null)
                    }
                    WebRtcPeerHealth.DISCONNECTED -> scheduleDisconnectedGrace(
                        binding,
                        attemptId,
                        "WebRTC peer connection disconnected",
                    )
                    WebRtcPeerHealth.FAILED -> scheduleRecovery(
                        binding,
                        attemptId,
                        "WebRTC peer connection failed",
                        notifyPeer = true,
                    )
                    WebRtcPeerHealth.CLOSED -> {
                        // Only the current peer's close may drop the track: a
                        // late CLOSED from the peer just replaced would
                        // otherwise blank the new one.
                        val current = synchronized(lock) {
                            !closed && activeAttemptId == attemptId && activeBinding == binding
                        }
                        if (current) {
                            device.getPlugin(ScreenControlPlugin::class.java)?.clearRemoteVideoTrack()
                        }
                        // A remote close is terminal for this peer generation.
                        // A local close during replacement clears activeAttemptId
                        // first, so `scheduleRecovery` safely ignores it.
                        scheduleRecovery(
                            binding,
                            attemptId,
                            "WebRTC peer connection closed",
                            notifyPeer = false,
                        )
                    }
                }
            }

            override fun onFailure(error: Throwable) {
                Log.w(TAG, "WebRTC peer connection failed", error)
                publish("Failed", error.message)
                scheduleRecovery(
                    binding,
                    attemptId,
                    error.message ?: "WebRTC peer connection failed",
                    notifyPeer = true,
                )
            }
        }),
    )

    private fun beginHandover(binding: SessionBinding, attemptId: String) = synchronized(lock) {
        require(sessions.isCurrent(binding)) { "Stale DeskLink session" }
        val localDeviceId = DeviceHelper.getDeviceId(context)
        publish("Authenticating", null)
        if (localDeviceId >= device.deviceId) return@synchronized
        // A data-channel observer can report Open more than once while a
        // peer is recovering. The signed hello is one attempt-scoped record,
        // so duplicate open events must not create a second nonce or make the
        // responder reject an otherwise healthy feature handover.
        if (handover.localNonce != null) return@synchronized
        val nonce = UUID.randomUUID().toString()
        handover.localNonce = nonce
        sendControl(
            binding,
            attemptId,
            controlMessage(
                WebRtcHandoverControlKind.HELLO,
                attemptId,
                nonce = nonce,
            ),
        )
    }

    private fun handleHandoverMessage(
        binding: SessionBinding,
        attemptId: String,
        envelope: WebRtcEnvelope,
    ) = synchronized(lock) {
        val wire = requireNotNull(activeWireBinding) { "No active DeskLink WebRTC wire binding" }
        val payload = envelope.validate(wire.peerDeviceId, wire.sessionId, wire.generation)
        val message = WebRtcHandoverControlMessage.fromJson(
            JSONObject(String(payload, Charsets.UTF_8)),
        )
        message.validate(wire, attemptId, System.currentTimeMillis())
        val localDeviceId = DeviceHelper.getDeviceId(context)
        val localIsInitiator = localDeviceId < device.deviceId
        val activeTransport = currentTransport(binding, attemptId)

        when (message.kind) {
            WebRtcHandoverControlKind.HELLO -> {
                require(!localIsInitiator) { "WebRTC initiator received an unexpected hello" }
                val initiatorNonce = message.nonce.required("hello nonce")
                val responderNonce = UUID.randomUUID().toString()
                val timestamp = System.currentTimeMillis()
                handover.remoteNonce = initiatorNonce
                handover.localNonce = responderNonce
                handover.authenticationTimestamp = timestamp
                val signature = buildAuthenticationTranscript(attemptId, wire, timestamp)
                    .sign(RsaHelper.getPrivateKey(context))
                sendControl(
                    binding,
                    attemptId,
                    controlMessage(
                        WebRtcHandoverControlKind.CHALLENGE,
                        attemptId,
                        nonce = responderNonce,
                        peerNonce = initiatorNonce,
                        timestamp = timestamp,
                        signatureBase64 = Base64.getEncoder().encodeToString(signature),
                    ),
                )
            }
            WebRtcHandoverControlKind.CHALLENGE -> {
                require(localIsInitiator) { "WebRTC responder received an unexpected challenge" }
                val responderNonce = message.nonce.required("challenge nonce")
                val initiatorNonce = message.peerNonce.required("peer nonce")
                require(handover.localNonce == initiatorNonce) {
                    "WebRTC challenge nonce does not match the initiator"
                }
                handover.remoteNonce = responderNonce
                handover.authenticationTimestamp = message.timestamp
                val transcript = buildAuthenticationTranscript(attemptId, wire, message.timestamp)
                require(transcript.verify(device.certificate.publicKey, message.signature())) {
                    "Invalid paired identity signature on WebRTC challenge"
                }
                handover.peerAuthenticated = true
                sendControl(
                    binding,
                    attemptId,
                    controlMessage(
                        WebRtcHandoverControlKind.RESPONSE,
                        attemptId,
                        nonce = initiatorNonce,
                        peerNonce = responderNonce,
                        timestamp = message.timestamp,
                        signatureBase64 = Base64.getEncoder().encodeToString(
                            transcript.sign(RsaHelper.getPrivateKey(context)),
                        ),
                    ),
                )
            }
            WebRtcHandoverControlKind.RESPONSE -> {
                require(!localIsInitiator) { "WebRTC initiator received an unexpected response" }
                val initiatorNonce = message.nonce.required("response nonce")
                val responderNonce = message.peerNonce.required("peer nonce")
                require(
                    handover.remoteNonce == initiatorNonce &&
                        handover.localNonce == responderNonce &&
                        handover.authenticationTimestamp == message.timestamp
                ) { "WebRTC response does not match the active challenge" }
                val transcript = buildAuthenticationTranscript(attemptId, wire, message.timestamp)
                require(transcript.verify(device.certificate.publicKey, message.signature())) {
                    "Invalid paired identity signature on WebRTC response"
                }
                handover.peerAuthenticated = true
                activeTransport.advanceHandover(WebRtcHandoverMessage.AUTHENTICATED)
                sendControl(
                    binding,
                    attemptId,
                    controlMessage(WebRtcHandoverControlKind.AUTHENTICATED, attemptId),
                )
                sendCapabilities(binding, attemptId)
            }
            WebRtcHandoverControlKind.AUTHENTICATED -> {
                require(localIsInitiator && handover.peerAuthenticated) {
                    "WebRTC authentication acknowledgement arrived before peer verification"
                }
                activeTransport.advanceHandover(WebRtcHandoverMessage.AUTHENTICATED)
                sendCapabilities(binding, attemptId)
            }
            WebRtcHandoverControlKind.CAPABILITIES -> {
                verifyRemoteCapabilities(message)
                if (!handover.remoteCapabilitiesReceived) {
                    handover.remoteCapabilitiesReceived = true
                    activeTransport.advanceHandover(WebRtcHandoverMessage.CAPABILITIES)
                }
                sendFeatureReady(binding, attemptId)
            }
            WebRtcHandoverControlKind.FEATURE_READY -> {
                handover.remoteFeatureReadyReceived = true
                if (handover.localFeatureReadySent && handover.remoteFeatureReadyReceived) {
                    if (!activeTransport.isReadyForPackets()) {
                        activeTransport.advanceHandover(WebRtcHandoverMessage.FEATURE_READY)
                    }
                    device.setWebRtcTransport(activeTransport)
                    device.setWebRtcRecoveryPending(false)
                    recoveryInProgress = false
                    val wire = requireNotNull(activeWireBinding)
                    fileExecutor.execute {
                        runCatching { fileTransfers.resumeSends(wire) }
                            .onFailure { publish("Failed", it.message) }
                    }
                    terminalLinkEvent { terminal.onLinkReady(wire) }
                    recovery.reset()
                    startLivenessLocked(binding, attemptId)
                    publish("FeatureReady", "LAN is now bootstrap and signaling only")
                    // Android notifications may have arrived while the
                    // feature channel was still gated. Reconcile only after
                    // the same WebRTC transport is installed for plugins.
                    device.getPlugin(NotificationsPlugin::class.java)?.resyncAfterWebRtcReady()
                    // The person was watching the desktop when the old peer went:
                    // ask again. Control follows the picture in MousePadActivity,
                    // and the session password is offered again with the request.
                    if (resumeScreen.getAndSet(false)) {
                        device.getPlugin(ScreenControlPlugin::class.java)?.onRemoteScreenRequesting()
                        requestRemoteView(WebRtcScreenDirection.DESKTOP_TO_PHONE)
                    }
                }
            }
            WebRtcHandoverControlKind.DEGRADED -> {
                activeTransport.advanceHandover(WebRtcHandoverMessage.DEGRADED)
                terminalLinkEvent { terminal.onLinkLost(wire) }
                device.setWebRtcTransport(null)
                device.setWebRtcRecoveryPending(true)
                recoveryInProgress = true
                publish("Degraded", "WebRTC recovery is required")
            }
            WebRtcHandoverControlKind.CLOSE -> closeLocked()
        }
    }

    private fun sendCapabilities(binding: SessionBinding, attemptId: String) {
        if (handover.localCapabilitiesSent) return
        val local = DeviceHelper.getDeviceInfo(context)
        sendControl(
            binding,
            attemptId,
            controlMessage(
                WebRtcHandoverControlKind.CAPABILITIES,
                attemptId,
                incomingCapabilities = WebRtcFeatureProfile.capabilities(local.incomingCapabilities),
                outgoingCapabilities = WebRtcFeatureProfile.capabilities(local.outgoingCapabilities),
            ),
        )
        handover.localCapabilitiesSent = true
    }

    private fun sendFeatureReady(binding: SessionBinding, attemptId: String) {
        if (handover.localFeatureReadySent) return
        sendControl(
            binding,
            attemptId,
            controlMessage(WebRtcHandoverControlKind.FEATURE_READY, attemptId),
        )
        handover.localFeatureReadySent = true
    }

    private fun sendControl(
        binding: SessionBinding,
        attemptId: String,
        message: WebRtcHandoverControlMessage,
    ) {
        currentTransport(binding, attemptId).sendEnvelope(
            WebRtcChannel.CONTROL,
            WebRtcHandoverControlMessage.MESSAGE_TYPE,
            message.toJson().toString().toByteArray(Charsets.UTF_8),
            System.currentTimeMillis(),
            object : org.desklink.mobile.transport.TransportCallback {
                override fun onSuccess() = Unit
                override fun onFailure(error: org.desklink.mobile.transport.TransportError) {
                    publish("Failed", "Could not send WebRTC handover message: ${error.message}")
                }
            },
        )
    }

    private fun sendRemoteSessionControlLocked(
        binding: SessionBinding,
        attemptId: String,
        message: WebRtcRemoteSessionControlMessage,
    ) {
        currentTransport(binding, attemptId).sendEnvelope(
            WebRtcChannel.CONTROL,
            WebRtcRemoteSessionControlMessage.MESSAGE_TYPE,
            message.toJson().toString().toByteArray(Charsets.UTF_8),
            System.currentTimeMillis(),
            object : org.desklink.mobile.transport.TransportCallback {
                override fun onSuccess() = Unit
                override fun onFailure(error: org.desklink.mobile.transport.TransportError) {
                    publish("RemoteSessionFailed", error.message)
                }
            },
        )
    }

    private fun handleRemoteSessionMessage(
        binding: SessionBinding,
        attemptId: String,
        envelope: WebRtcEnvelope,
    ) = synchronized(lock) {
        val wire = requireNotNull(activeWireBinding) { "No active DeskLink WebRTC wire binding" }
        val payload = envelope.validate(wire.peerDeviceId, wire.sessionId, wire.generation)
        val message = WebRtcRemoteSessionControlMessage.fromJson(
            JSONObject(String(payload, Charsets.UTF_8)),
        )
        // The session transition may clear its direction. Keep the prior
        // direction so an explicit peer stop can also end a local
        // MediaProjection capture that was sending phone pixels to desktop.
        val previousDirection = remoteSession.snapshot().direction
        if (!remoteSession.accept(message, wire, attemptId)) {
            // A late message for a session this side has already left: it
            // changed nothing, and must not stop a capture or clear a track.
            Log.d(TAG, "Ignoring stale remote-session message ${message.kind.wireName}")
            return@synchronized
        }
        when (message.kind) {
            WebRtcRemoteSessionControlKind.REQUEST_VIEW -> {
                val offeredHere = remoteSession.answersOffer(message.remoteSessionId, message.direction)
                if (message.direction == WebRtcScreenDirection.DESKTOP_TO_PHONE) {
                    remoteSession.markScreenReady(WebRtcScreenDirection.DESKTOP_TO_PHONE)
                }
                sendRemoteSessionControlLocked(
                    binding,
                    attemptId,
                    remoteSession.makeMessage(
                        WebRtcRemoteSessionControlKind.VIEW_GRANTED,
                        attemptId,
                        wire,
                        remoteSessionId = message.remoteSessionId,
                    ),
                )
                if (message.direction == WebRtcScreenDirection.PHONE_TO_DESKTOP) {
                    // The plugin starts Android's explicit MediaProjection flow. An answer to this
                    // phone's own offer was asked for here: no "the desk wants your screen" prompt.
                    val request = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SCREEN_REQUEST).apply {
                        this["role"] = ScreenControlPlugin.ROLE_PHONE_SCREEN
                        if (offeredHere) this[ScreenControlPlugin.OFFERED_FIELD] = true
                    }
                    device.getPlugin(ScreenControlPlugin::class.java)?.onPacketReceived(request)
                }
            }
            WebRtcRemoteSessionControlKind.REQUEST_CONTROL,
            WebRtcRemoteSessionControlKind.TAKEOVER_REQUEST -> {
                if (MouseReceiverService.instance == null) {
                    sendRemoteSessionControlLocked(
                        binding,
                        attemptId,
                        remoteSession.makeMessage(
                            WebRtcRemoteSessionControlKind.CONTROL_DENIED,
                            attemptId,
                            wire,
                            remoteSessionId = message.remoteSessionId,
                            reason = "Enable DeskLink accessibility control on this Android device",
                        ),
                    )
                } else {
                    remoteSession.grantPeerControl(message.remoteSessionId, wire)
                    sendRemoteSessionControlLocked(
                        binding,
                        attemptId,
                        remoteSession.makeMessage(
                            WebRtcRemoteSessionControlKind.CONTROL_GRANTED,
                            attemptId,
                            wire,
                            remoteSessionId = message.remoteSessionId,
                        ),
                    )
                }
            }
            WebRtcRemoteSessionControlKind.VIEW_GRANTED,
            WebRtcRemoteSessionControlKind.SCREEN_READY -> {
                if (message.direction == WebRtcScreenDirection.DESKTOP_TO_PHONE) {
                    val screen = device.getPlugin(ScreenControlPlugin::class.java)
                    // Install the track from the live peer now. Relying on the
                    // one-time onAddTrack alone lost it to a plugin reload, and
                    // the desktop's picture decoded but was never shown.
                    transport?.remoteVideoTrack()?.let { track -> screen?.onRemoteVideoTrack(track) }
                    screen?.onRemoteScreenReady(message.screenWidth, message.screenHeight, message.screenCount)
                }
            }
            WebRtcRemoteSessionControlKind.RESUME -> {
                device.getPlugin(ScreenControlPlugin::class.java)?.onRemoteControlStopped()
            }
            WebRtcRemoteSessionControlKind.PAUSE_LOCKED,
            WebRtcRemoteSessionControlKind.SCREEN_STOPPED,
            WebRtcRemoteSessionControlKind.RELEASE -> {
                if (previousDirection == WebRtcScreenDirection.PHONE_TO_DESKTOP) {
                    device.getPlugin(ScreenControlPlugin::class.java)
                        ?.onPhoneCaptureStopped("Phone screen sharing was stopped by the paired device")
                    stopPhoneScreenCapture()
                    context.stopService(Intent(context, PhoneScreenCaptureService::class.java))
                }
                val screen = device.getPlugin(ScreenControlPlugin::class.java)
                if (message.kind == WebRtcRemoteSessionControlKind.PAUSE_LOCKED) {
                    screen?.onRemotePaused()
                } else {
                    screen?.onRemoteSessionEnded()
                }
            }
            WebRtcRemoteSessionControlKind.CONTROL_GRANTED,
            WebRtcRemoteSessionControlKind.TAKEOVER_GRANTED -> {
                device.getPlugin(ScreenControlPlugin::class.java)?.onRemoteControlGranted()
            }
            WebRtcRemoteSessionControlKind.CONTROL_DENIED -> {
                val screen = device.getPlugin(ScreenControlPlugin::class.java)
                passwordDenial(message.reason)?.let { screen?.onPasswordDenied(it, control = true) }
                    ?: screen?.onRemoteControlDenied(message.reason)
            }
            WebRtcRemoteSessionControlKind.INPUT_FOCUS -> {
                device.getPlugin(ScreenControlPlugin::class.java)
                    ?.desktopTextFocused = message.editable == true
            }
            WebRtcRemoteSessionControlKind.VIEW_DENIED,
            WebRtcRemoteSessionControlKind.SCREEN_ERROR -> {
                val screen = device.getPlugin(ScreenControlPlugin::class.java)
                passwordDenial(message.reason)?.let { screen?.onPasswordDenied(it, control = false) }
                    ?: screen?.onRemoteViewFailed(message.reason)
            }
            else -> Unit
        }
        remoteSession.snapshot().let { s ->
            transport?.setScreenControlled(
                s.state == WebRtcRemoteSessionState.CONTROLLING && s.direction == WebRtcScreenDirection.PHONE_TO_DESKTOP,
            )
        }
        updateRemoteHeartbeatLocked(binding, attemptId)
        publish(
            "RemoteSession",
            "${message.kind.wireName} → ${remoteSession.snapshot().state.name}" +
                (message.reason?.let { ": $it" } ?: ""),
        )
    }

    /** A denial for want of the session password. A password that was refused is never offered
     * again, since each one counts as a guess. */
    private fun passwordDenial(reason: String?): SessionPasswordDenial? =
        SessionPassword.denial(reason)?.also { denial ->
            if (denial != SessionPasswordDenial.Required) sessionPassword.set(null)
        }

    /** Keeps an otherwise idle locally owned control lease alive. A peer
     * replacement, pause, release, or takeover cancels this single scheduled
     * task, so stale sessions never continue emitting control traffic. */
    private fun updateRemoteHeartbeatLocked(binding: SessionBinding, attemptId: String) {
        val wire = activeWireBinding ?: run {
            cancelRemoteHeartbeatLocked()
            return
        }
        if (!remoteSession.hasLocalControlLease(wire.generation)) {
            cancelRemoteHeartbeatLocked()
            return
        }
        // A heartbeat already running for this attempt is left alone. It was
        // restarted on every control message, and the first beat comes a full
        // interval after a start: the desktop's own heartbeat (same 10 s),
        // screen switches and focus reports kept resetting it before it ever
        // fired, so the 30 s lease was never renewed and control silently
        // stopped half a minute in.
        if (remoteHeartbeat?.isDone == false && remoteHeartbeatFor == binding to attemptId) return
        cancelRemoteHeartbeatLocked()
        remoteHeartbeatFor = binding to attemptId
        val epoch = remoteHeartbeatEpoch
        remoteHeartbeat = recoveryExecutor.scheduleAtFixedRate({
            synchronized(lock) {
                if (
                    epoch != remoteHeartbeatEpoch || closed || activeBinding != binding || activeAttemptId != attemptId ||
                    !sessions.isCurrent(binding) || transport?.isReadyForPackets() != true
                ) {
                    if (epoch == remoteHeartbeatEpoch) cancelRemoteHeartbeatLocked()
                    return@synchronized
                }
                val heartbeat = remoteSession.makeLocalHeartbeat(attemptId, wire) ?: run {
                    cancelRemoteHeartbeatLocked()
                    remoteSession.dropExpiredLease()
                    // The lease is gone (expired or ended): say so, so the
                    // screen stops claiming control and asks for it again,
                    // instead of every gesture being refused in silence.
                    device.getPlugin(ScreenControlPlugin::class.java)?.onRemoteControlStopped()
                    return@synchronized
                }
                runCatching { sendRemoteSessionControlLocked(binding, attemptId, heartbeat) }
                    .onFailure {
                        cancelRemoteHeartbeatLocked()
                        publish("RemoteSessionFailed", it.message)
                    }
            }
        }, REMOTE_HEARTBEAT_SECONDS, REMOTE_HEARTBEAT_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * Probes the desktop over the data channel after [PEER_PROBE_AFTER_MS] of
     * inbound silence and declares the peer dead after [PEER_DEAD_AFTER_MS].
     * A dead peer is usually a restarted desktop process: without this the
     * phone kept a feature-ready transport to nothing until ICE consent
     * freshness gave up ~25 s later, and every feature sent meanwhile was lost.
     */
    private fun startLivenessLocked(binding: SessionBinding, attemptId: String) {
        cancelLivenessLocked()
        peerLiveness.start(SystemClock.elapsedRealtime())
        liveness = recoveryExecutor.scheduleWithFixedDelay({
            val dead = synchronized(lock) {
                val active = transport
                if (closed || activeAttemptId != attemptId || !sessions.isCurrent(binding) ||
                    active?.isReadyForPackets() != true
                ) {
                    return@synchronized false
                }
                when (peerLiveness.tick(SystemClock.elapsedRealtime())) {
                    PeerLiveness.Verdict.DEAD -> return@synchronized true
                    PeerLiveness.Verdict.PROBE -> active.sendPacket(
                        org.desklink.mobile.heartbeatPacket(
                            reply = false,
                            interactive = context.getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true,
                        ),
                        object : TransportCallback {
                            override fun onSuccess() = Unit
                            override fun onFailure(error: TransportError) {
                                Log.d(TAG, "WebRTC heartbeat failed: ${error.message}")
                            }
                        },
                    )
                    PeerLiveness.Verdict.OK -> Unit
                }
                false
            }
            if (dead) {
                scheduleRecovery(
                    binding,
                    attemptId,
                    "WebRTC peer silent for ${PEER_DEAD_AFTER_MS / 1000}s on every data channel",
                    notifyPeer = false,
                )
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun cancelLivenessLocked() {
        liveness?.cancel(false)
        liveness = null
    }

    /** Which binding and attempt the running heartbeat belongs to. */
    private var remoteHeartbeatFor: Pair<SessionBinding, String>? = null

    private fun cancelRemoteHeartbeatLocked() {
        remoteHeartbeatFor = null
        remoteHeartbeatEpoch += 1
        remoteHeartbeat?.cancel(false)
        remoteHeartbeat = null
    }

    /**
     * Sends one terminal message. Never throws: an unavailable transport is reported through
     * [callback], which is how the client learns the link is down.
     */
    private fun sendTerminalMessage(messageType: String, payload: ByteArray, callback: TransportCallback) {
        val active = synchronized(lock) { transport?.takeIf { it.isReadyForPackets() } }
        if (active == null) {
            callback.onFailure(TransportError(TransportErrorCode.CLOSED, "WebRTC terminal transport is not ready"))
            return
        }
        // Outside `lock`: the send is a native call.
        active.sendEnvelope(WebRtcChannel.TERMINAL, messageType, payload, System.currentTimeMillis(), callback)
    }

    /**
     * Routes one `desklink.terminal.*` envelope to [terminal]. Called on the peer event thread
     * after the envelope was bound to the wire. Replay protection covers control messages only:
     * chunks are offset-tracked by the client, so a replayed one is trimmed, and the desktop
     * skips the cache for them as well.
     */
    private fun handleTerminalEnvelope(
        binding: SessionBinding,
        attemptId: String,
        wire: WebRtcWireBinding,
        envelope: WebRtcEnvelope,
    ) {
        require(envelope.channel == WebRtcChannel.TERMINAL.label) {
            "DeskLink terminal message arrived on the wrong channel"
        }
        require(currentTransport(binding, attemptId).handoverState().featuresAllowed) {
            "DeskLink WebRTC feature handover is incomplete"
        }
        val payload = envelope.validate(wire.peerDeviceId, wire.sessionId, wire.generation)
        when (envelope.messageType) {
            TerminalProtocol.CONTROL_MESSAGE_TYPE -> {
                acceptEnvelopeId(attemptId, envelope.messageId)
                terminal.onControl(wire, payload)
            }
            TerminalProtocol.CHUNK_MESSAGE_TYPE -> terminal.onChunk(wire, payload)
            else -> throw IllegalArgumentException("Unsupported DeskLink terminal message type")
        }
    }

    private fun terminalLinkEvent(action: () -> Unit) {
        try {
            terminalLink.execute(action)
        } catch (_: RejectedExecutionException) {
            // Closed: the client is being shut down with the coordinator.
        }
    }

    private fun sendFileMessage(message: OutboundWebRtcFileMessage) {
        val activeTransport = synchronized(lock) {
            val value = requireNotNull(transport) { "No active WebRTC peer connection" }
            require(value.isReadyForPackets()) { "WebRTC file transport is not ready" }
            value
        }
        val (channel, messageType, payload) = when (message) {
            is OutboundWebRtcFileMessage.Control -> Triple(
                WebRtcChannel.FILE_CONTROL,
                WebRtcFileControl.CONTROL_MESSAGE_TYPE,
                message.value.toJson().toString().toByteArray(Charsets.UTF_8),
            )
            is OutboundWebRtcFileMessage.Chunk -> Triple(
                WebRtcChannel.FILE_DATA,
                WebRtcFileControl.CHUNK_MESSAGE_TYPE,
                message.value,
            )
        }
        val completion = CompletableFuture<Unit>()
        activeTransport.sendEnvelope(
            channel,
            messageType,
            payload,
            System.currentTimeMillis(),
            object : org.desklink.mobile.transport.TransportCallback {
                override fun onSuccess() {
                    completion.complete(Unit)
                }

                override fun onFailure(error: org.desklink.mobile.transport.TransportError) {
                    completion.completeExceptionally(error)
                }
            },
        )
        completion.get(10, TimeUnit.SECONDS)
    }

    private fun controlMessage(
        kind: WebRtcHandoverControlKind,
        attemptId: String,
        nonce: String? = null,
        peerNonce: String? = null,
        timestamp: Long = System.currentTimeMillis(),
        signatureBase64: String? = null,
        incomingCapabilities: List<String> = emptyList(),
        outgoingCapabilities: List<String> = emptyList(),
    ): WebRtcHandoverControlMessage {
        val wire = requireNotNull(activeWireBinding)
        return WebRtcHandoverControlMessage(
            handoverVersion = WebRtcHandoverControlMessage.VERSION,
            kind = kind,
            sessionAttemptId = attemptId,
            deviceId = DeviceHelper.getDeviceId(context),
            sessionId = wire.sessionId,
            connectionGeneration = wire.generation,
            nonce = nonce,
            peerNonce = peerNonce,
            timestamp = timestamp,
            signatureBase64 = signatureBase64,
            incomingCapabilities = incomingCapabilities,
            outgoingCapabilities = outgoingCapabilities,
        )
    }

    private fun buildAuthenticationTranscript(
        attemptId: String,
        wire: WebRtcWireBinding,
        timestamp: Long,
    ): WebRtcAuthenticationTranscript {
        val offer = requireNotNull(handover.offerSdp) { "WebRTC authentication is missing the offer SDP" }
        val answer = requireNotNull(handover.answerSdp) { "WebRTC authentication is missing the answer SDP" }
        val localNonce = requireNotNull(handover.localNonce) { "WebRTC authentication is missing the local nonce" }
        val remoteNonce = requireNotNull(handover.remoteNonce) { "WebRTC authentication is missing the peer nonce" }
        val localDeviceId = DeviceHelper.getDeviceId(context)
        val localIsInitiator = localDeviceId < device.deviceId
        return WebRtcAuthenticationTranscript(
            sessionAttemptId = attemptId,
            initiatorDeviceId = if (localIsInitiator) localDeviceId else device.deviceId,
            responderDeviceId = if (localIsInitiator) device.deviceId else localDeviceId,
            sessionId = wire.sessionId,
            connectionGeneration = wire.generation,
            initiatorNonce = if (localIsInitiator) localNonce else remoteNonce,
            responderNonce = if (localIsInitiator) remoteNonce else localNonce,
            offerSha256 = sha256Hex(offer),
            answerSha256 = sha256Hex(answer),
            initiatorDtlsFingerprint = dtlsFingerprint(offer),
            responderDtlsFingerprint = dtlsFingerprint(answer),
            protocolVersion = 1,
            timestamp = timestamp,
        )
    }

    /**
     * The peer's handover lists may claim less than its authenticated identity (an older or newer
     * build), never more. What it confirms is then the intersection used for every feature send
     * and receive with it ([WebRtcFeatureProfile.peerAccepts] / [WebRtcFeatureProfile.peerSends]).
     */
    private fun verifyRemoteCapabilities(message: WebRtcHandoverControlMessage) {
        require(WebRtcFeatureProfile.handoverListIsConsistent(message.incomingCapabilities, device.deviceInfo.incomingCapabilities)) {
            "WebRTC incoming capabilities claim more than the authenticated device identity"
        }
        require(WebRtcFeatureProfile.handoverListIsConsistent(message.outgoingCapabilities, device.deviceInfo.outgoingCapabilities)) {
            "WebRTC outgoing capabilities claim more than the authenticated device identity"
        }
        WebRtcFeatureProfile.recordPeer(device.deviceId, message.incomingCapabilities, message.outgoingCapabilities)
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun dtlsFingerprint(sdp: String): String = sdp.lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("a=fingerprint:sha-256 ") }
        ?.removePrefix("a=fingerprint:sha-256 ")
        ?.takeIf(String::isNotEmpty)
        ?: error("WebRTC SDP has no SHA-256 DTLS fingerprint")

    private fun String?.required(label: String): String =
        requireNotNull(this?.takeIf { it.isNotEmpty() && it.length <= 256 }) {
            "WebRTC handover has no valid $label"
        }

    private fun WebRtcHandoverControlMessage.signature(): ByteArray {
        val encoded = requireNotNull(signatureBase64) { "WebRTC authentication signature is missing" }
        require(encoded.length <= 16 * 1024) { "WebRTC authentication signature is too large" }
        return Base64.getDecoder().decode(encoded)
    }

    private fun sendSignal(
        binding: SessionBinding,
        attemptId: String,
        type: SignalingMessageType,
        payload: JSONObject,
    ) {
        if (!sessions.isCurrent(binding)) return
        if (!device.hasBootstrapLink) {
            publish("WaitingForBootstrap", "Re-establishing local signaling")
            requestBootstrapReconnect()
            return
        }
        val message = WebRtcSignalingMessage(
            signalingVersion = 1,
            requestId = UUID.randomUUID().toString(),
            sessionAttemptId = attemptId,
            fromDeviceId = DeviceHelper.getDeviceId(context),
            toDeviceId = device.deviceId,
            timestamp = System.currentTimeMillis(),
            messageType = type,
            payload = payload,
            signature = "",
        ).sign(RsaHelper.getPrivateKey(context))
        Log.i(TAG, "${device.deviceId}: sending bootstrap ${type.name}")
        device.sendBootstrapPacket(message.toNetworkPacket(), object : Device.SendPacketStatusCallback() {
            override fun onSuccess() {
                Log.i(TAG, "${device.deviceId}: sent bootstrap ${type.name}")
            }
            override fun onFailure(e: Throwable) {
                publish("Failed", "Could not send WebRTC signaling: ${e.message}")
                requestBootstrapReconnect()
            }
        })
    }

    private fun startInitiatorLocked(binding: SessionBinding, iceServers: List<PeerConnection.IceServer>) {
        require(!closed) { "DeskLink WebRTC coordinator is closed" }
        val attemptId = UUID.randomUUID().toString()
        activeAttemptId = attemptId
        attemptStartedAt = SystemClock.elapsedRealtime()
        activeBinding = binding
        activeWireBinding = WebRtcWireBinding.fromAttempt(
            DeviceHelper.getDeviceId(context),
            device.deviceId,
            attemptId,
        )
        handover = HandoverRuntime()
        transport = createTransport(binding, attemptId, createLocalChannels = true, iceServers)
        publish("CreatingOffer", null)
        if (transport?.createOffer() == true) {
            armOfferWatchdogLocked(binding, attemptId)
        } else {
            publish("Failed", "Could not start WebRTC offer creation")
        }
    }

    private fun scheduleRecovery(
        binding: SessionBinding,
        attemptId: String,
        reason: String,
        notifyPeer: Boolean,
    ) {
        val delay = synchronized(lock) {
            if (closed || !sessions.isCurrent(binding) || activeAttemptId != attemptId) return
            cancelDisconnectedGraceLocked()
            cancelOfferWatchdogLocked()
            val claimed = recovery.claimDelayMillis() ?: return
            device.setWebRtcTransport(null)
            device.setWebRtcRecoveryPending(true)
            recoveryInProgress = true
            publish("Degraded", reason)
            claimed
        }
        recoveryExecutor.execute {
            if (notifyPeer && device.hasBootstrapLink) {
                runCatching {
                    sendSignal(binding, attemptId, SignalingMessageType.ICE_RESTART, JSONObject())
                }.onFailure { publish("Failed", "Could not request WebRTC recovery: ${it.message}") }
            } else if (!device.hasBootstrapLink) {
                requestBootstrapReconnect()
            }
            synchronized(lock) {
                if (activeAttemptId == attemptId) closeLocked()
            }
            if (DeviceHelper.getDeviceId(context) >= device.deviceId) {
                // The responder never schedules an initiation, so it has to
                // release the single-slot claim here -- the initiator's release
                // lives inside the scheduled block below, which this return
                // never reaches.
                //
                // Returning with the claim still held left `scheduled` true
                // forever, so every later claimDelayMillis() returned null and
                // scheduleRecovery bailed at its early return -- *after* having
                // already cancelled the disconnect grace and the offer
                // watchdog. The responder became a permanent no-op on failure,
                // holding a power lease, waiting for an offer that nothing
                // would ever prompt.
                recovery.release()
                publish("WaitingForRecoveryOffer", null)
                return@execute
            }
            recoveryExecutor.schedule({
                val current = sessions.currentBinding(device.deviceId)
                recovery.release()
                if (current == null || !sessions.isCurrent(current)) return@schedule
                if (synchronized(lock) { closed || activeAttemptId != null }) return@schedule
                // Fetched before the lock: the relay's TURN grant may take 2 s.
                val iceServers = runCatching { iceServersForPath() }
                synchronized(lock) {
                    if (closed || activeAttemptId != null) return@synchronized
                    runCatching { startInitiatorLocked(current, iceServers.getOrThrow()) }
                        .onFailure { error ->
                            publish("Failed", error.message)
                            scheduleRecovery(current, attemptId, error.message ?: "WebRTC recovery failed", false)
                        }
                }
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * `DISCONNECTED` is transient in WebRTC and often appears briefly while
     * Android turns the display off or switches Wi-Fi power state. Do not tear
     * down a viable peer on that first callback; wait for it to recover before
     * starting the bounded rebuild path used for a real failure.
     */
    private fun scheduleDisconnectedGrace(
        binding: SessionBinding,
        attemptId: String,
        reason: String,
    ) {
        val scheduled = synchronized(lock) {
            if (closed || !sessions.isCurrent(binding) || activeAttemptId != attemptId) {
                return@synchronized false
            }
            if (disconnectedGraceAttemptId == attemptId) {
                return@synchronized false
            }
            disconnectedGraceAttemptId = attemptId
            device.setWebRtcRecoveryPending(true)
            // A rebuild may still be coming, so the session must be retained
            // for the whole grace window.
            //
            // Without this the window protected nothing. ICE DISCONNECTED has
            // already forced TransportState.FAILED, so isReadyForPackets() is
            // false throughout -- and shouldRetainSessionAfterBootstrapLoss()
            // is `recoveryInProgress || (binding current && transport ready)`.
            // Both halves false meant that if the LAN socket dropped in the
            // same Wi-Fi blip, the coordinator and its peer were destroyed 12
            // seconds before the grace timer this function exists to arm would
            // ever have fired.
            recoveryInProgress = true
            disconnectedGrace = recoveryExecutor.schedule({
                val recover = synchronized(lock) {
                    if (
                        closed ||
                        !sessions.isCurrent(binding) ||
                        activeAttemptId != attemptId ||
                        disconnectedGraceAttemptId != attemptId
                    ) {
                        false
                    } else {
                        disconnectedGrace = null
                        disconnectedGraceAttemptId = null
                        true
                    }
                }
                if (recover) {
                    scheduleRecovery(binding, attemptId, reason, notifyPeer = true)
                }
            }, TRANSIENT_DISCONNECT_GRACE_MILLIS, TimeUnit.MILLISECONDS)
            true
        }
        if (scheduled) {
            publish(
                "Degraded",
                "Waiting briefly for the WebRTC peer to recover before rebuilding it",
            )
        }
    }

    private fun cancelDisconnectedGraceLocked() {
        disconnectedGrace?.cancel(false)
        disconnectedGrace = null
        disconnectedGraceAttemptId = null
    }

    private fun requestBootstrapReconnect() {
        (context.applicationContext as? org.desklink.mobile.DeskLinkApplication)
            ?.requestBootstrapReconnect()
    }

    /**
     * See [selectIceServers]. The relay grant is requested only for a peer
     * bootstrapped through the relay with "Use TURN" on (docs/RELAY.md §5).
     * Blocks up to 2 s for the grant: never call it under [lock] or on the main thread.
     */
    private fun iceServersForPath(): List<PeerConnection.IceServer> {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val useTurn = RelaySettings.useTurn(context)
        val lan = device.hasLanLink
        return selectIceServers(
            hasLanLink = lan,
            useTurn = useTurn,
            configuredStun = configuredIceServers(
                preferences.getString(DeviceHelper.KEY_WEBRTC_STUN_SERVERS_PREFERENCE, "").orEmpty(),
                setOf("stun", "stuns"),
            ),
            configuredTurn = if (!useTurn) emptyList() else configuredIceServers(
                preferences.getString(DeviceHelper.KEY_WEBRTC_TURN_SERVERS_PREFERENCE, "").orEmpty(),
                setOf("turn", "turns"),
            ),
            granted = if (lan || !useTurn) emptyList()
                else RelayLinkProvider.instance?.iceServersFor(device.deviceId).orEmpty(),
        ).map(IceServerSpec::toIceServer)
    }

    private fun configuredIceServers(value: String, schemes: Set<String>): List<IceServerSpec> =
        parseServerList(value, schemes).map { raw ->
            val uri = URI(raw)
            val endpoint = buildString {
                append(uri.scheme).append(':').append(uri.host)
                if (uri.port >= 0) append(':').append(uri.port)
                if (!uri.query.isNullOrEmpty()) append('?').append(uri.query)
            }
            val credentials = uri.userInfo?.split(':', limit = 2)
            IceServerSpec(listOf(endpoint), credentials?.first(), credentials?.getOrNull(1))
        }

    private fun parseServerList(value: String, schemes: Set<String>): List<String> = value
        .split(',', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .also { require(it.size <= 8) { "At most eight ICE servers may be configured" } }
        .onEach { raw ->
            val uri = URI(raw)
            require(uri.scheme in schemes && !uri.host.isNullOrEmpty()) {
                "Unsupported ICE server URI: $raw"
            }
        }

    private fun currentTransport(binding: SessionBinding, attemptId: String): WebRtcTransport = synchronized(lock) {
        require(sessions.isCurrent(binding)) { "Stale DeskLink session" }
        require(activeAttemptId == attemptId && activeBinding?.let(sessions::isCurrent) == true) {
            "Stale WebRTC negotiation"
        }
        requireNotNull(transport) { "No active WebRTC peer connection" }
    }

    private fun closeLocked(reloadPlugins: Boolean = true) {
        cancelLivenessLocked()
        cancelAnswerWatchdogLocked()
        cancelDisconnectedGraceLocked()
        cancelRemoteHeartbeatLocked()
        cancelOfferWatchdogLocked()
        // The file manager's methods hold the manager's monitor and call back
        // into this coordinator for `lock`; closing it here, *under* `lock`, is
        // the other half of that lock-order cycle. The cleanup thread does it.
        transportCleanup.execute { fileTransfers.close("WebRTC session closed") }
        // The peer is going away, so its remote session is over: a stale
        // controller would leave the UI "enabled" and every gesture failing.
        val remote = remoteSession.snapshot()
        val hadRemoteSession = remote.remoteSessionId != null
        if (remote.direction == WebRtcScreenDirection.DESKTOP_TO_PHONE && remote.state in RESUMABLE_REMOTE_STATES) {
            resumeScreen.set(true)
        }
        remoteSession.stop()
        device.getPlugin(ScreenControlPlugin::class.java)?.let { screen ->
            screen.clearRemoteVideoTrack()
            if (transport != null) screen.onCapturePeerClosed()
            if (hadRemoteSession) {
                screen.onRemoteSessionEnded(context.getString(R.string.remote_screen_interrupted))
            }
        }
        val previousTransport = transport
        activeWireBinding?.let { closing -> terminalLinkEvent { terminal.onLinkLost(closing) } }
        transport = null
        activeAttemptId = null
        activeBinding = null
        activeWireBinding = null
        handover = HandoverRuntime()
        device.setWebRtcTransport(null, reloadPlugins)
        // DataChannel/PeerConnection disposal can wait for native callbacks
        // that re-enter this coordinator. Never perform it under `lock`.
        previousTransport?.let { previous ->
            transportCleanup.execute {
                previous.close(org.desklink.mobile.transport.DisconnectReason.REPLACED)
            }
        }
    }

    /** Arms one timeout for the local offer operation. Native WebRTC is
     * asynchronous and can otherwise leave the UI permanently at
     * "CreatingOffer" without producing a callback. */
    private fun armOfferWatchdogLocked(binding: SessionBinding, attemptId: String) {
        cancelOfferWatchdogLocked()
        offerWatchdog = recoveryExecutor.schedule({
            val timedOut = synchronized(lock) {
                if (
                    closed ||
                    !sessions.isCurrent(binding) ||
                    activeBinding != binding ||
                    activeAttemptId != attemptId ||
                    transport?.offerOperationState() == OfferOperationState.COMPLETED
                ) {
                    false
                } else {
                    offerWatchdog = null
                    true
                }
            }
            if (timedOut) {
                Log.w(TAG, "WebRTC offer creation timed out")
                publish("Failed", "Timed out while creating the WebRTC offer")
                scheduleRecovery(
                    binding,
                    attemptId,
                    "Timed out while creating the WebRTC offer",
                    notifyPeer = false,
                )
            }
        }, OFFER_CREATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    }

    /**
     * Re-offers when the desktop never answers. Nothing else noticed: with no
     * remote description ICE never starts, so no FAILED callback arrives, and
     * the phone waited for a heartbeat-driven restart up to 20 s later.
     */
    private fun armAnswerWatchdogLocked(binding: SessionBinding, attemptId: String) {
        cancelAnswerWatchdogLocked()
        answerWatchdog = recoveryExecutor.schedule({
            val unanswered = synchronized(lock) {
                answerWatchdog = null
                !closed && activeAttemptId == attemptId && sessions.isCurrent(binding) &&
                    handover.answerSdp == null
            }
            if (unanswered) {
                scheduleRecovery(binding, attemptId, "No answer to the WebRTC offer", notifyPeer = false)
            }
        }, ANSWER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun cancelAnswerWatchdogLocked() {
        answerWatchdog?.cancel(false)
        answerWatchdog = null
    }

    private fun cancelOfferWatchdogLocked() {
        offerWatchdog?.cancel(false)
        offerWatchdog = null
    }

    private fun acceptRequestId(requestId: String) = synchronized(seenRequestIds) {
        require(seenRequestIds.add(requestId)) { "Replayed WebRTC signaling request" }
        while (seenRequestIds.size > MAX_SEEN_REQUESTS) {
            seenRequestIds.iterator().run { next(); remove() }
        }
    }

    /**
     * Records a data-channel envelope ID, rejecting a repeat.
     *
     * The attempt ID is part of the key rather than a scalar the cache is reset
     * on. Two transports are briefly alive during a peer replacement — the old
     * peer's callbacks are not stopped synchronously — so a single "current
     * attempt" field was flipped back and forth by interleaved envelopes and
     * cleared the whole set on nearly every message. That defeated replay
     * protection precisely during the window it exists to cover.
     *
     * `LinkedHashSet` eviction is oldest-first, so a recently seen ID is never
     * dropped ahead of an old one.
     */
    private fun acceptEnvelopeId(attemptId: String, messageId: String) =
        synchronized(seenEnvelopeIds) {
            require(messageId.isNotEmpty()) { "WebRTC envelope has no message ID" }
            require(seenEnvelopeIds.add("$attemptId|$messageId")) { "Replayed WebRTC envelope" }
            while (seenEnvelopeIds.size > MAX_SEEN_REQUESTS) {
                seenEnvelopeIds.iterator().run { next(); remove() }
            }
        }

    // Paired features have no LAN fallback. Disabling WebRTC would therefore
    // disable the product while discovery still appeared connected.
    private fun isEnabled(): Boolean = true

    private fun remoteAcceptsSignal(): Boolean = device.deviceInfo.incomingCapabilities
        ?.contains(DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1) == true

    private fun publish(state: String, detail: String?) {
        Log.i(TAG, "${device.deviceId}: $state${detail?.let { " ($it)" } ?: ""}")
        onStateChanged(state, detail)
    }

    private fun requiredString(payload: JSONObject, key: String, maximum: Int): String {
        val value = payload.getString(key)
        require(value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= maximum) {
            "Invalid WebRTC signaling $key"
        }
        return value
    }

    companion object {
        private const val TAG = "DeskLink/WebRTC"
        private val RESUMABLE_REMOTE_STATES = setOf(
            WebRtcRemoteSessionState.VIEWING,
            WebRtcRemoteSessionState.REQUESTING_CONTROL,
            WebRtcRemoteSessionState.CONTROLLING,
            WebRtcRemoteSessionState.PAUSED_LOCKED,
            WebRtcRemoteSessionState.RECONNECTING,
        )
        private const val TERMINAL_MESSAGE_PREFIX = "desklink.terminal."
        private const val MAX_SDP_BYTES = 256 * 1024
        private const val MAX_CANDIDATE_BYTES = 16 * 1024
        private const val MAX_SEEN_REQUESTS = 4096
        private const val TRANSIENT_DISCONNECT_GRACE_MILLIS = 12_000L
        private const val OFFER_CREATION_TIMEOUT_MILLIS = 10_000L
        private const val REMOTE_HEARTBEAT_SECONDS = 10L
        const val PEER_PROBE_AFTER_MS = 5_000L
        const val PEER_DEAD_AFTER_MS = 15_000L
        const val ATTEMPT_STALL_MS = 20_000L
        // The measured LAN offer→answer is ~0.3 s; 5 s leaves room for the
        // desktop building its first GStreamer pipeline.
        const val ANSWER_TIMEOUT_MS = 5_000L
        /** A little longer than the desktop's own two-minute portal timeout. */
        private const val REMOTE_REQUEST_TIMEOUT_SECONDS = 135L

        @JvmStatic
        fun shouldInitiateForRestartRequest(localDeviceId: String, remoteDeviceId: String): Boolean =
            localDeviceId < remoteDeviceId

        @JvmStatic
        fun shouldRestartAfterBootstrapReconnect(
            localDeviceId: String,
            remoteDeviceId: String,
            hasActiveFeatureTransport: Boolean,
        ): Boolean = !hasActiveFeatureTransport && localDeviceId < remoteDeviceId
    }
}
