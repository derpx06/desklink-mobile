/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.backends.relay

import android.content.Context
import android.net.Network
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.desklink.mobile.BuildConfig
import org.desklink.mobile.DeviceInfo
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.backends.BaseLinkProvider
import org.desklink.mobile.helpers.DeviceHelper
import org.desklink.mobile.helpers.TrustedDevices
import org.desklink.mobile.helpers.security.RsaHelper
import org.desklink.mobile.helpers.security.SslHelper
import org.desklink.mobile.webrtc.IceServerSpec
import org.desklink.mobile.webrtc.identitySignature
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.cert.Certificate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The relay preferences (docs/RELAY.md §7). */
object RelaySettings {
    const val KEY_ENABLED = "relay_enabled"
    const val KEY_URL = "relay_url"
    const val KEY_USE_TURN = "relay_use_turn"
    const val KEY_FORCE_RELAY = "relay_debug_force_relay"
    const val KEY_FORCE_TURN = "relay_debug_force_turn"

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    fun enabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)

    /** Empty means the built-in default, which is itself empty unless the build names a relay. */
    fun url(context: Context): String =
        prefs(context).getString(KEY_URL, "").orEmpty().trim().ifEmpty { BuildConfig.DEFAULT_RELAY_URL }

    /** A relay address exists (entered, or built in). Without one nothing relay-related runs or shows. */
    fun configured(context: Context) = url(context).isNotBlank()

    /** Whether the relay connection runs: switched on, an address to reach, and someone paired to reach. */
    fun shouldRun(enabled: Boolean, url: String, hasPairedDevices: Boolean) = enabled && url.isNotBlank() && hasPairedDevices

    fun useTurn(context: Context) = prefs(context).getBoolean(KEY_USE_TURN, true)

    /** Debug builds only: no LAN or Bluetooth links at all. Read when the service starts. */
    fun forceRelay(context: Context) = BuildConfig.DEBUG && prefs(context).getBoolean(KEY_FORCE_RELAY, false)

    /** Debug builds only: relay-only ICE transport policy. */
    fun forceTurn(context: Context) = BuildConfig.DEBUG && prefs(context).getBoolean(KEY_FORCE_TURN, false)
}

/**
 * Reaches paired devices through the DeskLink relay when the LAN cannot
 * (docs/RELAY.md). One WebSocket: authenticate with the device key, allow every
 * paired device, run the end-to-end handshake ([RelayLinkSession]) with each one
 * that comes online, and hand a verified [RelayLink] to the device layer. Also
 * fetches TURN credentials for WebRTC.
 *
 * Reconnects walk 1, 2, 5, 10, 30, 30 s and then stop in an explicit
 * "unavailable" state; only [reset] (network, foreground, settings, pairing)
 * starts over. Nothing here loops on its own.
 *
 * Threads: OkHttp's reader thread and [timers] mutate state under [lock]; every
 * call into the device layer goes through [events], in order and never under
 * [lock] — the device layer may block on [iceServersFor], which the reader
 * thread answers.
 */
class RelayLinkProvider(private val context: Context) : BaseLinkProvider() {
    enum class Status { OFF, CONNECTING, ONLINE, UNAVAILABLE, UPDATE_REQUIRED }

    private class Peer(val deviceId: String, val certificate: Certificate, val session: RelayLinkSession) {
        var online = false
        var lastStart = 0L
        var link: RelayLink? = null
        val turnWaiters = mutableListOf<ArrayBlockingQueue<List<IceServerSpec>>>()
    }

    private val lock = Any()
    // Created by onStart and shut down by onStop, so a re-created service leaks no threads.
    private lateinit var client: OkHttpClient
    private lateinit var timers: ScheduledExecutorService
    private lateinit var events: ExecutorService
    private var socket: WebSocket? = null
    /** Bumped for every socket; callbacks from an older one are ignored. */
    private var generation = 0L
    private var localFp = ""
    private var peers = emptyMap<String, Peer>()
    private var rung = 0
    private var retry: ScheduledFuture<*>? = null
    private var tick: ScheduledFuture<*>? = null
    private var started = false

    override fun onStart() {
        synchronized(lock) {
            started = true
            instance = this
            client = OkHttpClient.Builder().pingInterval(PING_SECONDS, TimeUnit.SECONDS).build()
            timers = Executors.newSingleThreadScheduledExecutor { Thread(it, "DeskLink-Relay").apply { isDaemon = true } }
            events = Executors.newSingleThreadExecutor { Thread(it, "DeskLink-Relay-Events").apply { isDaemon = true } }
            tick = timers.scheduleWithFixedDelay({ synchronized(lock) { restartStalledLocked() } }, 1, 1, TimeUnit.SECONDS)
        }
        reset(reconnect = true)
    }

