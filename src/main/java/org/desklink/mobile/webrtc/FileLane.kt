/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import android.content.Context
import android.util.Log
import org.desklink.mobile.helpers.DeviceHelper
import org.desklink.mobile.helpers.TrustedDevices
import org.desklink.mobile.helpers.readLineBounded
import org.desklink.mobile.helpers.security.SslHelper
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket

/**
 * Lane A of the bulk lanes (W18): the file engine's `DLF2` frames over the pinned, mutually authenticated TLS connection to the paired
 * computer on the LAN, with a one-time token. The control plane (offer, accept, acks, lane switches) stays on the WebRTC control
 * channel; nothing but frames crosses a lane. Wire: stageD-protocol.md "W18", vectors in `filelane_bodies.json`.
 */
object FileLaneProtocol {
    const val V = 1
    const val FIRST_TYPE = "desklink.filelane"
    const val TOKEN_TYPE = "desklink.filelane.token"
    const val READY_TYPE = "desklink.filelane.ready"
    const val MIN_FILE_BYTES = 8L * 1024 * 1024
    const val CONNECT_MS = 3_000
    const val TOKEN_TTL_MS = 60_000L
    const val STALL_MS = 5_000L
    const val FIRST_LINE_BYTES = 4096
    const val LANES_PER_PEER = 2

    /** First wait before a failed lane is asked for again; doubled with each failure in a row. */
    const val RETRY_MS = 10_000L

    /** A lane that carries no byte for this long is dead (the receiver closes it and asks for the rest over WebRTC). */
    const val SILENT_MS = 30_000

    /**
     * The address a lane may dial, from the selected pair's literal: IPv4 private (10/8, 172.16/12, 192.168/16) or IPv6 unique-local, and on a
     * subnet this phone has an address in. Link-local, public, hostnames and anything else give null.
     */
    fun laneAddress(literal: String?, locals: () -> List<Pair<InetAddress, Int>> = { localAddresses() }): InetAddress? {
        val text = literal?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 && it.all { c -> c.isDigit() || c in "abcdefABCDEF.:" } } ?: return null
        if (!text.contains(':') && text.count { it == '.' } != 3) return null
        val address = runCatching { InetAddress.getByName(text) }.getOrNull() ?: return null // a literal: no lookup happens
        val bytes = address.address
        val private = when (bytes.size) {
            4 -> address.isSiteLocalAddress
            16 -> (bytes[0].toInt() and 0xfe) == 0xfc
            else -> false
        }
        if (!private || address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress) return null
        return address.takeIf { target -> locals().any { (local, prefix) -> local.address.size == bytes.size && sameSubnet(local.address, target.address, prefix) } }
    }

    private fun sameSubnet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
        var bits = prefix
        for (i in a.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xff else (0xff shl (8 - bits)) and 0xff
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    private fun localAddresses(): List<Pair<InetAddress, Int>> =
        runCatching { java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.flatMap { n -> n.interfaceAddresses.map { it.address to it.networkPrefixLength.toInt() } } }.getOrDefault(emptyList())

    /** The `reason` of a `block-nack` that only means "the lane closed under me" (not a damaged block). */
    const val NACK_REASON = "lane"

    /** A refusal's reason on the `ready` line. */
    const val REASON_TOKEN = "token"
    const val REASON_TRANSFER = "transfer"
    const val REASON_RANGE = "range"
    const val REASON_BUSY = "busy"
    const val REASON_PATH = "path"

    private val TOKEN = Regex("[0-9a-f]{32}")

    fun validToken(value: String?) = value != null && TOKEN.matches(value)

    fun newToken(random: SecureRandom = SecureRandom()): String =
        ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    // Lines are built by hand: the key order is part of the vectors, and JSONObject's is not guaranteed.
    private fun q(value: String) = JSONObject.quote(value)

    fun firstLine(id: Long, deviceId: String, targetDeviceId: String) =
        """{"id":$id,"type":"$FIRST_TYPE","body":{"v":$V,"deviceId":${q(deviceId)},"targetDeviceId":${q(targetDeviceId)}}}"""

    fun tokenLine(transferId: String, token: String, pass: Int, startBlock: Int) =
        """{"type":"$TOKEN_TYPE","body":{"transferId":${q(transferId)},"token":${q(token)},"pass":$pass,"startBlock":$startBlock}}"""

    fun readyLine(ok: Boolean, reason: String? = null) =
        if (ok) """{"type":"$READY_TYPE","body":{"ok":true}}""" else """{"type":"$READY_TYPE","body":{"ok":false,"reason":${q(reason ?: REASON_TOKEN)}}}"""

    data class First(val deviceId: String, val targetDeviceId: String)
    data class Token(val transferId: String, val token: String, val pass: Int, val startBlock: Int)

    /** Null for anything but a well-formed first line of version 1. */
    fun parseFirst(line: String): First? = runCatching {
        val o = JSONObject(line)
        if (o.getString("type") != FIRST_TYPE) return null
        val b = o.getJSONObject("body")
        if (b.getInt("v") != V) return null
        First(b.getString("deviceId"), b.getString("targetDeviceId")).takeIf { it.deviceId.isNotEmpty() && it.targetDeviceId.isNotEmpty() }
    }.getOrNull()

    fun parseToken(line: String): Token? = runCatching {
        val o = JSONObject(line)
        if (o.getString("type") != TOKEN_TYPE) return null
        val b = o.getJSONObject("body")
        val t = Token(b.getString("transferId"), b.getString("token"), b.getInt("pass"), b.getInt("startBlock"))
        t.takeIf { validToken(it.token) && it.pass in 0..255 && it.startBlock >= 0 }
    }.getOrNull()

    /** The `ready` answer: null on success, else the refusal's reason (unknown ones read as `token`); false = not a ready line. */
    fun parseReady(line: String): Pair<Boolean, String?>? = runCatching {
        val o = JSONObject(line)
        if (o.getString("type") != READY_TYPE) return null
        val b = o.getJSONObject("body")
        if (b.getBoolean("ok")) true to null else false to (b.optString("reason").takeIf { it.isNotEmpty() } ?: REASON_TOKEN)
    }.getOrNull()

    /** Total length of the frame whose 40-byte [header] this is, or null when the header cannot be a frame. */
    fun frameLength(header: ByteArray): Int? {
        if (header.size < FileV2.FRAME_HEADER_BYTES) return null
        if (header[0] != 'D'.code.toByte() || header[1] != 'L'.code.toByte() || header[2] != 'F'.code.toByte() || header[3] != '2'.code.toByte()) return null
        val length = java.nio.ByteBuffer.wrap(header, 36, 4).int
        val last = header[4].toInt() and FileFrame.FLAG_LAST != 0
        val total = FileV2.FRAME_HEADER_BYTES.toLong() + length + if (last) FileV2.BLOCK_TRAILER_BYTES else 0
        return total.takeIf { length >= 1 && it <= FileV2.FRAME_BYTES_PROVEN }?.toInt()
    }
}

