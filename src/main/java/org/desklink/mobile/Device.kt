/*
 * SPDX-FileCopyrightText: 2025 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.desklink.mobile

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import androidx.annotation.AnyThread
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.apache.commons.collections4.MultiValuedMap
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap
import org.desklink.mobile.backends.BaseLink
import org.desklink.mobile.backends.BaseLink.PacketReceiver
import org.desklink.mobile.backends.lan.LanLink
import org.desklink.mobile.DeviceInfo.Companion.loadFromSettings
import org.desklink.mobile.DeviceStats.countReceived
import org.desklink.mobile.DeviceStats.countSent
import org.desklink.mobile.helpers.DeviceHelper
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.helpers.TrustedDevices
import org.desklink.mobile.PairingHandler.PairingCallback
import org.desklink.mobile.lifecycle.DeviceUnpairCoordinator
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.Plugin.Companion.getPluginKey
import org.desklink.mobile.plugins.PluginFactory
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.webrtc.BoundedPendingQueue
import org.desklink.mobile.webrtc.WebRtcFeatureProfile
import org.desklink.mobile.webrtc.WebRtcTransport
import org.desklink.mobile.session.PairingState
import org.desklink.mobile.transport.LogicalChannel
import org.desklink.mobile.transport.SessionTransport
import org.desklink.mobile.transport.TransportCallback
import org.desklink.mobile.transport.TransportError
import org.desklink.mobile.ui.MainActivity
import org.desklink.mobile.R
import java.io.IOException
import java.security.cert.Certificate
import org.desklink.mobile.helpers.security.SslHelper
import java.util.Vector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class Device : PacketReceiver {

    data class NetworkPacketWithCallback(val np : NetworkPacket, val callback: SendPacketStatusCallback)

    val context: Context

    @VisibleForTesting
    var deviceInfo: DeviceInfo
        private set

    /**
     * The notification ID for the pairing notification.
     * This ID should be only set once, and it should be unique for each device.
     * We use the current time in milliseconds as the ID as default.
     */
    private var notificationId = 0

    @VisibleForTesting
    var pairingHandler: PairingHandler

    private val links = CopyOnWriteArrayList<BaseLink>()

    /** Current LAN control transport owned by DeskLinkApplication's session manager. */
    @Volatile
    private var sessionTransport: SessionTransport? = null

    /** Authenticated data-channel transport. LAN remains bootstrap/signaling-only. */
    @Volatile
    private var webRtcTransport: WebRtcTransport? = null

    /**
     * A short WebRTC recovery attempt still needs CPU time while the display is
     * off.  It is deliberately separate from reachability: the UI must not
     * present a recovering peer as ready for feature traffic.
     */
    @Volatile
    private var webRtcRecoveryPending: Boolean = false

    /** When recovery started pending; bounds how long it may hold the wake lock. */
    @Volatile
    private var webRtcRecoveryPendingSince = 0L

    /**
     * Packet types that ride the pinned-TLS bootstrap link rather than the
     * feature transport, and are therefore exempt from the feature profile and
     * from pairing-state filtering.
     *
     * One definition on purpose. This list was written out three separate times
     * — in dispatch, in `sendBootstrapPacket` and in `sendPacketBlocking` — and
     * a type added to one copy but not the others is silently accepted on
     * receive and refused on send, or the reverse. Adding the heartbeat is
     * exactly the change that would have drifted.
     */
    private fun isBootstrapPacketType(type: String): Boolean =
        type == NetworkPacket.PACKET_TYPE_IDENTITY ||
            type == NetworkPacket.PACKET_TYPE_PAIR ||
            type == DeskLinkProtocol.PACKET_TYPE_PAIR_QR ||
            type == DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1 ||
            type == DeskLinkProtocol.PACKET_TYPE_HEARTBEAT

    fun interface PayloadPacketHandler {
        fun sendPayload(packet: NetworkPacket, callback: SendPacketStatusCallback): Boolean
    }

    @Volatile
    private var payloadPacketHandler: PayloadPacketHandler? = null

    /** Paired-session control packets that are not plugin capabilities. */
    fun interface ControlPacketHandler {
        fun onControlPacket(packet: NetworkPacket): Boolean
    }

    @Volatile
    private var controlPacketHandler: ControlPacketHandler? = null
    private val controlPacketLock = Any()
    private val pendingControlPackets = BoundedPendingQueue<NetworkPacket>(8)

    /** The peer's last seen restart epoch; see [DeviceInfo.instanceId]. */
    @Volatile
    var peerInstanceId: String? = null

    /** Delta resync: which snapshots the peer already holds (and which of its this side holds). */
    val sync = org.desklink.mobile.webrtc.SyncDigest { DeviceInfo.PROCESS_INSTANCE_ID }

    /**
     * A snapshot of [feature] on a new link: stamped with [rev] and sent, unless the peer has just
     * said it already holds exactly this one. Returns whether it was sent.
     */
    fun sendSnapshot(feature: String, rev: String, packets: List<NetworkPacket>, send: (NetworkPacket) -> Unit = ::sendPacket): Boolean {
        if (!sync.shouldSend(feature, rev)) {
            Log.d("DeskLink/Device", "The desk already holds the $feature snapshot")
            return false
        }
        packets.forEach { it["rev"] = rev; send(it) }
        return true
    }
    // Feature packets waiting for the WebRTC transport; see sendPacketBlocking.
    private val pendingFeaturePackets = BoundedPendingQueue<QueuedFeature>(256)

    private class QueuedFeature(
        val packet: NetworkPacket,
        val callback: SendPacketStatusCallback,
        val queuedAt: Long = android.os.SystemClock.elapsedRealtime(),
    )

    /**
     * The WebRTC coordinator supplies a generation-bound control lease just
     * before remote-input packets are sent. Plugins never create leases, so a
     * queued packet cannot be reused after a peer replacement.
     */
    fun interface RemoteInputPacketPreparer {
        fun prepare(packet: NetworkPacket): NetworkPacket
    }

    @Volatile
    private var remoteInputPacketPreparer: RemoteInputPacketPreparer? = null

    /**
     * Plugins that have matching capabilities.
     */
    var supportedPlugins: List<String>
        private set

    /**
     * Plugins that have been instantiated successfully. A subset of supportedPlugins.
     */
    val loadedPlugins: ConcurrentMap<String, Plugin> = ConcurrentHashMap()

    /**
     * Plugins that have not been instantiated because of missing permissions.
     * The supportedPlugins that aren't in loadedPlugins will be here.
     */
    val pluginsWithoutPermissions: ConcurrentMap<String, Plugin> = ConcurrentHashMap()

    /**
     * Subset of loadedPlugins that, despite being able to run, will have some limitation because of missing permissions.
     */
    val pluginsWithoutOptionalPermissions: ConcurrentMap<String, Plugin> = ConcurrentHashMap()

    /**
     * Same as loadedPlugins but indexed by incoming packet type
     */
    private var pluginsByIncomingInterface: MultiValuedMap<String, String> = ArrayListValuedHashMap()

    private val pairingCallbacks = CopyOnWriteArrayList<PairingCallback>()
    private val pluginsChangedListeners = CopyOnWriteArrayList<PluginsChangedListener>()

    /** Interactive packets before bulk ones (contacts, file requests, manifests); a full lane is reported. */
    private val outbound = OutboundQueue<NetworkPacketWithCallback>()

    /** Files in flight (their own threads, [FILE_SENDS_IN_FLIGHT] at a time) and waiting; the rest are refused and reported. */
    private val fileSendPool = java.util.concurrent.ThreadPoolExecutor(
        FILE_SENDS_IN_FLIGHT, FILE_SENDS_IN_FLIGHT, 30, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(FILE_SENDS_WAITING),
    ).apply { allowCoreThreadTimeOut(true) }
    private var sendCoroutine : Job? = null

    /** Pairing generation used to make local and remote unpair callbacks idempotent. */
    private val pairingGeneration = AtomicLong(1)
    private val lifecycle = DeviceUnpairCoordinator()

    /**
     * Constructor for remembered, already-trusted devices.
     * Given the deviceId, it will load the other properties from SharedPreferences.
     */
    internal constructor(context: Context, deviceId: String) {
        this.context = context
        this.deviceInfo = loadFromSettings(context, deviceId)
        this.pairingHandler = PairingHandler(this, createDefaultPairingCallback(), PairingHandler.PairState.Paired)
        this.supportedPlugins = Vector(PluginFactory.availablePlugins) // Assume all are supported until we receive capabilities
        Log.i("Device", "Loading trusted device: ${deviceInfo.name}")
    }

    /**
     * Constructor for devices discovered but not trusted yet.
     * Gets the DeviceInfo by calling link.getDeviceInfo() on the link passed.
     * This constructor also calls addLink() with the link you pass to it, since it's not legal to have an unpaired Device with 0 links.
     */
    internal constructor(context: Context, link: BaseLink) {
        this.context = context
        this.deviceInfo = link.deviceInfo
        this.pairingHandler = PairingHandler(this, createDefaultPairingCallback(), PairingHandler.PairState.NotPaired)
        this.supportedPlugins = Vector(PluginFactory.availablePlugins) // Assume all are supported until we receive capabilities
        Log.i("Device", "Creating untrusted device: " + deviceInfo.name)
        addLink(link)
    }

    fun supportsPacketType(type: String): Boolean =
        NetworkPacket.PROTOCOL_PACKET_TYPES.contains(type) || deviceInfo.incomingCapabilities?.contains(type) ?: true

    fun interface PluginsChangedListener {
        fun onPluginsChanged(device: Device)
    }

    val connectivityType: String?
        get() = links.firstOrNull()?.name

    val name: String
        get() = deviceInfo.name

    val icon: Drawable
        get() = deviceInfo.type.getIcon(context)

    val iconDrawable: Int
        @DrawableRes
        get() = deviceInfo.type.toDrawableId()

    val deviceType: DeviceType
        get() = deviceInfo.type

    val protocolVersion: Int
        get() = deviceInfo.protocolVersion

    val deviceId: String
        get() = deviceInfo.id

    // While unpaired, the certificate that counts is the current link's: the
    // verification key is derived from it and it is what gets pinned. The one
    // captured when this Device was created can belong to an older link.
    val certificate: Certificate
        get() = if (isPaired) {
            deviceInfo.certificate
        } else {
            links.firstOrNull()?.deviceInfo?.certificate ?: deviceInfo.certificate
        }

    val verificationKey: String?
        get() = pairingHandler.verificationKey()

    // Returns 0 if the version matches, < 0 if it is older or > 0 if it is newer
    fun compareProtocolVersion(): Int =
        deviceInfo.protocolVersion - DeviceHelper.PROTOCOL_VERSION

    val isPaired: Boolean
        get() = pairingHandler.state == PairingHandler.PairState.Paired

    val pairStatus : PairingHandler.PairState
        get() = pairingHandler.state

    fun addPairingCallback(callback: PairingCallback) = pairingCallbacks.add(callback)

    fun removePairingCallback(callback: PairingCallback) = pairingCallbacks.remove(callback)

    fun requestPairing() = pairingHandler.requestPairing()

    fun unpair() = pairingHandler.unpair()

    /* This method is called after accepting pair request form GUI */
    fun acceptPairing(expectedTimestamp: Long? = null) {
        Log.i("Device", "Accepted pair request started by the other device")
        pairingHandler.acceptPairing(expectedTimestamp)
    }

    fun rejectPairingRequest(expectedTimestamp: Long) {
        pairingHandler.rejectPairingRequest(expectedTimestamp)
    }

    /* This method is called after rejecting pairing from GUI */
    fun cancelPairing() {
        Log.i("Device", "This side cancelled the pair request")
        pairingHandler.cancelPairing()
    }

    private fun createDefaultPairingCallback(): PairingCallback {
        return object : PairingCallback {
            override fun incomingPairRequest() {
                displayPairingNotification()
                pairingCallbacks.forEach(PairingCallback::incomingPairRequest)
            }

            override fun pairingSuccessful() {
                Log.i("Device", "pairing successful, adding to trusted devices list")

                hidePairingNotification()

                // Pin the certificate of the link the pairing was negotiated
                // on, then mark trusted — certificate first, both committed
                // synchronously. Two async writes could land half-done, and a
                // trusted flag with no certificate refuses every future link.
                // (Read from the link directly: the state is already Paired
                // here, so `certificate` would return the stale one.)
                val negotiated = pairingHandler.negotiatedCertificate
                    ?: links.firstOrNull()?.deviceInfo?.certificate
                    ?: deviceInfo.certificate
                deviceInfo = deviceInfo.copy(certificate = negotiated)
                deviceInfo.saveInSettings(context)
                TrustedDevices.addTrustedDevice(context, deviceInfo.id)
                updateSessionPairingState(PairingState.PAIRED)
                val generation = pairingGeneration.incrementAndGet()

                try {
                    lifecycle.enqueue("plugin reload after pairing generation $generation") {
                        reloadPluginsFromSettings()
                    }

                    pairingCallbacks.forEach(PairingCallback::pairingSuccessful)
                } catch (e: Exception) {
                    Log.e("Device", "Exception in pairingSuccessful. Not unpairing because saving the trusted device succeeded", e)
                }
            }

            override fun pairingFailed(error: String) {
                hidePairingNotification()
                pairingCallbacks.forEach { it.pairingFailed(error) }
            }

            override fun unpaired(device: Device) {
                assert(device == this@Device)
                Log.i("Device", "unpaired, removing from trusted devices list")
                TrustedDevices.removeTrustedDevice(context, deviceInfo.id)
                updateSessionPairingState(PairingState.NOT_PAIRED)

                val generation = pairingGeneration.get()
                lifecycle.beginUnpair(generation) {
                    // Close the peer before plugin teardown so late callbacks
                    // cannot recreate a transport for an untrusted device.
                    // This runs on the lifecycle worker, never on the menu
                    // thread.
                    runCatching {
                        DeskLinkApplication.getInstance().beginDeviceUnpair(this@Device)
                    }.onFailure { error ->
                        Log.e("Device", "Failed to close transports during unpair", error)
                    }
                    notifyPluginsOfDeviceUnpaired(context, deviceInfo.id)
                    reloadPluginsFromSettings()
                }

                pairingCallbacks.forEach { it.unpaired(this@Device) }
            }
        }
    }

    private fun updateSessionPairingState(state: PairingState) {
        // Device unit tests and a few library-style callers can construct a
        // Device before Application.onCreate(). Pairing must still complete;
        // session state is an optional runtime integration in that case.
        runCatching {
            DeskLinkApplication.getInstance().sessionManager.updatePairingState(deviceInfo.id, state)
        }
    }

    //
    // Notification related methods used during pairing
    //
    fun displayPairingNotification() {
        hidePairingNotification()

        notificationId = System.currentTimeMillis().toInt()

        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_DEVICE_ID, deviceId)
            putExtra(MainActivity.PAIR_REQUEST_STATUS, MainActivity.PAIRING_PENDING)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId + 2,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Accept/Reject go to a non-exported receiver (no other app can send
        // them), carry the negotiation they were shown for, and use request
        // codes unique to this notification: with fixed codes, a second
        // device's Accept reused the first one's PendingIntent (extras don't
        // count for PendingIntent identity) and accepted the wrong desktop.
        val timestamp = pairingHandler.negotiationTimestamp
        fun pairingAction(action: String, requestCode: Int) = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, PairingActionReceiver::class.java).apply {
                this.action = action
                putExtra(PairingActionReceiver.EXTRA_DEVICE_ID, deviceId)
                putExtra(PairingActionReceiver.EXTRA_TIMESTAMP, timestamp)
            },
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val acceptedPendingIntent = pairingAction(PairingActionReceiver.ACTION_ACCEPT, notificationId)
        val rejectedPendingIntent = pairingAction(PairingActionReceiver.ACTION_REJECT, notificationId + 1)

        val res = context.resources

        val notificationManager = ContextCompat.getSystemService(context, NotificationManager::class.java)!!

        val noti = NotificationCompat.Builder(context, NotificationHelper.Channels.DEFAULT)
            .setContentTitle(res.getString(R.string.pairing_request_from, name))
            .setContentText(res.getString(R.string.pairing_verification_code, verificationKey))
            .setTicker(res.getString(R.string.pair_requested))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_accept_pairing_24dp, res.getString(R.string.pairing_accept), acceptedPendingIntent)
            .addAction(R.drawable.ic_reject_pairing_24dp, res.getString(R.string.pairing_reject), rejectedPendingIntent)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .build()

        notificationManager.notify(notificationId, noti)
    }

    fun hidePairingNotification() {
        val notificationManager = ContextCompat.getSystemService(context, NotificationManager::class.java)!!
        notificationManager.cancel(notificationId)
    }


    val isReachable: Boolean
        get() = links.isNotEmpty() || hasActiveWebRtcFeatureTransport

    val hasActiveWebRtcFeatureTransport: Boolean
        get() = webRtcTransport?.isReadyForPackets() == true

    val hasBootstrapLink: Boolean
        get() = links.isNotEmpty()

    /** The live peer's path (lan/direct/turn), RTT and outgoing bandwidth, for a
     * connection chip; null with no peer. Answers on libwebrtc's thread. */
    @AnyThread
    fun requestConnectionStats(callback: org.desklink.mobile.webrtc.WebRtcConnectionStatsCallback) {
        val transport = webRtcTransport ?: return callback.onStats(null)
        transport.requestConnectionStats(!hasLanLink && relayLink != null) { stats ->
            if (webRtcTransport === transport) connectionPath = stats?.path
            callback.onStats(stats)
        }
    }

    /**
     * The live peer's ICE path as last measured (the desk's unlock check uses the same rule), or
     * null while unknown. What decides LOCAL or AWAY, not which bootstrap link exists.
     */
    @Volatile
    var connectionPath: org.desklink.mobile.webrtc.WebRtcConnectionPath? = null
        private set

    /** The preferred link is pinned TLS over the LAN (not, e.g., Bluetooth). */
    /** Whether [packetType] is in the feature set shared with this computer (both profiles agree). */
    fun featureActive(packetType: String): Boolean = WebRtcFeatureProfile.peerAccepts(deviceId, packetType)

    val hasLanLink: Boolean
        get() = links.firstOrNull() is org.desklink.mobile.backends.lan.LanLink

    /** The verified relay link, if the device is reachable through the relay. */
    val relayLink: org.desklink.mobile.backends.relay.RelayLink?
        get() = links.firstNotNullOfOrNull { it as? org.desklink.mobile.backends.relay.RelayLink }


    private fun isInteractive(): Boolean =
        context.getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true

    /**
     * The screen went on or off: tell the desk at once (`dozing`) on every live path, as a
     * report (`reply: true`) so nothing answers it. Best effort.
     */
    fun sendDozeState() {
        if (!isPaired) return
        val interactive = isInteractive()
        webRtcTransport?.takeIf { it.isReadyForPackets() }?.sendPacket(
            heartbeatPacket(reply = true, interactive = interactive),
            object : TransportCallback {
                override fun onSuccess() = Unit
                override fun onFailure(error: TransportError) = Unit
            },
        )
        if (hasBootstrapLink) {
            sendBootstrapPacket(
                heartbeatPacket(reply = true, interactive = interactive),
                object : SendPacketStatusCallback() {
                    override fun onSuccess() = Unit
                    override fun onFailure(e: Throwable) = Unit
                },
            )
        }
    }

    fun setSessionTransport(transport: SessionTransport?) {
        sessionTransport = transport
    }

    fun setWebRtcTransport(transport: WebRtcTransport?) {
        setWebRtcTransport(transport, reloadPlugins = true)
    }

    /**
     * Feature-ready: every loaded plugin sends its snapshot. Queued behind the plugin reload that
     * [setWebRtcTransport] starts, so a plugin loaded by it is included.
     */
    fun onFeatureReady() {
        lifecycle.enqueue("feature-ready snapshots") {
            // B1: the doze state first; the desk keeps its own until the phone says otherwise.
            runCatching { sendDozeState() }
            // Delta resync: say what this phone already holds of the desk's state, then give the desk
            // a moment to say the same, so only what differs is sent. A desk that does not take
            // part in it (not in its handover) is not waited for.
            sync.beginLink()
            if (WebRtcFeatureProfile.peerAccepts(deviceId, DeskLinkProtocol.PACKET_TYPE_SYNC_HAVE)) {
                runCatching { sendPacket(sync.havePacket(peerInstanceId)) }
                if (WebRtcFeatureProfile.peerSends(deviceId, DeskLinkProtocol.PACKET_TYPE_SYNC_HAVE)) sync.awaitHave()
            }
            loadedPlugins.values.forEach { plugin ->
                runCatching { plugin.onFeatureReady() }
                    .onFailure { Log.w("DeskLink/Device", "${plugin.pluginKey} failed its feature-ready snapshot", it) }
            }
            // A phone-owned feature that is off or has no permission has no plugin to speak for
            // it: the empty form goes out here, so the desk never keeps a stale copy (S2).
            emptyStatesFor(this).forEach { runCatching { sendPacket(it) } }
        }
    }

    fun setWebRtcTransport(transport: WebRtcTransport?, reloadPlugins: Boolean) {
        if (webRtcTransport === transport) return
        webRtcTransport = transport
        connectionPath = null
        if (transport?.isReadyForPackets() == true) requestConnectionStats { }
        if (transport?.isReadyForPackets() == true) flushPendingFeaturePackets()
        // Plugin availability is calculated from reachability. Re-evaluate it
        // when WebRTC becomes ready or is closed so a transient loss of the
        // bootstrap socket cannot disable a live paired feature session.
        if (reloadPlugins) {
            lifecycle.enqueue("plugin reload after WebRTC transport change") {
                reloadPluginsFromSettings()
            }
        }
    }

    /**
     * Sends, in order, what was queued while WebRTC was down. Anything older
     * than [PENDING_FEATURE_MAX_AGE_MS] fails instead: "Lock" or "Ring" must
     * not fire long after the user gave up on it. Mirrors the desktop queue.
     */
    //
    // Sends synchronously on the calling thread, never through the outbound queue:
    // a full 256-entry queue would overflow that 64-slot channel. Queued
    // packets never carry a payload, so nothing here blocks on file I/O.
    fun flushPendingFeaturePackets() = synchronized(sendOrderLock) {
        // Loop: a producer may queue more while a batch is being sent.
        while (true) {
            val batch = pendingFeaturePackets.drain()
            if (batch.isEmpty()) break
            val now = android.os.SystemClock.elapsedRealtime()
            val (stale, fresh) = batch.partition { now - it.queuedAt > PENDING_FEATURE_MAX_AGE_MS }
            failExpired(stale)
            fresh.forEach { queued -> sendPacketBlocking(queued.packet, queued.callback) }
            if (webRtcTransport?.isReadyForPackets() != true) break
        }
    }

    private val sendOrderLock = Any()

    private val expiryHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Queued packets the link did not come back for in time fail, even while it stays down. */
    private val expireTask = Runnable { expireStalePending() }

    private fun expireStalePending() {
        val now = android.os.SystemClock.elapsedRealtime()
        failExpired(synchronized(sendOrderLock) { pendingFeaturePackets.removeIf { now - it.queuedAt > PENDING_FEATURE_MAX_AGE_MS } })
    }

    /**
     * Fails each expired packet's callback, and tells the person once when any of them was
     * something they asked for (a lock, a ring, a switch…): it was never sent.
     */
    private fun failExpired(expired: List<QueuedFeature>) {
        if (expired.isEmpty()) return
        expired.forEach { it.callback.onFailure(QueuedPacketExpiredException(it.packet.type)) }
        val actions = expiredUserActions(expired.map { it.callback })
        if (actions > 0) {
            val message = context.resources.getQuantityString(R.plurals.queued_actions_expired, actions, actions, name)
            expiryHandler.post { android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    private fun sendOnTransport(
        transport: WebRtcTransport,
        packet: NetworkPacket,
        type: String,
        callback: SendPacketStatusCallback,
    ): Boolean = try {
        transport.sendPacket(
            packet,
            object : TransportCallback {
                override fun onSuccess() = callback.onSuccess()

                override fun onFailure(error: TransportError) = callback.onFailure(error)

                override fun onProgress(percent: Int) {
                    callback.onPayloadProgressChanged(percent)
                }
            },
        )
        countSent(deviceId, type, true)
        true
    } catch (e: Exception) {
        callback.onFailure(e)
        countSent(deviceId, type, false)
        false
    }

    /** Clears every WebRTC callback and queued bootstrap packet on unpair. */
    fun clearWebRtcBindingsForUnpair() {
        webRtcTransport = null
        pendingFeaturePackets.drain().forEach { queued ->
            queued.callback.onFailure(IllegalStateException("Device was unpaired"))
        }
        webRtcRecoveryPending = false
        payloadPacketHandler = null
        remoteInputPacketPreparer = null
        val pending = synchronized(controlPacketLock) {
            controlPacketHandler = null
            pendingControlPackets.drain()
        }
        pending.forEach { it.payload?.close() }
    }

    /** Every rung of the recovery ladder failed: shown as "unavailable" until a reset event or feature-ready. */
    @Volatile var webRtcUnavailable: Boolean = false
        private set

    fun setWebRtcUnavailable(unavailable: Boolean) {
        webRtcUnavailable = unavailable
    }

    fun setWebRtcRecoveryPending(pending: Boolean) {
        if (pending && !webRtcRecoveryPending) {
            webRtcRecoveryPendingSince = android.os.SystemClock.elapsedRealtime()
        }
        webRtcRecoveryPending = pending
    }

    fun setWebRtcPayloadHandler(handler: PayloadPacketHandler?) {
        payloadPacketHandler = handler
    }

    fun setControlPacketHandler(handler: ControlPacketHandler?) {
        val pending = synchronized(controlPacketLock) {
            controlPacketHandler = handler
            if (handler == null) emptyList() else pendingControlPackets.drain()
        }
        if (handler != null) {
            pending.forEach { packet -> deliverControlPacket(handler, packet) }
        }
    }

    fun setWebRtcRemoteInputPreparer(preparer: RemoteInputPacketPreparer?) {
        remoteInputPacketPreparer = preparer
    }

    /**
     * Whether a link may speak for this device.
     *
     * The LAN transport proves this during its TLS handshake, by installing the
     * stored pairing certificate as the only trust anchor. No other transport
     * does: [org.desklink.mobile.backends.bluetooth.BluetoothLink] takes the
     * peer's certificate straight out of the identity packet it was handed.
     *
     * [dispatchPacket] has no link parameter, so once a link is attached
     * nothing downstream can tell a pinned socket from an unpinned one — an
     * unauthenticated peer reached `desklink.pair`, and could unpair this
     * device and destroy its stored certificate with a single packet. The check
     * therefore belongs here, where every present and future transport passes.
     */
    private fun linkSpeaksForThisDevice(link: BaseLink): Boolean {
        if (!isPaired) {
            // No certificate to pin yet, but it must at least claim this id:
            // the desktop checks the CN too, and without it a link could
            // present anyone's certificate under a broadcast device id.
            return runCatching {
                SslHelper.getCommonNameFromCertificate(
                    link.deviceInfo.certificate as java.security.cert.X509Certificate,
                ) == deviceId
            }.getOrDefault(false)
        }
        if (!TrustedDevices.isCertificateStored(context, deviceId)) {
            // Paired with no stored certificate is the desync SslHelper guards
            // against. Refuse rather than fall back to trusting the peer.
            return false
        }
        return runCatching {
            TrustedDevices.getDeviceCertificate(context, deviceId) == link.deviceInfo.certificate
        }.getOrDefault(false)
    }

    fun addLink(link: BaseLink) {
        if (!linkSpeaksForThisDevice(link)) {
            Log.e(
                "DeskLink/Device",
                "Rejected a ${link.name} link for $deviceId: its certificate does not match the pairing record",
            )
            runCatching { link.disconnect() }
                .onFailure { error ->
                    Log.w("DeskLink/Device", "Could not close a rejected ${link.name} link", error)
                }
            return
        }

        // A pairing negotiation belongs to the certificate it was started on;
        // the user compares a key derived from it. A link with a different
        // certificate abandons it (the desktop does the same, CVE-2020-26164).
        if (!isPaired && runCatching { link.deviceInfo.certificate != certificate }.getOrDefault(true)) {
            pairingHandler.abandonNegotiation()
        }

        // A provider owns one current link for a device. Drop a stale link
        // from the same provider before adding the replacement; otherwise a
        // later pairing request can iterate an already-closed socket first
        // and report the device as unreachable even though the new socket is
        // healthy.
        links.filter { it.linkProvider === link.linkProvider && it !== link }
            .forEach { staleLink ->
                staleLink.removePacketReceiver(this)
                links.remove(staleLink)
                runCatching { staleLink.disconnect() }
                    .onFailure { error ->
                        Log.w("DeskLink/Device", "Could not close stale ${staleLink.name} link", error)
                    }
            }

        synchronized(outbound) {
            if (sendCoroutine == null) {
                sendCoroutine = CoroutineScope(Dispatchers.IO).launch {
                    while (true) {
                        val (np, callback) = runInterruptible { outbound.take() } ?: break
                        // A file blocks until the peer has it, however long that takes: it runs on
                        // its own threads so everything queued behind it keeps flowing.
                        if (np.payload != null) sendFileAsync(np, callback) else sendPacketBlocking(np, callback)
                    }
                }
            }
        }

        // FilesHelper.LogOpenFileCount();
        links.add(link)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // sorting CopyOnWriteArrayList is not supported in SDK < 26 (throws UnsupportedOperationException)
            val copy = links.toMutableList()
            copy.sortWith { o1, o2 ->
                o2.linkProvider.priority compareTo o1.linkProvider.priority
            }
            links.clear()
            links.addAll(copy)
        } else {
            links.sortWith { o1, o2 ->
                o2.linkProvider.priority compareTo o1.linkProvider.priority
            }
        }

        link.addPacketReceiver(this)

        val hasChanges = updateDeviceInfo(link.deviceInfo)

        if (hasChanges || links.size == 1) {
            reloadPluginsFromSettings()
        }
    }

    @WorkerThread
    fun removeLink(link: BaseLink) {
        // FilesHelper.LogOpenFileCount();

        link.removePacketReceiver(this)
        links.remove(link)
        Log.i(
            "DeskLink/Device",
            "removeLink: ${link.linkProvider.name} -> $name active links: ${links.size}"
        )
        if (links.isEmpty() && !hasActiveWebRtcFeatureTransport) {
            reloadPluginsFromSettings()
            synchronized(outbound) {
                sendCoroutine?.cancel(CancellationException("Device disconnected"))
                sendCoroutine = null
            }
        } else if (links.isEmpty()) {
            Log.i(
                "DeskLink/Device",
                "LAN bootstrap link closed while authenticated WebRTC remains active for $name",
            )
        }
    }

    fun updateDeviceInfo(newDeviceInfo: DeviceInfo): Boolean {
        var hasChanges = false
        if (deviceInfo.name != newDeviceInfo.name || deviceInfo.type != newDeviceInfo.type || deviceInfo.protocolVersion != newDeviceInfo.protocolVersion) {
            hasChanges = true
            deviceInfo.name = newDeviceInfo.name
            deviceInfo.type = newDeviceInfo.type
            deviceInfo.protocolVersion = newDeviceInfo.protocolVersion
            if (isPaired) {
                deviceInfo.saveInSettings(context)
            }
        }

        val oldIncomingCapabilities = deviceInfo.incomingCapabilities
        val oldOutgoingCapabilities = deviceInfo.outgoingCapabilities
        val newIncomingCapabilities = newDeviceInfo.incomingCapabilities
        val newOutgoingCapabilities = newDeviceInfo.outgoingCapabilities
        if (
            !newIncomingCapabilities.isNullOrEmpty() &&
            !newOutgoingCapabilities.isNullOrEmpty() &&
            (
                oldIncomingCapabilities != newIncomingCapabilities ||
                oldOutgoingCapabilities != newOutgoingCapabilities
            )
        ) {
            hasChanges = true
            Log.i("updateDeviceInfo", "Updating supported plugins according to new capabilities")
            deviceInfo.outgoingCapabilities = newOutgoingCapabilities
            deviceInfo.incomingCapabilities = newIncomingCapabilities
            supportedPlugins = Vector(
                PluginFactory.pluginsForCapabilities(
                    newIncomingCapabilities,
                    newOutgoingCapabilities
                )
            )
        }

        return hasChanges
    }

    override fun onPacketReceived(np: NetworkPacket) {
        dispatchPacket(np, fromWebRtc = false)
    }

    fun onWebRtcPacketReceived(np: NetworkPacket) {
        dispatchPacket(np, fromWebRtc = true)
    }

    private fun dispatchPacket(np: NetworkPacket, fromWebRtc: Boolean) {
        countReceived(deviceId, np.type)
        Log.d(
            "DeskLink/Inbound",
            "${np.type} from $deviceId (webrtc=$fromWebRtc, links=${links.size})",
        )

        val bootstrapPacket = isBootstrapPacketType(np.type)
        // The heartbeat is the one bootstrap type that is also legal on WebRTC:
        // it is the data-channel liveness probe, answered just below.
        if (fromWebRtc && bootstrapPacket && np.type != DeskLinkProtocol.PACKET_TYPE_HEARTBEAT) {
            Log.w("DeskLink/WebRTC", "Rejected bootstrap packet on the WebRTC feature path")
            np.payload?.close()
            return
        }
        // A heartbeat is liveness and nothing else. Answered here and returned,
        // *before* any of the filtering below, because falling through would
        // reach notifyPeerNotPaired and tell a perfectly healthy desktop it was
        // unpaired once every ten seconds -- the mechanism that keeps links
        // alive turned into one that tears pairings down.
        //
        // The reply is what makes the desktop's own silence budget survive an
        // idle link: it probes when it has heard nothing, and this is what it
        // hears.
        if (np.type == DeskLinkProtocol.PACKET_TYPE_HEARTBEAT) {
            if (fromWebRtc) {
                // Data-channel liveness probe from the desktop: answer it on the
                // same transport. A reply is never answered, or the two sides
                // would ping-pong forever. Arriving at all already refreshed the
                // coordinator's own silence clock.
                if (!np.getBoolean("reply", false)) {
                    val reply = heartbeatPacket(reply = true, interactive = isInteractive())
                    webRtcTransport?.sendPacket(reply, object : TransportCallback {
                        override fun onSuccess() = Unit
                        override fun onFailure(error: TransportError) {
                            Log.d("DeskLink/Heartbeat", "Could not answer a WebRTC heartbeat: ${error.message}")
                        }
                    })
                }
            } else if (!np.getBoolean("reply", false)) {
                // B1: also on the data channel, so the desk's data-channel clock hears the phone
                // even while it is dozing and frozen between LAN heartbeats.
                webRtcTransport?.takeIf { it.isReadyForPackets() }?.sendPacket(
                    heartbeatPacket(reply = true, interactive = isInteractive()),
                    object : TransportCallback {
                        override fun onSuccess() = Unit
                        override fun onFailure(error: TransportError) = Unit
                    },
                )
                sendBootstrapPacket(
                    heartbeatPacket(reply = true, interactive = isInteractive()),
                    // Best effort. A failure here means the link is already
                    // gone, which the desktop's own silence budget will reach
                    // the same conclusion about a few seconds later.
                    object : SendPacketStatusCallback() {
                        override fun onSuccess() = Unit
                        override fun onFailure(e: Throwable) {
                            Log.d("DeskLink/Heartbeat", "Could not answer a heartbeat", e)
                        }
                    },
                )
                // A heartbeat proves the bootstrap link works *right now*, which
                // makes it the right moment to check that WebRTC exists at all.
                // Idempotent when a transport is already live.
                runCatching { DeskLinkApplication.getInstance().onBootstrapHealthy(deviceId) }
                    .onFailure { Log.d("DeskLink/Heartbeat", "No coordinator to nudge", it) }
            }
            return
        }
        // A feature packet from a peer this side no longer trusts means the peer
        // missed our unpair — tell it, before any other filtering drops the
        // packet, so a peer that only ever sends non-profile types is still
        // corrected.
        //
        // Restricted to `NotPaired` on purpose. `Requested` and
        // `RequestedByPeer` are an in-flight negotiation, not a stale peer:
        // acting on them would cancel a pairing prompt the user is looking at
        // and report a failure they did not cause.
        if (!bootstrapPacket && pairStatus == PairingHandler.PairState.NotPaired) {
            pairingHandler.notifyPeerNotPaired()
        }
        // The feature profile applies to every transport, not only WebRTC.
        // Gating it on `fromWebRtc` meant that once LAN began carrying features
        // before handover, it accepted packet types the profile excludes —
        // `desklink.sms.request`, `desklink.telephony.request`,
        // `desklink.screen.request` and the rest — and handed them straight to
        // their plugins.
        // Within the profile and, once the handover is done, within what the peer said it sends.
        if (!bootstrapPacket && !WebRtcFeatureProfile.peerSends(deviceId, np.type)) {
            Log.w("DeskLink/Device", "Rejected feature outside the active profile: ${np.type}")
            np.payload?.close()
            return
        }
        // Paired feature traffic is WebRTC-only; see shouldRejectPairedLanFeaturePacket.
        if (shouldRejectPairedLanFeaturePacket(
                fromWebRtc = fromWebRtc,
                paired = isPaired,
                bootstrapPacket = bootstrapPacket,
            )
        ) {
            Log.w("DeskLink/Device", "Rejected paired feature packet on the LAN bootstrap path: ${np.type}")
            np.payload?.close()
            return
        }

        if (NetworkPacket.PACKET_TYPE_PAIR == np.type) {
            Log.i("DeskLink/Device", "Pair packet")
            pairingHandler.packetReceived(np)
            return
        }

        if (np.type == DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1) {
            if (!isPaired) {
                Log.w("DeskLink/WebRTC", "Rejected signaling from an unpaired device")
                // Signaling is all a peer that missed our unpair still sends now
                // that features never use LAN, so this is where it must be told
                // (damped, and only in NotPaired). Without it the desktop showed
                // "Connecting" forever.
                pairingHandler.notifyPeerNotPaired()
                np.payload?.close()
                return
            }
            Log.i("DeskLink/WebRTC", "Received signed bootstrap signaling from $deviceId")
            val (handler, evicted) = synchronized(controlPacketLock) {
                val current = controlPacketHandler
                if (current == null) {
                    null to pendingControlPackets.offer(np)
                } else {
                    current to null
                }
            }
            evicted?.payload?.close()
            if (handler == null) {
                Log.i("DeskLink/WebRTC", "Queued signed signaling until the coordinator is attached")
                return
            }
            deliverControlPacket(handler, np)
            return
        }

        if (np.type == DeskLinkProtocol.PACKET_TYPE_SYNC_HAVE) {
            sync.onHave(np)
            return
        }

        // pluginsByIncomingInterface may not be built yet
        if (pluginsByIncomingInterface.isEmpty) {
            reloadPluginsFromSettings()
        }

        // The unpair self-heal for a peer that missed our unpair — one device
        // unpairing while the other is off Wi-Fi — now runs earlier, before the
        // feature-profile filter, so a peer sending only non-profile types is
        // still told. `unpair()` is idempotent, so nothing is lost by it having
        // already run for this packet.

        // The following code when `isPaired == false` is NOT USED.
        // It adds support for receiving packets from not trusted devices,
        // but as of March 2023 no plugin implements "onUnpairedDevicePacketReceived".
        notifyPluginPacketReceived(np)
        // Applied: remember the snapshot's rev so the next link can tell the peer it need not resend it.
        sync.record(peerInstanceId, np)
    }

    /** Per type, under every plugin: a flood is dropped (counted, one log line a window), never dispatched. */
    private val inboundLimits = org.desklink.mobile.helpers.InboundLimits.ForDesk()

    private fun notifyPluginPacketReceived(np: NetworkPacket) {
        if (!inboundLimits.allow(np.type)) {
            np.payload?.close()
            return
        }
        // Type only, never the body. tools/device_e2e.sh asserts on this line.
        Log.i("DeskLink/Feature", "Received ${np.type} from $deviceId")
        val targetPlugins = pluginsByIncomingInterface[np.type] // Returns an empty collection if the key doesn't exist
        if (targetPlugins.isEmpty()) {
            Log.w("Device", "Ignoring packet with type ${np.type} because no plugin can handle it")

            // If there is a payload close it to not leak sockets
            np.payload?.close()
            return
        }
        targetPlugins
            .asSequence()
            .mapNotNull { loadedPlugins[it] }
            .forEach { plugin ->
                runCatching {
                    if (isPaired) {
                        plugin.onPacketReceived(np)
                    } else {
                        plugin.onUnpairedDevicePacketReceived(np)
                    }
                }.onFailure { e ->
                    Log.e("Device", "Exception in ${plugin.pluginKey}'s onPacketReceived()", e)
                }
            }
    }

    private fun deliverControlPacket(handler: ControlPacketHandler, packet: NetworkPacket) {
        if (!runCatching { handler.onControlPacket(packet) }.getOrDefault(false)) {
            Log.w("DeskLink/WebRTC", "Rejected WebRTC signaling packet")
            packet.payload?.close()
        }
    }

    abstract class SendPacketStatusCallback {
        abstract fun onSuccess()

        abstract fun onFailure(e: Throwable)

        open fun onPayloadProgressChanged(percent: Int) {}
    }

    private val defaultCallback: SendPacketStatusCallback = object : SendPacketStatusCallback() {
        override fun onSuccess() {
        }

        // Rejected input (an expired lease, a stopped session) fails once per
        // gesture event, at pointer rate. A stack trace for each buried the log
        // and hid everything else, so this reports at most every few seconds.
        private var lastFailureLoggedNanos = 0L

        override fun onFailure(e: Throwable) {
            val now = System.nanoTime()
            if (now - lastFailureLoggedNanos >= SEND_FAILURE_LOG_INTERVAL_NANOS) {
                lastFailureLoggedNanos = now
                Log.w("Device", "Send packet failed: ${e.message}", e)
            }
        }
    }

    /**
     * Send a packet to the device asynchronously
     * @param np The packet
     * @param callback A callback for success/failure
     */
    @AnyThread
    fun sendPacket(np: NetworkPacket, callback: SendPacketStatusCallback) = enqueue(np, callback, bulk = false)

    /**
     * A long batch (contacts, file share requests, manifest parts): served after everything
     * interactive, in its own lane, so it can never delay a tap or a state change.
     */
    @AnyThread
    fun sendBulk(np: NetworkPacket, callback: SendPacketStatusCallback = defaultCallback) = enqueue(np, callback, bulk = true)

    private fun enqueue(np: NetworkPacket, callback: SendPacketStatusCallback, bulk: Boolean) {
        if (!outbound.offer(NetworkPacketWithCallback(np, callback), bulk)) reportNotSent(callback, "outbound queue is full")
    }

    private var lastNotSentToastAt = 0L

    private fun sendFileAsync(np: NetworkPacket, callback: SendPacketStatusCallback) {
        try {
            fileSendPool.execute { sendPacketBlocking(np, callback) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            np.payload?.close()
            reportNotSent(callback, "too many files are waiting to send")
        }
    }

    /**
     * A packet was refused or dropped for lack of room: the sender's callback always hears of it, and
     * the person is told (at most once every few seconds) when it was something they asked for.
     */
    private fun reportNotSent(callback: SendPacketStatusCallback, why: String) {
        callback.onFailure(IllegalStateException("DeskLink did not send a packet: $why"))
        if (callback !is UserActionCallback) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNotSentToastAt < NOT_SENT_TOAST_INTERVAL_MS) return
        lastNotSentToastAt = now
        val message = context.getString(R.string.outbound_not_sent, name)
        expiryHandler.post { android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show() }
    }

    @AnyThread
    fun sendPacket(np: NetworkPacket) = sendPacket(np, defaultCallback)

    /** Something the person asked for: said out loud if it expires unsent while offline. */
    fun sendUserAction(np: NetworkPacket) = sendPacket(np, UserActionCallback())

    /**
     * Sends only discovery/pairing/signaling packets on the bootstrap LAN
     * link. Paired feature packets must never use this path.
     */
    @AnyThread
    fun sendBootstrapPacket(np: NetworkPacket, callback: SendPacketStatusCallback): Boolean {
        val bootstrapPacket = isBootstrapPacketType(np.type)
        if (!bootstrapPacket) {
            callback.onFailure(IllegalArgumentException("Only DeskLink bootstrap packets may use LAN"))
            return false
        }
        return sendPacketBlocking(np, callback, false)
    }

    @WorkerThread
    fun sendPacketBlocking(np: NetworkPacket, callback: SendPacketStatusCallback): Boolean =
        sendPacketBlocking(np, callback, false)

    @WorkerThread
    fun sendPacketBlocking(np: NetworkPacket): Boolean = sendPacketBlocking(np, defaultCallback, false)


    @WorkerThread
    fun sendPacketBlocking(
        np: NetworkPacket,
        callback: SendPacketStatusCallback,
        sendPayloadFromSameThread: Boolean
    ): Boolean {
        // Bootstrap packets are deliberately independent of the feature
        // capability list.  In particular, the signed WebRTC SDP/ICE packet
        // is exchanged before the WebRTC feature profile is confirmed, so it
        // must not be rejected merely because the peer does not advertise it
        // as an ordinary plugin capability.
        val bootstrapPacket = isBootstrapPacketType(np.type)
        if (!bootstrapPacket && !supportsPacketType(np.type)) {
            Log.e("DeskLink/sendPacket", "Tried to send an unsupported packet type ${np.type} to: ${deviceInfo.name}")
            callback.onFailure(IllegalArgumentException("Unsupported packet type ${np.type}"))
            return false
        }

        val transport = webRtcTransport
        val readyTransport = transport?.takeIf { it.isReadyForPackets() }
        val filePayload = np.type == DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST &&
            np.getString("filename").isNotEmpty() && np.payload != null
        // The intersection: in this profile, and in what the peer said it receives.
        if (isPaired && !bootstrapPacket && !WebRtcFeatureProfile.peerAccepts(deviceId, np.type)) {
            val error = IllegalStateException(
                "${np.type} is not enabled with ${deviceInfo.name} (outside the shared feature profile)",
            )
            callback.onFailure(error)
            np.payload?.close()
            countSent(deviceId, np.type, false)
            return false
        }
        if (isPaired && !bootstrapPacket && filePayload) {
            val handler = payloadPacketHandler
            if (readyTransport != null && handler != null) {
                return runCatching { handler.sendPayload(np, callback) }
                    .onFailure(callback::onFailure)
                    .getOrDefault(false)
                    .also { countSent(deviceId, np.type, it) }
            }
            val error = IllegalStateException(
                "DeskLink WebRTC file transport is not ready",
            )
            callback.onFailure(error)
            np.payload?.close()
            countSent(deviceId, np.type, false)
            return false
        }
        if (
            isPaired &&
            np.type == DeskLinkProtocol.PACKET_TYPE_NOTIFICATION &&
            np.payload != null
        ) {
            // Notification artwork is optional. The notification itself must
            // still synchronize when its icon does not fit the event channel.
            np.payload?.close()
            np.payload = null
            np.payloadTransferInfo = org.json.JSONObject()
        }
        if (isPaired && !bootstrapPacket && np.payload != null) {
            val error = IllegalStateException(
                "Payload-bearing ${np.type} has no WebRTC media/attachment transport",
            )
            callback.onFailure(error)
            np.payload?.close()
            countSent(deviceId, np.type, false)
            return false
        }
        if (isPaired && !bootstrapPacket) {
            val activeTransport = readyTransport
            if (activeTransport == null) {
                // WebRTC is the only feature transport. The LAN socket used to
                // carry features until the handover finished, but each side
                // judged "finished" by its own state, so after a restart the
                // phone sent over LAN while the desktop — still holding the old
                // peer — rejected everything arriving there. Notifications and
                // battery were lost in silence. Queue instead; the queue is
                // flushed in order the moment the transport is ready.
                //
                // Remote input is never queued: a stale tap replayed seconds
                // later is worse than a dropped one, and its lease can only be
                // minted by the WebRTC coordinator anyway.
                if (np.type == DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST) {
                    callback.onFailure(
                        IllegalStateException(
                            "DeskLink remote control requires the authenticated WebRTC transport",
                        ),
                    )
                    countSent(deviceId, np.type, false)
                    return false
                }
                // One check, pushed back by each new packet: at most one toast per outage.
                expiryHandler.removeCallbacks(expireTask)
                expiryHandler.postDelayed(expireTask, PENDING_FEATURE_MAX_AGE_MS + 1_000)
                pendingFeaturePackets.offerPreferring(QueuedFeature(np, callback)) { it.callback !is UserActionCallback }?.let { dropped ->
                    reportNotSent(dropped.callback, "${dropped.packet.type}: the offline queue is full")
                }
                // Close the race with a transport that became ready between the
                // check above and the offer.
                if (webRtcTransport?.isReadyForPackets() == true) flushPendingFeaturePackets()
                return true
            }
            if (np.payload != null) {
                val error = IllegalStateException(
                    "Payload-bearing ${np.type} has no WebRTC attachment transport",
                )
                callback.onFailure(error)
                np.payload?.close()
                countSent(deviceId, np.type, false)
                return false
            }
            val remoteInput = np.type == DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST
            if (!remoteInput) {
                // Ordered against the flush: older queued packets go first, and
                // no direct send may overtake a batch being flushed. Remote
                // input stays outside this lock — it is never queued, its
                // channel is unordered anyway, and its lease preparer takes the
                // coordinator lock, which a flush may already hold (deadlock).
                synchronized(sendOrderLock) {
                    if (!pendingFeaturePackets.isEmpty()) {
                        pendingFeaturePackets.offerPreferring(QueuedFeature(np, callback)) { it.callback !is UserActionCallback }?.let { dropped ->
                            reportNotSent(dropped.callback, "${dropped.packet.type}: the offline queue is full")
                        }
                        flushPendingFeaturePackets()
                        return true
                    }
                    return sendOnTransport(activeTransport, np, np.type, callback)
                }
            }
            val packetForTransport = try {
                requireNotNull(remoteInputPacketPreparer) {
                    "DeskLink remote-control lease is not active"
                }.prepare(np)
            } catch (error: Throwable) {
                callback.onFailure(error)
                countSent(deviceId, np.type, false)
                return false
            }
            return sendOnTransport(activeTransport, packetForTransport, np.type, callback)
        }

        val success = links.any { link ->
            try {
                link.sendPacket(np, callback, sendPayloadFromSameThread)
            } catch (e: IOException) {
                Log.w("DeskLink/sendPacket", "Failed to send packet", e)
                false
            }.also { sent ->
                countSent(deviceId, np.type, sent)
            }
        }

        if (!success) {
            Log.e(
                "DeskLink/sendPacket",
                "No device link (of ${links.size} available) could send the packet. Packet ${np.type} to ${deviceInfo.name} lost!"
            )
        }

        return success
    }

    //
    // Plugin-related functions
    //
    fun <T : Plugin> getPlugin(pluginClass: Class<T>): T? {
        val plugin = getPlugin(getPluginKey(pluginClass))
        return plugin?.let(pluginClass::cast)
    }

    fun getPlugin(pluginKey: String): Plugin? = loadedPlugins[pluginKey]

    fun getPluginIncludingWithoutPermissions(pluginKey: String): Plugin? {
        return loadedPlugins[pluginKey] ?: pluginsWithoutPermissions[pluginKey]
    }

    // Helper function for reloadPluginsFromSettings(), do not call from elsewhere
    private fun addPlugin(pluginKey: String): Boolean {
        val isNewPlugin = !loadedPlugins.containsKey(pluginKey)

        val plugin = loadedPlugins[pluginKey]
            ?: PluginFactory.instantiatePluginForDevice(context, pluginKey, this)
                ?: return false

        if (!plugin.isCompatible) {
            Log.d("DeskLink/addPlugin", "Minimum requirements (e.g. API level) not fulfilled $pluginKey")
            return false
        }

        if (!plugin.checkRequiredPermissions()) {
            Log.d("DeskLink/addPlugin", "No permission $pluginKey")
            pluginsWithoutPermissions[pluginKey] = plugin
            if (plugin.loadPluginWhenRequiredPermissionsMissing()) {
                loadedPlugins[pluginKey] = plugin
            } else {
                loadedPlugins.remove(pluginKey)
                return false
            }
        } else {
            Log.d("DeskLink/addPlugin", "Permissions OK $pluginKey")
            loadedPlugins[pluginKey] = plugin
            pluginsWithoutPermissions.remove(pluginKey)
            if (plugin.checkOptionalPermissions()) {
                Log.d("DeskLink/addPlugin", "Optional Permissions OK $pluginKey")
                pluginsWithoutOptionalPermissions.remove(pluginKey)
            } else {
                Log.d("DeskLink/addPlugin", "No optional permission $pluginKey")
                pluginsWithoutOptionalPermissions[pluginKey] = plugin
            }
        }

        if (!isNewPlugin) {
            return true
        }

        return runCatching {
            plugin.onCreate()
        }.onFailure {
            Log.e("DeskLink/addPlugin", "plugin failed to load $pluginKey", it)
        }.getOrDefault(false)
    }

    // Helper function for reloadPluginsFromSettings(), do not call from elsewhere
    private fun removePlugin(pluginKey: String): Boolean {
        val plugin = loadedPlugins.remove(pluginKey) ?: return false

        try {
            plugin.onDestroy()
            // Log.e("removePlugin","removed " + pluginKey);
        } catch (e: Exception) {
            Log.e("DeskLink/removePlugin", "Exception calling onDestroy for plugin $pluginKey", e)
        }

        return true
    }

    fun setPluginEnabled(pluginKey: String, value: Boolean) {
        if (!value) runCatching { loadedPlugins[pluginKey]?.onTurnedOff() }.onFailure { Log.w("DeskLink/Device", "$pluginKey could not send its empty state", it) }
        TrustedDevices.getDeviceSettings(context, deviceId).edit { putBoolean(pluginKey, value) }
        reloadPluginsFromSettings()
    }

    fun isPluginEnabled(pluginKey: String): Boolean {
        val enabledByDefault = PluginFactory.getPluginInfo(pluginKey).isEnabledByDefault
        return TrustedDevices.getDeviceSettings(context, deviceId).getBoolean(pluginKey, enabledByDefault)
    }

    fun notifyPluginsOfDeviceUnpaired(context: Context, deviceId: String) {
        for (pluginKey in supportedPlugins) {
            // This is a hacky way to temporarily create plugins just so that they can be notified of the
            // device being unpaired. This else part will only come into picture when 1) the user tries to
            // unpair a device while that device is not reachable or 2) the plugin was never initialized
            // for this device, e.g., the plugins that need additional permissions from the user, and those
            // permissions were never granted.
            runCatching {
                val plugin = getPlugin(pluginKey)
                    ?: PluginFactory.instantiatePluginForDevice(context, pluginKey, this)
                plugin?.onDeviceUnpaired(context, deviceId)
            }.onFailure { error ->
                Log.e("DeskLink/DeviceLifecycle", "Plugin $pluginKey failed during unpair", error)
            }
        }
    }

    fun launchBackgroundReloadPluginsFromSettings() {
        CoroutineScope(Dispatchers.IO).launch {
            reloadPluginsFromSettings()
        }
    }

    @Synchronized
    @WorkerThread
    fun reloadPluginsFromSettings() {
        Log.i("Device", "${deviceInfo.name}: reloading plugins")
        val newPluginsByIncomingInterface: MultiValuedMap<String, String> = ArrayListValuedHashMap()

        supportedPlugins.forEach { pluginKey ->
            val pluginInfo = PluginFactory.getPluginInfo(pluginKey)
            val listenToUnpaired = pluginInfo.listenToUnpaired

            val pluginEnabled = (isPaired || listenToUnpaired) && this.isReachable && isPluginEnabled(pluginKey)

            if (pluginEnabled && addPlugin(pluginKey)) {
                pluginInfo.supportedPacketTypes.forEach { packetType ->
                    newPluginsByIncomingInterface.put(packetType, pluginKey)
                }
            } else {
                removePlugin(pluginKey)
            }
        }

        pluginsByIncomingInterface = newPluginsByIncomingInterface

        onPluginsChanged()
    }

    fun onPluginsChanged() {
        pluginsChangedListeners.forEach { it.onPluginsChanged(this) }
        org.desklink.mobile.ui.surfaces.DeskSurfaces.refresh(context)
    }

    fun addPluginsChangedListener(listener: PluginsChangedListener) = pluginsChangedListeners.add(listener)

    fun removePluginsChangedListener(listener: PluginsChangedListener) = pluginsChangedListeners.remove(listener)

    fun disconnect() {
        links.forEach(BaseLink::disconnect)
    }

    @VisibleForTesting
    internal fun closeLifecycle() {
        lifecycle.close()
    }

    override fun toString(): String {
        return "Device(name=$name, id=$deviceId)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Device) return false
        // There should never be two instances of Device if they have the same ID
        return deviceId == other.deviceId
    }

    override fun hashCode(): Int {
        return deviceId.hashCode()
    }
}

/**
 * LAN is allowed for paired feature packets only during the authenticated
 * transition to WebRTC.  It becomes bootstrap-only after WebRTC handover.
 *
 * The `webRtcFeatureTransportReady` argument used to be accepted and then
 * ignored, so LAN was rejected unconditionally and the comment above described
 * behaviour the code never had. Since the LAN link is mutually authenticated
 * with pinned certificates, refusing it before handover left the product with
 * no working transport at all whenever ICE could not complete.
 */
/**
 * LAN carries only identity, pairing, signaling and heartbeats. Paired features
 * travel exclusively over the authenticated WebRTC transport, before and after
 * the handover alike, so both sides always agree about where a feature lives.
 */
/**
 * `desklink.heartbeat`, carrying `dozing` (contract §11): with the phone's screen off the desk
 * stretches its dead time and stops probing the data channel, since a frozen phone cannot answer.
 * [reply] marks a report that must not be answered (answers and state pushes alike).
 */
internal fun heartbeatPacket(reply: Boolean, interactive: Boolean): NetworkPacket =
    NetworkPacket(DeskLinkProtocol.PACKET_TYPE_HEARTBEAT).apply {
        if (reply) this["reply"] = true
        this["dozing"] = !interactive
    }

/**
 * The empty snapshots for phone-owned features that are turned off or lack their permission:
 * battery, notifications, this phone's media, calls. A loaded plugin sends its own instead.
 */
internal fun emptyStatesFor(device: Device): List<NetworkPacket> {
    fun silent(cls: Class<out org.desklink.mobile.plugins.Plugin>): Boolean {
        val key = org.desklink.mobile.plugins.Plugin.getPluginKey(cls)
        return !device.isPluginEnabled(key) || device.loadedPlugins[key] == null || device.pluginsWithoutPermissions.containsKey(key)
    }
    return buildList {
        if (silent(org.desklink.mobile.plugins.battery.BatteryPlugin::class.java)) add(org.desklink.mobile.plugins.battery.BatteryPlugin.turnedOffPacket())
        if (silent(org.desklink.mobile.plugins.notifications.NotificationsPlugin::class.java)) add(org.desklink.mobile.plugins.notifications.NotificationsPlugin.snapshotPacket(emptyList()))
        if (silent(org.desklink.mobile.plugins.mprisreceiver.MprisReceiverPlugin::class.java)) add(org.desklink.mobile.plugins.mprisreceiver.MprisReceiverPlugin.emptyStatePacket())
        if (silent(org.desklink.mobile.plugins.telephony.TelephonyPlugin::class.java)) add(org.desklink.mobile.plugins.telephony.CallTracker.emptySnapshot())
        // F5: not shared (off, or no permission): the desk deletes its copy of the contacts.
        if (silent(org.desklink.mobile.plugins.contacts.ContactsPlugin::class.java)) add(org.desklink.mobile.plugins.contacts.ContactsBook.notSharingPacket())
    }
}

/** A queued packet that waited longer than [PENDING_FEATURE_MAX_AGE_MS] and was never sent. */
class QueuedPacketExpiredException(type: String) : IllegalStateException("DeskLink dropped stale queued $type")

/**
 * A send the person asked for (a tap: ping, ring, lock, a switch…), as opposed to one the app
 * makes by itself on load or on a timer. Only these say so out loud when they expire unsent.
 */
open class UserActionCallback @JvmOverloads constructor(private val inner: Device.SendPacketStatusCallback? = null) : Device.SendPacketStatusCallback() {
    override fun onSuccess() { inner?.onSuccess() }
    override fun onFailure(e: Throwable) { inner?.onFailure(e) }
    override fun onPayloadProgressChanged(percent: Int) { inner?.onPayloadProgressChanged(percent) }
}

internal fun expiredUserActions(callbacks: Collection<Device.SendPacketStatusCallback>): Int = callbacks.count { it is UserActionCallback }

/** Queued features older than this are failed, not sent. Matches the desktop. */
internal const val PENDING_FEATURE_MAX_AGE_MS = 30_000L

/** At most one "not sent" notice this often. */
internal const val NOT_SENT_TOAST_INTERVAL_MS = 5_000L

/** Files being sent to one desk at once, and files waiting for a free slot. */
internal const val FILE_SENDS_IN_FLIGHT = 2
internal const val FILE_SENDS_WAITING = 256

internal fun shouldRejectPairedLanFeaturePacket(
    fromWebRtc: Boolean,
    paired: Boolean,
    bootstrapPacket: Boolean,
): Boolean = !fromWebRtc && paired && !bootstrapPacket

private const val SEND_FAILURE_LOG_INTERVAL_NANOS = 5_000_000_000L
