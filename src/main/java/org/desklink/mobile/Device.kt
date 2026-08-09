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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.apache.commons.collections4.MultiValuedMap
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap
import org.desklink.mobile.backends.BaseLink
import org.desklink.mobile.backends.BaseLink.PacketReceiver
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
import java.util.Vector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class Device : PacketReceiver {

    data class NetworkPacketWithCallback(val np : NetworkPacket, val callback: SendPacketStatusCallback)

    val context: Context

    @VisibleForTesting
    val deviceInfo: DeviceInfo

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

    private val sendChannel = Channel<NetworkPacketWithCallback>(64)
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

    val certificate: Certificate
        get() = deviceInfo.certificate

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
    fun acceptPairing() {
        Log.i("Device", "Accepted pair request started by the other device")
        pairingHandler.acceptPairing()
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

                // Store current device certificate so we can check it in the future (TOFU)
                deviceInfo.saveInSettings(context)

                // Store as trusted device
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
            1,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_DEVICE_ID, deviceId)
            putExtra(MainActivity.PAIR_REQUEST_STATUS, MainActivity.PAIRING_ACCEPTED)
        }
        val rejectIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_DEVICE_ID, deviceId)
            putExtra(MainActivity.PAIR_REQUEST_STATUS, MainActivity.PAIRING_REJECTED)
        }

        val acceptedPendingIntent = PendingIntent.getActivity(
            context,
            2,
            acceptIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val rejectedPendingIntent = PendingIntent.getActivity(
            context,
            4,
            rejectIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

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


    val requiresConnectionPowerLease: Boolean
        get() = hasBootstrapLink || hasActiveWebRtcFeatureTransport || webRtcRecoveryPending

    fun setSessionTransport(transport: SessionTransport?) {
        sessionTransport = transport
    }

    fun setWebRtcTransport(transport: WebRtcTransport?) {
        setWebRtcTransport(transport, reloadPlugins = true)
    }

    fun setWebRtcTransport(transport: WebRtcTransport?, reloadPlugins: Boolean) {
        if (webRtcTransport === transport) return
        webRtcTransport = transport
        // Plugin availability is calculated from reachability. Re-evaluate it
        // when WebRTC becomes ready or is closed so a transient loss of the
        // bootstrap socket cannot disable a live paired feature session.
        if (reloadPlugins) {
            lifecycle.enqueue("plugin reload after WebRTC transport change") {
                reloadPluginsFromSettings()
            }
        }
    }

    /** Clears every WebRTC callback and queued bootstrap packet on unpair. */
    fun clearWebRtcBindingsForUnpair() {
        webRtcTransport = null
        webRtcRecoveryPending = false
        payloadPacketHandler = null
        remoteInputPacketPreparer = null
        val pending = synchronized(controlPacketLock) {
            controlPacketHandler = null
            pendingControlPackets.drain()
        }
        pending.forEach { it.payload?.close() }
    }

    fun setWebRtcRecoveryPending(pending: Boolean) {
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

    fun addLink(link: BaseLink) {
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

        synchronized(sendChannel) {
            if (sendCoroutine == null) {
                sendCoroutine = CoroutineScope(Dispatchers.IO).launch {
                    for ((np, callback) in sendChannel) {
                        sendPacketBlocking(np, callback)
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
            synchronized(sendChannel) {
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

        val bootstrapPacket = np.type == NetworkPacket.PACKET_TYPE_IDENTITY ||
            np.type == NetworkPacket.PACKET_TYPE_PAIR ||
            np.type == DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1
        if (fromWebRtc && bootstrapPacket) {
            Log.w("DeskLink/WebRTC", "Rejected bootstrap packet on the WebRTC feature path")
            np.payload?.close()
            return
        }
        if (fromWebRtc && !bootstrapPacket && !WebRtcFeatureProfile.allows(np.type)) {
            Log.w("DeskLink/WebRTC", "Rejected feature outside the active WebRTC profile: ${np.type}")
            np.payload?.close()
            return
        }
        // Paired feature traffic is WebRTC-only even while handover is still
        // incomplete. LAN remains restricted to bootstrap packets so features
        // cannot silently downgrade to a different transport.
        if (shouldRejectPairedLanFeaturePacket(
                fromWebRtc = fromWebRtc,
                paired = isPaired,
                bootstrapPacket = bootstrapPacket,
                webRtcFeatureTransportReady = webRtcTransport?.isReadyForPackets() == true,
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

        // pluginsByIncomingInterface may not be built yet
        if (pluginsByIncomingInterface.isEmpty) {
            reloadPluginsFromSettings()
        }

        if (!isPaired) {
            // If it is pair packet, it should be captured by "if" at start
            // If not and device is paired, it should be captured by isPaired
            // Else unpair, this handles the situation when one device unpairs,
            // but other don't know like unpairing when wi-fi is off.

            unpair()
        }

        // The following code when `isPaired == false` is NOT USED.
        // It adds support for receiving packets from not trusted devices,
        // but as of March 2023 no plugin implements "onUnpairedDevicePacketReceived".
        notifyPluginPacketReceived(np)
    }

    private fun notifyPluginPacketReceived(np: NetworkPacket) {
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

        override fun onFailure(e: Throwable) {
            Log.e("Device", "Send packet exception", e)
        }
    }

    /**
     * Send a packet to the device asynchronously
     * @param np The packet
     * @param callback A callback for success/failure
     */
    @AnyThread
    fun sendPacket(np: NetworkPacket, callback: SendPacketStatusCallback) {
        val result = sendChannel.trySend(NetworkPacketWithCallback(np, callback))
        if (result.isFailure) {
            callback.onFailure(IllegalStateException("DeskLink outbound queue is full or closed"))
        }
    }

    @AnyThread
    fun sendPacket(np: NetworkPacket) = sendPacket(np, defaultCallback)

    /**
     * Sends only discovery/pairing/signaling packets on the bootstrap LAN
     * link. Paired feature packets must never use this path.
     */
    @AnyThread
    fun sendBootstrapPacket(np: NetworkPacket, callback: SendPacketStatusCallback): Boolean {
        val bootstrapPacket = np.type == NetworkPacket.PACKET_TYPE_IDENTITY ||
            np.type == NetworkPacket.PACKET_TYPE_PAIR ||
            np.type == DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1
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
        val bootstrapPacket = np.type == NetworkPacket.PACKET_TYPE_IDENTITY ||
            np.type == NetworkPacket.PACKET_TYPE_PAIR ||
            np.type == DeskLinkProtocol.PACKET_TYPE_WEBRTC_SIGNAL_V1
        if (!bootstrapPacket && !supportsPacketType(np.type)) {
            Log.e("DeskLink/sendPacket", "Tried to send an unsupported packet type ${np.type} to: ${deviceInfo.name}")
            callback.onFailure(IllegalArgumentException("Unsupported packet type ${np.type}"))
            return false
        }

        val transport = webRtcTransport
        val readyTransport = transport?.takeIf { it.isReadyForPackets() }
        val filePayload = np.type == DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST &&
            np.getString("filename").isNotEmpty() && np.payload != null
        if (isPaired && !bootstrapPacket && !WebRtcFeatureProfile.allows(np.type)) {
            val error = IllegalStateException(
                "${np.type} is not enabled in the current DeskLink WebRTC feature profile",
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
                val error = IllegalStateException(
                    "DeskLink WebRTC feature transport is not ready; paired features are not sent over LAN",
                )
                callback.onFailure(error)
                np.payload?.close()
                countSent(deviceId, np.type, false)
                return false
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
            val packetForTransport = try {
                if (
                    np.type == DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST ||
                    np.type == DeskLinkProtocol.PACKET_TYPE_PRESENTER
                ) {
                    requireNotNull(remoteInputPacketPreparer) {
                        "DeskLink remote-control lease is not active"
                    }.prepare(np)
                } else {
                    np
                }
            } catch (error: Throwable) {
                callback.onFailure(error)
                countSent(deviceId, np.type, false)
                return false
            }
            return try {
                activeTransport.sendPacket(
                    packetForTransport,
                    object : TransportCallback {
                        override fun onSuccess() = callback.onSuccess()

                        override fun onFailure(error: TransportError) = callback.onFailure(error)

                        override fun onProgress(percent: Int) {
                            callback.onPayloadProgressChanged(percent)
                        }
                    },
                )
                countSent(deviceId, np.type, true)
                true
            } catch (e: Exception) {
                callback.onFailure(e)
                countSent(deviceId, np.type, false)
                false
            }
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

    fun onPluginsChanged() = pluginsChangedListeners.forEach { it.onPluginsChanged(this) }

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
 */
internal fun shouldRejectPairedLanFeaturePacket(
    fromWebRtc: Boolean,
    paired: Boolean,
    bootstrapPacket: Boolean,
    webRtcFeatureTransportReady: Boolean,
): Boolean = !fromWebRtc && paired && !bootstrapPacket

/** Paired feature sends are allowed only on the authenticated WebRTC transport. */
internal fun webRtcFeatureTransportShouldHandlePacket(ready: Boolean): Boolean = ready