/** What the sender of a transfer writes frames to: the TCP lane. */
interface LaneOut {
    fun write(frame: ByteArray)

    /** Bytes handed over that the connection has not taken yet (the window gate). */
    fun buffered(): Long

    fun close()

    /** Called once, from any thread, when the lane breaks. */
    var onFailure: ((Throwable) -> Unit)?
}

/** The engine's seam to the lanes (a fake in tests, [RealFileLanes] in the app). */
interface FileLanes {
    /** Sender: whether an offer of [totalSize] bytes may ask for the lane. */
    fun canOffer(totalSize: Long): Boolean

    /** Receiver: an offer asked for the lane; the accept's lane spec, or null (WebRTC carries it). */
    fun issue(transferId: String): FileLaneSpec?

    /** Sender: dial per the accept; [result] gets the open lane, or null (any thread). */
    fun dial(transferId: String, spec: FileLaneSpec, pass: Int, startBlock: Int, result: (LaneOut?) -> Unit)
}

/** A lane connection's writer: its own thread, so a slow socket never stops the file thread. */
class TcpLaneOut(private val socket: Socket, private val out: OutputStream) : LaneOut {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val queued = AtomicLong()
    @Volatile private var closed = false
    override var onFailure: ((Throwable) -> Unit)? = null

    private val thread = Thread({
        try {
            while (!closed) {
                val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                out.write(frame)
                queued.addAndGet(-frame.size.toLong())
            }
        } catch (error: Throwable) {
            if (!closed) onFailure?.invoke(error)
        } finally {
            runCatching { socket.close() }
        }
    }, "DeskLink-lane-out").apply { isDaemon = true; start() }

    override fun write(frame: ByteArray) {
        if (closed) throw IOException("The lane is closed")
        queued.addAndGet(frame.size.toLong())
        queue.add(frame)
    }

    override fun buffered(): Long = queued.get()

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }
}

/**
 * The phone as receiver and as dialler of lane A. [listenPort] is the LAN listener's port (set by the LAN provider); tokens are
 * issued at an accept, valid 60 s, single use, bound to (transfer, dialler). A lane connection reaches [accept] from the LAN listener
 * only when its first line is `desklink.filelane`; it never reaches the packet dispatcher.
 */