    override fun onStop() {
        synchronized(lock) {
            if (!started) return
            started = false
            if (instance === this) instance = null
            tick?.cancel(false)
            retry?.cancel(false)
            generation++
            socket?.close(1000, null)
            socket = null
            dropAllLocked()
            setStatus(Status.OFF)
            // Queued link-lost events still run; nothing new is accepted.
            timers.shutdownNow()
            events.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /** A LAN interface moved. Not a reason to drop a working relay socket, only to retry a failed one. */
    override fun onNetworkChange(network: Network?) = reset(reconnect = false)

    // refreshDiscovery stays a no-op: discovery refreshes never reset the ladder (docs/RELAY.md §1).

    override fun getName(): String = "RelayLinkProvider"

    /** Below LAN (20) and Bluetooth (10): the relay is the last resort. */
    override fun getPriority(): Int = 5

    /**
     * Starts the ladder over: the default network, the settings or the app's
     * foreground state changed. [reconnect] replaces a live socket too (a new
     * network or relay URL); otherwise only a failed or stopped one is retried.
     */
    fun reset(reconnect: Boolean) = synchronized(lock) {
        rung = 0
        if (!started) return@synchronized
        if (reconnect || socket == null || !wanted()) connectLocked()
    }

    /** The trust store changed: re-send the allow list, or connect if this is the first pairing. */
    fun onTrustChanged() = synchronized(lock) {
        rung = 0
        if (!started) return@synchronized
        val ws = socket
        if (ws != null && status == Status.ONLINE && wanted()) {
            loadPeersLocked()
            sendAllowLocked(ws)
        } else {
            connectLocked()
        }
    }

    /**
     * ICE servers the relay grants for the WebRTC peer with [deviceId], or none
     * within [TURN_TIMEOUT_MS]. Blocking; never call it from this provider's threads.
     */
    internal fun iceServersFor(deviceId: String): List<IceServerSpec> {
        val waiter = synchronized(lock) {
            val ws = socket?.takeIf { status == Status.ONLINE } ?: return emptyList()
            val (fp, peer) = peers.entries.firstOrNull { it.value.deviceId == deviceId } ?: return emptyList()
            ArrayBlockingQueue<List<IceServerSpec>>(1).also { waiter ->
                peer.turnWaiters += waiter
                ws.send(JSONObject().put("t", "turn").put("peer", fp).toString())
            }
        }
        return waiter.poll(TURN_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: run {
            synchronized(lock) { peers.values.forEach { it.turnWaiters.remove(waiter) } }
            emptyList()
        }
    }

    /** Seals and sends one packet on [link]; false once the link is gone. */
    internal fun send(link: RelayLink, plaintext: ByteArray): Boolean = synchronized(lock) {
        val peer = peers[link.fp]
        val ws = socket
        if (link.closed || ws == null || peer?.link !== link) return@synchronized false
        runCatching { sendDataLocked(ws, link.fp, peer.session.seal(plaintext)) }.getOrDefault(false)
    }

    /** The device layer closed [link]. The session is dropped; the initiator's stall check rebuilds it. */
    internal fun dropLink(link: RelayLink) = synchronized(lock) {
        val peer = peers[link.fp]
        if (peer?.link === link) {
            peer.session.reset()
            detachLocked(peer)
        } else {
            link.closed = true
        }
    }

    // No address means no relay at all: no lookup, no ladder, no "unavailable" (status stays OFF).
    private fun wanted(): Boolean =
        RelaySettings.shouldRun(RelaySettings.enabled(context), RelaySettings.url(context), TrustedDevices.getAllTrustedDevices(context).isNotEmpty())

    private fun connectLocked() {
        retry?.cancel(false)
        retry = null
        generation++
        socket?.cancel()
        socket = null
        dropAllLocked()
        if (!started || !wanted()) {
            setStatus(Status.OFF)
            return
        }
        val url = RelaySettings.url(context)
        val host = relayHost(url)
        val schemeOk = url.startsWith("wss://") || (BuildConfig.DEBUG && url.startsWith("ws://"))
        if (host.isNullOrEmpty() || !schemeOk) {
            Log.w(TAG, "Invalid relay address")
            setStatus(Status.UNAVAILABLE)
            return
        }
        setStatus(Status.CONNECTING)
        localFp = RelayLinkCrypto.fingerprint(SslHelper.certificate.encoded)
        loadPeersLocked()
        val socketGeneration = generation
        socket = client.newWebSocket(Request.Builder().url(url).build(), Listener(socketGeneration, host))
    }

    private inner class Listener(private val socketGeneration: Long, private val host: String) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
            if (socketGeneration != generation) return@synchronized
            val message = runCatching { JSONObject(text) }.getOrNull()
            if (message == null) {
                Log.w(TAG, "Malformed relay frame")
                webSocket.cancel()
                endedLocked(null)
                return@synchronized
            }
            handleLocked(webSocket, message, host)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            synchronized(lock) { if (socketGeneration == generation) endedLocked(code) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            synchronized(lock) { if (socketGeneration == generation) endedLocked(code) }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
            if (socketGeneration == generation) {
                Log.i(TAG, "Relay connection failed: ${t.javaClass.simpleName}")
                endedLocked(null)
            }
        }
    }

    /** The current socket ended; [code] is its close code, if it closed cleanly. */
    private fun endedLocked(code: Int?) {
        generation++
        socket = null
        dropAllLocked()
        when (code) {
            CLOSE_UPGRADE_REQUIRED -> setStatus(Status.UPDATE_REQUIRED)
            // A newer connection of ours took over; reconnecting would only replace it back.
            CLOSE_REPLACED -> setStatus(Status.UNAVAILABLE)
            else -> {
                val delay = LADDER_SECONDS.getOrNull(rung)
                if (delay == null) {
                    Log.w(TAG, "Relay unavailable (close code $code)")
                    setStatus(Status.UNAVAILABLE)
                } else {
                    rung++
                    setStatus(Status.CONNECTING)
                    retry = timers.schedule({ synchronized(lock) { retryLocked() } }, delay, TimeUnit.SECONDS)
                }
            }
        }
    }

    /** A scheduled attempt. One that throws is a failed attempt and takes the next rung. */
    private fun retryLocked() {
        if (!started) return
        try {
            connectLocked()
        } catch (error: Exception) {
            Log.w(TAG, "Relay connection attempt failed: ${error.javaClass.simpleName}")
            generation++
            socket?.cancel()
            socket = null
            endedLocked(null)
        }
    }

    private fun handleLocked(ws: WebSocket, message: JSONObject, host: String) {
        when (message.optString("t")) {
            "challenge" -> {
                if (message.optInt("minV", PROTOCOL_VERSION) > PROTOCOL_VERSION) {
                    ws.close(1000, null)
                    generation++
                    socket = null
                    setStatus(Status.UPDATE_REQUIRED)
                    return
                }
                val key = RsaHelper.getPrivateKey(context)
                val input = "$AUTH_CONTEXT\n${message.optString("nonce")}\n$host".toByteArray(Charsets.UTF_8)
                val signature = identitySignature(key.algorithm).run {
                    initSign(key)
                    update(input)
                    sign()
                }
                ws.send(
                    JSONObject()
                        .put("t", "auth")
                        .put("v", PROTOCOL_VERSION)
                        .put("cert", RelayLinkCrypto.b64(SslHelper.certificate.encoded))
                        .put("sig", RelayLinkCrypto.b64(signature))
                        .toString(),
                )
            }
            "welcome" -> {
                if (message.optString("fp") != localFp) {
                    Log.w(TAG, "The relay refused this device")
                    ws.cancel()
                    endedLocked(null)
                    return
                }
                setStatus(Status.ONLINE)
                sendAllowLocked(ws)
                // The ladder starts over only once the connection has proved
                // stable: a relay that welcomes and then drops us (rate limit,
                // proxy timeout) must still run out of rungs, never loop.
                val welcomed = generation
                timers.schedule({
                    synchronized(lock) { if (generation == welcomed && status == Status.ONLINE) rung = 0 }
                }, STABLE_SECONDS, TimeUnit.SECONDS)
            }
            "presence" -> {
                val fp = message.optString("fp")
                val peer = peers[fp] ?: return
                if (message.optBoolean("online")) {
                    peer.online = true
                    // The responder never speaks on presence: a kick here crossed
                    // the initiator's first h1 and the two restarted forever.
                    if (peer.session.role == RelayLinkCrypto.Role.INITIATOR) startLocked(ws, fp, peer, force = false)
                } else {
                    peer.online = false
                    peer.session.reset()
                    detachLocked(peer)
                    failTurnLocked(peer)
                }
            }
            "msg" -> {
                val from = message.optString("from")
                val peer = peers[from] ?: return
                val data = message.optJSONObject("data") ?: return
                peer.online = true
                val produced = try {
                    peer.session.receive(data)
                } catch (error: Exception) {
                    // Never log the frame: only that this peer's session broke.
                    Log.i(TAG, "Relay link with ${peer.deviceId} reset: ${error.message}")
                    // Reset even when the spacing below skips the restart, so
                    // the 10 s stall check sees an unfinished handshake.
                    peer.session.reset()
                    detachLocked(peer)
                    startLocked(ws, from, peer, force = false)
                    return
                }
                produced.forEach { event ->
                    when (event) {
                        is RelayLinkSession.Event.Send -> sendDataLocked(ws, from, event.message)
                        is RelayLinkSession.Event.Established -> establishedLocked(from, peer, event.identity)
                        is RelayLinkSession.Event.Packet -> deliverLocked(peer, event.bytes)
                    }
                }
            }
            "turnCreds" -> peers[message.optString("peer")]?.let { peer ->
                val servers = parseIceServers(message.optJSONArray("iceServers"))
                peer.turnWaiters.forEach { it.offer(servers) }
                peer.turnWaiters.clear()
            }
            "error" -> {
                val code = message.optString("code")
                if (code == "turn-denied" || code == "turn-quota") peers[message.optString("to")]?.let(::failTurnLocked)
            }
            // Unknown server frames are ignored, as unknown fields are.
        }
    }

    /** (Re)starts the handshake: the initiator's h1, or the responder's kick after a failure. At most once per 2 s. */
    private fun startLocked(ws: WebSocket, fp: String, peer: Peer, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && peer.lastStart != 0L && now - peer.lastStart < HANDSHAKE_SPACING_MS) return
        peer.lastStart = now
        runCatching { sendDataLocked(ws, fp, peer.session.start()) }
            .onFailure { Log.w(TAG, "Relay link could not start: ${it.javaClass.simpleName}") }
    }

    /** An initiator whose handshake has not finished 10 s after its h1 sends a new one (a lost frame). */
    private fun restartStalledLocked() {
        val ws = socket?.takeIf { status == Status.ONLINE } ?: return
        val now = SystemClock.elapsedRealtime()
        peers.forEach { (fp, peer) ->
            if (peer.online && peer.session.role == RelayLinkCrypto.Role.INITIATOR && !peer.session.isEstablished &&
                now - peer.lastStart > HANDSHAKE_TIMEOUT_MS
            ) {
                startLocked(ws, fp, peer, force = true)
            }
        }
    }

    private fun establishedLocked(fp: String, peer: Peer, identity: String) {
        val packet = NetworkPacket.unserialize(identity)
        if (DeviceInfo.loadProtocolVersionFromSettings(context, peer.deviceId) > packet.getInt("protocolVersion")) {
            Log.w(TAG, "Refusing a relay link to ${peer.deviceId}: protocol downgrade")
            peer.session.reset()
            return
        }
        // A new session replaces whatever the old one carried.
        detachLocked(peer)
        val link = RelayLink(context, this, fp, DeviceInfo.fromIdentityPacketAndCert(packet, peer.certificate))
        peer.link = link
        Log.i(TAG, "Relay link with ${peer.deviceId} established")
        events.execute { if (!link.closed) onConnectionReceived(link) }
    }

    private fun deliverLocked(peer: Peer, bytes: ByteArray) {
        val link = peer.link ?: return
        val packet = runCatching { NetworkPacket.unserialize(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
        if (!RelayLink.allowedOnRelay(packet) || packet.hasPayloadTransferInfo()) {
            Log.w(TAG, "Dropped ${packet.type} from ${peer.deviceId}: not allowed over a relay link")
            return
        }
        events.execute { if (!link.closed) link.packetReceived(packet) }
    }

    private fun detachLocked(peer: Peer) {
        val link = peer.link ?: return
        peer.link = null
        link.closed = true
        events.execute { onConnectionLost(link) }
    }

    private fun failTurnLocked(peer: Peer) {
        peer.turnWaiters.forEach { it.offer(emptyList()) }
        peer.turnWaiters.clear()
    }

    private fun dropAllLocked() {
        peers.values.forEach { peer ->
            peer.online = false
            peer.session.reset()
            detachLocked(peer)
            failTurnLocked(peer)
        }
    }

    /** One [Peer] per trusted device, keyed by the fingerprint of its *stored* certificate. Unchanged peers are kept. */
    private fun loadPeersLocked() {
        val localKey = RsaHelper.getPrivateKey(context)
        val localIdentity = DeviceHelper.getDeviceInfo(context).toIdentityPacket().serialize().trimEnd('\n')
        val fresh = HashMap<String, Peer>()
        for (deviceId in TrustedDevices.getAllTrustedDevices(context)) {
            if (!TrustedDevices.isCertificateStored(context, deviceId)) continue
            val certificate = runCatching { TrustedDevices.getDeviceCertificate(context, deviceId) }.getOrNull() ?: continue
            val fp = RelayLinkCrypto.fingerprint(certificate.encoded)
            fresh[fp] = peers[fp]?.takeIf { it.deviceId == deviceId } ?: Peer(
                deviceId,
                certificate,
                RelayLinkSession(
                    RelayLinkSession.Identities(localFp, fp, localKey, localIdentity, certificate.publicKey, deviceId),
                ),
            )
            if (fresh.size == MAX_ALLOW) break
        }
        peers.filterKeys { it !in fresh }.values.forEach { gone ->
            gone.session.reset()
            detachLocked(gone)
            failTurnLocked(gone)
        }
        peers = fresh
    }

    /** After every welcome and on every trust change; it replaces the relay's set. */
    private fun sendAllowLocked(ws: WebSocket) {
        ws.send(JSONObject().put("t", "allow").put("fps", JSONArray(peers.keys.toList())).toString())
    }

    private fun sendDataLocked(ws: WebSocket, to: String, data: JSONObject): Boolean =
        ws.send(JSONObject().put("t", "send").put("to", to).put("data", data).toString())

    companion object {
        private const val TAG = "DeskLink/Relay"
        private const val PROTOCOL_VERSION = 1
        private const val AUTH_CONTEXT = "desklink-relay-auth-v1"
        private const val CLOSE_REPLACED = 4409
        private const val CLOSE_UPGRADE_REQUIRED = 4426
        private const val MAX_ALLOW = 32
        private const val PING_SECONDS = 20L
        private const val HANDSHAKE_SPACING_MS = 2_000L
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        private const val TURN_TIMEOUT_MS = 2_000L
        private const val STABLE_SECONDS = 60L
        private val LADDER_SECONDS = longArrayOf(1, 2, 5, 10, 30, 30)

        /** The running provider, for TURN credentials and resets; null while the service is stopped. */
        @Volatile
        @JvmStatic
        var instance: RelayLinkProvider? = null
            private set

        private val statusData = MutableLiveData(Status.OFF)

        /** For the settings screen. */
        @JvmStatic
        val statusLive: LiveData<Status> get() = statusData

        @Volatile
        @JvmStatic
        var status: Status = Status.OFF
            private set

        private fun setStatus(value: Status) {
            status = value
            statusData.postValue(value)
        }

        /** `turnCreds.iceServers`; `username`/`credential` are absent on STUN entries. */
        private fun parseIceServers(array: JSONArray?): List<IceServerSpec> =
            (0 until (array?.length() ?: 0)).mapNotNull { index ->
                val entry = array?.optJSONObject(index) ?: return@mapNotNull null
                val urls = entry.optJSONArray("urls") ?: return@mapNotNull null
                val list = (0 until urls.length()).mapNotNull { urls.optString(it).takeIf(String::isNotEmpty) }
                if (list.isEmpty()) return@mapNotNull null
                IceServerSpec(
                    list,
                    entry.optString("username").takeIf(String::isNotEmpty),
                    entry.optString("credential").takeIf(String::isNotEmpty),
                )
            }
    }
}

/**
 * The bare host the relay auth signs, as the desktop's `host_of`: no IPv6
 * brackets, port or userinfo. [URI.getHost] is null for a hostname with `_`,
 * so that falls back to taking the authority apart by hand.
 */
internal fun relayHost(url: String): String? {
    val parsed = runCatching { URI(url).host }.getOrNull()
        ?: url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@')
            .let { authority ->
                if (authority.startsWith("[")) authority.substringBefore(']') + "]"
                else authority.substringBefore(':')
            }
    return parsed.removePrefix("[").removeSuffix("]").takeIf(String::isNotEmpty)
}