class RealFileLanes(
    private val context: Context,
    private val peerDeviceId: String,
    /** The transfer layer's hooks: is this transfer open, where does the receiver stand, and where do frames go. */
    private val host: Host,
) : FileLanes {
    interface Host {
        fun onLan(): Boolean
        fun peerLanAddress(): InetAddress?
        fun peerHasLane(): Boolean
        fun nextBlock(transferId: String): Int?
        fun expectedPass(transferId: String): Int?
        fun onFrame(frame: ByteArray)
        fun laneClosed(transferId: String)

        /** The lane's token was good and the lane is about to carry frames: the transfer is on it from now (before the sender says so). */
        fun laneAccepted(transferId: String)
    }

    override fun canOffer(totalSize: Long) =
        totalSize >= FileLaneProtocol.MIN_FILE_BYTES && host.peerHasLane() && host.onLan() && host.peerLanAddress() != null

    override fun issue(transferId: String): FileLaneSpec? {
        val port = listenPort
        if (port <= 0 || !host.peerHasLane() || !host.onLan() || open(peerDeviceId) >= FileLaneProtocol.LANES_PER_PEER) return null
        val token = FileLaneProtocol.newToken()
        tokens[token] = Issued(transferId, peerDeviceId, System.currentTimeMillis() + FileLaneProtocol.TOKEN_TTL_MS)
        return FileLaneSpec(v = FileLaneProtocol.V, port = port, token = token, ttlMs = FileLaneProtocol.TOKEN_TTL_MS)
    }

    override fun dial(transferId: String, spec: FileLaneSpec, pass: Int, startBlock: Int, result: (LaneOut?) -> Unit) {
        val address = host.peerLanAddress()
        val port = spec.port
        val token = spec.token
        if (address == null || port == null || token == null || !FileLaneProtocol.validToken(token)) return result(null)
        org.desklink.mobile.helpers.ThreadHelper.execute {
            result(runCatching { connect(address, port, token, transferId, pass, startBlock) }.onFailure { Log.i(TAG, "The lane did not open (${it.javaClass.simpleName})") }.getOrNull())
        }
    }

    /** The dialler: TCP, the plaintext first line, TLS as the server (pinned), the token line, `ready`. Blocking, at most 3 s. */
    private fun connect(address: InetAddress, port: Int, token: String, transferId: String, pass: Int, startBlock: Int): LaneOut? {
        val deadline = System.currentTimeMillis() + FileLaneProtocol.CONNECT_MS
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(address, port), FileLaneProtocol.CONNECT_MS)
            raw.tcpNoDelay = true
            raw.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(200)
            val me = DeviceHelper.getDeviceId(context)
            raw.getOutputStream().apply { write((FileLaneProtocol.firstLine(System.currentTimeMillis(), me, peerDeviceId) + "\n").toByteArray()); flush() }
            val tls = SslHelper.convertToSslSocket(context, raw, peerDeviceId, true, false)
            tls.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(200)
            tls.startHandshake()
            if (!pinned(context, tls, peerDeviceId)) throw IOException("The lane's certificate is not the paired one")
            tls.outputStream.apply { write((FileLaneProtocol.tokenLine(transferId, token, pass, startBlock) + "\n").toByteArray()); flush() }
            tls.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(200)
            val ready = FileLaneProtocol.parseReady(readLineBounded(tls.inputStream, FileLaneProtocol.FIRST_LINE_BYTES)) ?: throw IOException("No ready line")
            if (!ready.first) throw IOException("The lane was refused: ${ready.second}")
            tls.soTimeout = 0
            return TcpLaneOut(tls, tls.outputStream)
        } catch (error: Throwable) {
            runCatching { raw.close() }
            throw error
        }
    }

    companion object {
        private const val TAG = "DeskLink/FileLane"

        /** The LAN listener's port; 0 while there is none (set by the provider). */
        @Volatile var listenPort: Int = 0

        private class Issued(val transferId: String, val deviceId: String, val expiresAt: Long)

        private val tokens = ConcurrentHashMap<String, Issued>()
        private val openByPeer = ConcurrentHashMap<String, Int>()

        private fun open(peer: String) = openByPeer[peer] ?: 0

        /** The peer's certificate is the one stored at pairing, and names the device it claims to be. */
        fun pinned(context: Context, socket: SSLSocket, deviceId: String): Boolean = runCatching {
            val seen = socket.session.peerCertificates[0]
            val stored = TrustedDevices.getDeviceCertificate(context, deviceId)
            seen == stored && SslHelper.getCommonNameFromCertificate(seen as java.security.cert.X509Certificate) == deviceId
        }.getOrDefault(false)

        private var appContext: Context? = null

        /** The paired computers' lane hosts, by device id, for [accept]: each transfer layer registers itself. */
        private val hosts = ConcurrentHashMap<String, Host>()
        fun register(context: Context, deviceId: String, host: Host) { appContext = context.applicationContext; hosts[deviceId] = host }
        fun unregister(deviceId: String, host: Host) { hosts.remove(deviceId, host) }

        /** A lane connection reached the LAN listener: [first] is its plaintext first line. Blocking; runs on its own thread. */
        fun accept(context: Context, raw: Socket, firstLine: String) {
            appContext = context.applicationContext
            val deadline = System.currentTimeMillis() + FileLaneProtocol.CONNECT_MS
            var counted: String? = null
            try {
                val first = FileLaneProtocol.parseFirst(firstLine) ?: return raw.closeQuietly()
                if (first.targetDeviceId != DeviceHelper.getDeviceId(context)) return raw.closeQuietly()
                // Paired devices only: no QR admission, no unpaired lane.
                if (!TrustedDevices.isTrustedDevice(context, first.deviceId) || !TrustedDevices.isCertificateStored(context, first.deviceId)) return raw.closeQuietly()
                val host = hosts[first.deviceId] ?: return raw.closeQuietly()
                raw.tcpNoDelay = true
                raw.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(200)
                val tls = SslHelper.convertToSslSocket(context, raw, first.deviceId, true, true)
                tls.startHandshake()
                if (!pinned(context, tls, first.deviceId)) return raw.closeQuietly()
                val line = readLineBounded(tls.inputStream, FileLaneProtocol.FIRST_LINE_BYTES)
                val token = FileLaneProtocol.parseToken(line)
                val verdict = if (token == null) FileLaneProtocol.REASON_TOKEN else judge(token, first.deviceId, host)
                if (verdict != null) {
                    tls.outputStream.apply { write((FileLaneProtocol.readyLine(false, verdict) + "\n").toByteArray()); flush() }
                    return tls.closeQuietly()
                }
                if (open(first.deviceId) >= FileLaneProtocol.LANES_PER_PEER) {
                    tls.outputStream.apply { write((FileLaneProtocol.readyLine(false, FileLaneProtocol.REASON_BUSY) + "\n").toByteArray()); flush() }
                    return tls.closeQuietly()
                }
                counted = first.deviceId
                openByPeer.merge(first.deviceId, 1, Int::plus)
                tls.outputStream.apply { write((FileLaneProtocol.readyLine(true) + "\n").toByteArray()); flush() }
                host.laneAccepted(token!!.transferId)
                tls.soTimeout = FileLaneProtocol.SILENT_MS
                read(tls.inputStream, host, token.transferId)
            } catch (error: Throwable) {
                Log.i(TAG, "A lane ended (${error.javaClass.simpleName})")
            } finally {
                counted?.let { openByPeer.merge(it, -1, Int::plus) }
                raw.closeQuietly()
            }
        }

        /** Null when the token is good (and spent); else the refusal's reason. */
        internal fun judge(token: FileLaneProtocol.Token, deviceId: String, host: Host, now: Long = System.currentTimeMillis()): String? {
            val issued = tokens.remove(token.token) ?: return FileLaneProtocol.REASON_TOKEN // single use: gone either way
            if (issued.expiresAt < now || issued.deviceId != deviceId || issued.transferId != token.transferId) return FileLaneProtocol.REASON_TOKEN
            val next = host.nextBlock(token.transferId) ?: return FileLaneProtocol.REASON_TRANSFER
            if (token.startBlock != next) return FileLaneProtocol.REASON_RANGE
            // Null = the receiver takes any pass (just accepted): the stream's pass becomes its own.
            host.expectedPass(token.transferId)?.let { if (it != token.pass) return FileLaneProtocol.REASON_RANGE }
            return null
        }

        /** Frames, back to back, until the lane ends; each goes to the file thread through the host. */
        private fun read(input: InputStream, host: Host, transferId: String) {
            val header = ByteArray(FileV2.FRAME_HEADER_BYTES)
            try {
                while (true) {
                    readFully(input, header, FileV2.FRAME_HEADER_BYTES)
                    val total = FileLaneProtocol.frameLength(header) ?: throw IOException("Not a frame")
                    val frame = header.copyOf(total)
                    readFully(input, frame, total - FileV2.FRAME_HEADER_BYTES, FileV2.FRAME_HEADER_BYTES)
                    // The frame must belong to this transfer: anything else ends the lane (the transfer carries on over WebRTC).
                    if (frameTransfer(frame) != transferId) throw IOException("A frame of another transfer")
                    host.onFrame(frame)
                }
            } finally {
                host.laneClosed(transferId)
            }
        }

        internal fun frameTransfer(frame: ByteArray): String {
            val b = java.nio.ByteBuffer.wrap(frame, 8, 16)
            return java.util.UUID(b.long, b.long).toString()
        }

        private fun readFully(input: InputStream, into: ByteArray, count: Int, offset: Int = 0) {
            var read = 0
            while (read < count) {
                val n = input.read(into, offset + read, count - read)
                if (n < 0) throw IOException("The lane closed")
                read += n
            }
        }

        private fun Socket.closeQuietly() { runCatching { close() } }
    }
}
