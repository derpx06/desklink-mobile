/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import android.app.Application
import android.util.Base64
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.helpers.DeviceHelper
import org.desklink.mobile.helpers.TrustedDevices
import org.desklink.mobile.helpers.readLineBounded
import org.desklink.mobile.helpers.security.RsaHelper
import org.desklink.mobile.helpers.security.SslHelper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** W18, lane A: the wire lines against the shared vectors, the token rules, the offer rules, and a real pinned TLS lane on loopback. */
@RunWith(RobolectricTestRunner::class)
class FileLaneTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val vectors = JSONObject(javaClass.getResourceAsStream("/shared/filelane_bodies.json")!!.bufferedReader().readText())

    @Test
    fun theLinesAreExactlyTheSharedVectors() {
        val first = vectors.getJSONObject("first_line")
        assertEquals(first.getString("line"), FileLaneProtocol.firstLine(1730000000000, first.getString("deviceId"), first.getString("targetDeviceId")))
        val parsed = FileLaneProtocol.parseFirst(first.getString("line"))!!
        assertEquals(first.getString("deviceId"), parsed.deviceId); assertEquals(first.getString("targetDeviceId"), parsed.targetDeviceId)
        val token = vectors.getJSONObject("token_line")
        assertEquals(token.getString("line"), FileLaneProtocol.tokenLine(token.getString("transferId"), token.getString("token"), token.getInt("pass"), token.getInt("startBlock")))
        val t = FileLaneProtocol.parseToken(token.getString("line"))!!
        assertEquals(token.getInt("startBlock"), t.startBlock); assertEquals(token.getString("transferId"), t.transferId)
        assertEquals(vectors.getString("ready_ok"), FileLaneProtocol.readyLine(true))
        assertEquals(true to null, FileLaneProtocol.parseReady(vectors.getString("ready_ok")))
        val refusals = vectors.getJSONArray("ready_refusals")
        for (i in 0 until refusals.length()) {
            val r = refusals.getJSONObject(i)
            assertEquals(r.getString("line"), FileLaneProtocol.readyLine(false, r.getString("reason")))
            assertEquals(false to r.getString("reason"), FileLaneProtocol.parseReady(r.getString("line")))
        }
    }

    @Test
    fun theControlFragmentsRoundTripAndMalformedTokensAreRefused() {
        val accept = FileLaneSpec.fromJson(vectors.getJSONObject("accept_lane").getJSONObject("lane"))
        assertEquals(1717, accept.port); assertEquals(60_000L, accept.ttlMs); accept.validate()
        assertEquals(1, FileLaneSpec.fromJson(vectors.getJSONObject("offer_lane").getJSONObject("lane")).v)
        val actions = vectors.getJSONArray("lane_actions")
        for (i in 0 until actions.length()) {
            val spec = FileLaneSpec.fromJson(actions.getJSONObject(i).getJSONObject("lane"))
            spec.validate()
            assertEquals(spec, FileLaneSpec.fromJson(spec.toJson()))
        }
        val refused = vectors.getJSONArray("refused_tokens")
        for (i in 0 until refused.length()) {
            assertFalse(refused.getString(i), FileLaneProtocol.validToken(refused.getString(i)))
            assertNull(FileLaneProtocol.parseToken(FileLaneProtocol.tokenLine(UUID.randomUUID().toString(), refused.getString(i), 0, 0)))
        }
        val limits = vectors.getJSONObject("limits")
        assertEquals(limits.getLong("minFileBytes"), FileLaneProtocol.MIN_FILE_BYTES)
        assertEquals(limits.getInt("connectMs"), FileLaneProtocol.CONNECT_MS)
        assertEquals(limits.getLong("tokenTtlMs"), FileLaneProtocol.TOKEN_TTL_MS)
        assertEquals(limits.getLong("stallMs"), FileLaneProtocol.STALL_MS)
        assertEquals(limits.getInt("firstLineBytes"), FileLaneProtocol.FIRST_LINE_BYTES)
        assertEquals(limits.getInt("lanesPerPeer"), FileLaneProtocol.LANES_PER_PEER)
        assertEquals("a fresh token is 32 lowercase hex", true, FileLaneProtocol.validToken(FileLaneProtocol.newToken()))
    }

    private fun subnet(ip: String, prefix: Int) = InetAddress.getByName(ip) to prefix

    @Test
    fun theLaneAddressComesOnlyFromAPrivateAddressOnASubnetThePhoneIsOn() {
        val locals = { listOf(subnet("192.168.1.3", 24), subnet("fd12:3456::5", 64)) }
        assertEquals("192.168.1.9", FileLaneProtocol.laneAddress("192.168.1.9", locals)?.hostAddress)
        assertEquals("fd12:3456:0:0:0:0:0:9", FileLaneProtocol.laneAddress("fd12:3456::9", locals)?.hostAddress)
        assertNull("another subnet", FileLaneProtocol.laneAddress("192.168.2.9", locals))
        assertNull("public", FileLaneProtocol.laneAddress("8.8.8.8", { listOf(subnet("8.8.8.1", 24)) }))
        assertNull("link-local v6", FileLaneProtocol.laneAddress("fe80::1", { listOf(subnet("fe80::2", 64)) }))
        assertNull("link-local v4", FileLaneProtocol.laneAddress("169.254.1.1", { listOf(subnet("169.254.1.2", 16)) }))
        assertNull("a name is never looked up", FileLaneProtocol.laneAddress("desk.local", locals))
        assertNull(FileLaneProtocol.laneAddress(null, locals)); assertNull(FileLaneProtocol.laneAddress("", locals))
    }

    private class FakeHost(
        var lan: Boolean = true, var address: InetAddress? = InetAddress.getLoopbackAddress(), var capable: Boolean = true,
        var next: Int? = 0, var pass: Int? = null,
    ) : RealFileLanes.Host {
        val frames = CopyOnWriteArrayList<ByteArray>()
        @Volatile var closed = false
        @Volatile var accepted = false
        override fun onLan() = lan
        override fun peerLanAddress() = address
        override fun peerHasLane() = capable
        override fun nextBlock(transferId: String) = next
        override fun expectedPass(transferId: String) = pass
        override fun onFrame(frame: ByteArray, connection: Long) { frames += frame }
        override fun laneClosed(transferId: String, connection: Long) { closed = true }
        override fun laneAccepted(transferId: String, pass: Int, startBlock: Int, connection: Long) { accepted = true }
    }

    @Test
    fun theLaneIsOfferedOnlyForBigFilesOnTheLanToAPeerThatHasIt() {
        val host = FakeHost()
        val lanes = RealFileLanes(app, "desk", host)
        val big = FileLaneProtocol.MIN_FILE_BYTES
        assertTrue(lanes.canOffer(big))
        assertFalse("a file under 8 MiB", lanes.canOffer(big - 1))
        host.lan = false; assertFalse("a relay or a direct internet path", lanes.canOffer(big)); host.lan = true
        host.capable = false; assertFalse("the peer lacks the capability", lanes.canOffer(big)); host.capable = true
        host.address = null; assertFalse("no LAN address for the peer", lanes.canOffer(big))
    }

    @Test
    fun anAcceptCarriesALaneOnlyWhenTheListenerIsUpOnTheLanAndTheTokenIsOneTimeAndBoundToTheTransfer() {
        val host = FakeHost()
        val lanes = RealFileLanes(app, "desk-1", host)
        RealFileLanes.listenPort = 0
        assertNull("no listener, no lane", lanes.issue("t"))
        RealFileLanes.listenPort = 1717
        try {
            host.lan = false; assertNull("not on the LAN", lanes.issue("t")); host.lan = true
            val spec = lanes.issue("transfer-1")!!
            assertEquals(1717, spec.port); assertEquals(60_000L, spec.ttlMs); assertTrue(FileLaneProtocol.validToken(spec.token))
            val token = FileLaneProtocol.Token("transfer-1", spec.token!!, 0, 0)
            assertEquals("another computer", FileLaneProtocol.REASON_TOKEN, RealFileLanes.judge(token, "desk-2", host))
            val again = lanes.issue("transfer-1")!!
            val good = FileLaneProtocol.Token("transfer-1", again.token!!, 0, 0)
            assertNull(RealFileLanes.judge(good, "desk-1", host))
            assertEquals("used once", FileLaneProtocol.REASON_TOKEN, RealFileLanes.judge(good, "desk-1", host))
            val other = lanes.issue("transfer-1")!!
            assertEquals("another transfer", FileLaneProtocol.REASON_TOKEN, RealFileLanes.judge(FileLaneProtocol.Token("transfer-2", other.token!!, 0, 0), "desk-1", host))
            val late = lanes.issue("transfer-1")!!
            assertEquals("expired", FileLaneProtocol.REASON_TOKEN, RealFileLanes.judge(FileLaneProtocol.Token("transfer-1", late.token!!, 0, 0), "desk-1", host, now = System.currentTimeMillis() + 61_000))
            host.next = null
            val gone = lanes.issue("transfer-1")!!
            assertEquals("a transfer that is not open", FileLaneProtocol.REASON_TRANSFER, RealFileLanes.judge(FileLaneProtocol.Token("transfer-1", gone.token!!, 0, 0), "desk-1", host))
            host.next = 3
            val wrongStart = lanes.issue("transfer-1")!!
            assertEquals("past the verified prefix", FileLaneProtocol.REASON_RANGE, RealFileLanes.judge(FileLaneProtocol.Token("transfer-1", wrongStart.token!!, 0, 4), "desk-1", host))
            host.pass = 1
            val wrongPass = lanes.issue("transfer-1")!!
            assertEquals("not the transfer's pass", FileLaneProtocol.REASON_RANGE, RealFileLanes.judge(FileLaneProtocol.Token("transfer-1", wrongPass.token!!, 0, 3), "desk-1", host))
            val rightPass = lanes.issue("transfer-1")!!
            assertNull(RealFileLanes.judge(FileLaneProtocol.Token("transfer-1", rightPass.token!!, 1, 3), "desk-1", host))
        } finally {
            RealFileLanes.listenPort = 0
        }
    }

    @Test
    fun aFrameIsReadByItsOwnLengthAndOnlyFramesAreTaken() {
        val id = UUID.randomUUID()
        val data = ByteArray(1000) { it.toByte() }
        val plain = FileFrame.encode(id, 0, 0, 0, data, 0, 1000, null)
        val last = FileFrame.encode(id, 0, 0, 0, data, 0, 1000, ByteArray(32))
        assertEquals(plain.size, FileLaneProtocol.frameLength(plain.copyOf(40)))
        assertEquals(last.size, FileLaneProtocol.frameLength(last.copyOf(40)))
        assertNull("not a frame", FileLaneProtocol.frameLength(ByteArray(40)))
        assertEquals(id.toString(), RealFileLanes.frameTransfer(plain))
        val huge = plain.copyOf(40).also { java.nio.ByteBuffer.wrap(it).putInt(36, FileV2.FRAME_BYTES_PROVEN + 1) }
        assertNull("longer than a frame may be", FileLaneProtocol.frameLength(huge))
    }

    // ------------------------------------------------------------------ two real parties over real TLS

    /** A second Android-side identity: its own key and certificate, named [deviceId] (what a paired computer is to the phone). */
    private class Peer(val deviceId: String) {
        val keys = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate: java.security.cert.X509Certificate = run {
            val name = org.bouncycastle.asn1.x500.X500NameBuilder(org.bouncycastle.asn1.x500.style.BCStyle.INSTANCE)
                .addRDN(org.bouncycastle.asn1.x500.style.BCStyle.CN, deviceId).build()
            val now = System.currentTimeMillis()
            val holder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                name, java.math.BigInteger.valueOf(now), java.util.Date(now - 86_400_000), java.util.Date(now + 86_400_000L * 365), name, keys.public,
            ).build(org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
            java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(java.io.ByteArrayInputStream(holder.encoded)) as java.security.cert.X509Certificate
        }

        /** TLS over [raw] as this identity; [server] = the dialler's role (the listener is the TLS client). */
        fun tls(raw: Socket, server: Boolean): javax.net.ssl.SSLSocket {
            val store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply {
                load(null, null); setKeyEntry("key", keys.private, "".toCharArray(), arrayOf(certificate))
            }
            val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "".toCharArray()) }
            val any = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
            })
            val context = javax.net.ssl.SSLContext.getInstance("TLSv1.2").apply { init(kmf.keyManagers, any, java.security.SecureRandom()) }
            val socket = context.socketFactory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as javax.net.ssl.SSLSocket
            socket.useClientMode = !server
            if (server) socket.needClientAuth = true
            return socket
        }
    }

    private val pool = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
    private val peer = Peer("desk-1")

    private fun pairWith(peer: Peer) {
        RsaHelper.initialiseRsaKeys(app); DeviceHelper.initializeDeviceId(app); SslHelper.initialiseCertificate(app)
        TrustedDevices.addTrustedDevice(app, peer.deviceId)
        app.getSharedPreferences(peer.deviceId, android.content.Context.MODE_PRIVATE).edit(commit = true) {
            putString("certificate", Base64.encodeToString(peer.certificate.encoded, 0))
        }
    }

    /** The phone's LAN listener, reduced to what the lane touches: accept, read the first line, hand a lane to [RealFileLanes.accept]. */
    private fun listen(server: ServerSocket, times: Int = 1) = pool.submit {
        repeat(times) {
            val raw = server.accept()
            raw.soTimeout = 5000
            val line = runCatching { readLineBounded(raw.getInputStream(), 64 * 1024) }.getOrNull()
            if (line != null) RealFileLanes.accept(app, raw, line) else runCatching { raw.close() }
        }
    }

    private fun firstLineFor(from: String, target: String, padding: Int = 0) =
        FileLaneProtocol.firstLine(System.currentTimeMillis(), from, target).let { if (padding == 0) it else it.replace("}}", ",\"pad\":\"${"x".repeat(padding)}\"}}") }

    /** The computer's side of a lane: the plaintext first line, TLS as [as], the token line; the answer line, or null when it never got that far. */
    private fun dialAs(
        port: Int, as_: Peer, claimed: String = as_.deviceId, target: String = DeviceHelper.getDeviceId(app), token: String, transfer: String,
        padding: Int = 0, afterReady: ((Socket, javax.net.ssl.SSLSocket) -> Unit)? = null,
    ): String? {
        val raw = Socket()
        return try {
            raw.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 3000)
            raw.soTimeout = 15_000
            raw.getOutputStream().apply { write((firstLineFor(claimed, target, padding) + "\n").toByteArray()); flush() }
            val tls = as_.tls(raw, server = true)
            tls.startHandshake()
            tls.outputStream.apply { write((FileLaneProtocol.tokenLine(transfer, token, 0, 0) + "\n").toByteArray()); flush() }
            val answer = readLineBounded(tls.inputStream, 4096)
            afterReady?.invoke(raw, tls)
            answer.trim()
        } catch (_: Exception) {
            null
        } finally {
            if (afterReady == null) runCatching { raw.close() }
        }
    }

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < end) Thread.sleep(10)
        return condition()
    }

    @Test
    fun aPairedComputerOpensALaneAndCarriesFramesAnImpostorUnderTheSameIdIsRefusedAndSpendsNothing() {
        pairWith(peer)
        val host = FakeHost(next = 0, pass = null)
        RealFileLanes.register(app, peer.deviceId, host)
        val lanes = RealFileLanes(app, peer.deviceId, host)
        val id = UUID.randomUUID().toString()
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            RealFileLanes.listenPort = server.localPort
            try {
                val token = lanes.issue(id)!!.token!!
                // Another keypair and certificate that says it is the same computer: the pinned certificate is the only one that counts.
                val impostor = Peer(peer.deviceId)
                listen(server)
                assertNull("the impostor gets no answer", dialAs(server.localPort, impostor, token = token, transfer = id))
                assertFalse(host.accepted)
                // The token was not spent by the refused attempt: the real computer still opens the lane with it.
                listen(server)
                val frame = FileFrame.encode(UUID.fromString(id), 0, 0, 0, ByteArray(500) { 7 }, 0, 500, null)
                val answer = dialAs(server.localPort, peer, token = token, transfer = id) { _, tls -> tls.outputStream.apply { write(frame); flush() } }
                assertEquals(FileLaneProtocol.readyLine(true), answer)
                assertTrue(waitFor { host.frames.isNotEmpty() })
                assertTrue(host.frames.single().contentEquals(frame))
                assertTrue(host.accepted)
            } finally {
                RealFileLanes.listenPort = 0
            }
        }
        RealFileLanes.unregister(peer.deviceId, host)
    }

    @Test
    fun anUnpairedIdAWrongTargetAnOversizedLineAndASilentHandshakeAllEndWithoutALane() {
        pairWith(peer)
        val host = FakeHost(next = 0, pass = null)
        RealFileLanes.register(app, peer.deviceId, host)
        val lanes = RealFileLanes(app, peer.deviceId, host)
        val id = UUID.randomUUID().toString()
        ServerSocket(0, 10, InetAddress.getLoopbackAddress()).use { server ->
            RealFileLanes.listenPort = server.localPort
            try {
                val token = lanes.issue(id)!!.token!!
                val stranger = Peer("stranger-9")
                listen(server)
                assertNull("an id that was never paired", dialAs(server.localPort, stranger, token = token, transfer = id))
                listen(server)
                assertNull("addressed to another computer", dialAs(server.localPort, peer, target = "someone-else", token = token, transfer = id))
                listen(server)
                assertNull("a first line over 4 KiB", dialAs(server.localPort, peer, token = token, transfer = id, padding = 5000))
                // A paired id that connects and then says nothing: the handshake has 3 s, whatever the socket timeouts add up to.
                listen(server)
                val started = System.nanoTime()
                val silent = Socket()
                silent.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), 3000)
                silent.soTimeout = 15_000
                silent.getOutputStream().apply { write((firstLineFor(peer.deviceId, DeviceHelper.getDeviceId(app)) + "\n").toByteArray()); flush() }
                val ended = runCatching { silent.getInputStream().read() }.getOrDefault(-1)
                val seconds = (System.nanoTime() - started) / 1e9
                assertTrue("closed by the listener, not by this socket's own timeout", ended == -1 || ended >= 0)
                assertTrue("closed within the 3 s deadline (took $seconds s)", seconds < 6.5)
                silent.close()
                assertFalse(host.accepted)
                assertTrue("none of these spent the token", RealFileLanes.judge(FileLaneProtocol.Token(id, token, 0, 0), peer.deviceId, host) == null)
            } finally {
                RealFileLanes.listenPort = 0
            }
        }
        RealFileLanes.unregister(peer.deviceId, host)
    }

    @Test
    fun theDiallerOnlySendsItsTokenToTheComputerWhoseCertificateIsPinned() {
        pairWith(peer)
        val host = FakeHost()
        val lanes = RealFileLanes(app, peer.deviceId, host)
        val id = UUID.randomUUID().toString()
        val token = "00112233445566778899aabbccddeeff"
        fun listener(identity: Peer, answer: String?, sawToken: java.util.concurrent.atomic.AtomicBoolean): Pair<ServerSocket, java.util.concurrent.Future<*>> {
            val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
            return server to pool.submit {
                runCatching {
                    val raw = server.accept()
                    raw.soTimeout = 8000
                    readLineBounded(raw.getInputStream(), 4096) // the plaintext first line
                    val tls = identity.tls(raw, server = false)
                    tls.startHandshake()
                    val line = readLineBounded(tls.inputStream, 4096)
                    sawToken.set(FileLaneProtocol.parseToken(line) != null)
                    if (answer != null) tls.outputStream.apply { write((answer + "\n").toByteArray()); flush() }
                    Thread.sleep(300)
                    raw.close()
                }
            }
        }
        fun dial(port: Int): LaneOut? {
            val result = java.util.concurrent.CompletableFuture<LaneOut?>()
            lanes.dial(id, FileLaneSpec(v = 1, port = port, token = token, ttlMs = 60_000), 0, 0) { result.complete(it) }
            return result.get(20, TimeUnit.SECONDS)
        }
        // A computer that is not the paired one, whatever id it claims: the token never leaves the phone.
        val impostorSaw = java.util.concurrent.atomic.AtomicBoolean()
        val (impostorServer, impostorDone) = listener(Peer(peer.deviceId), FileLaneProtocol.readyLine(true), impostorSaw)
        impostorServer.use { assertNull(dial(it.localPort)); impostorDone.get(15, TimeUnit.SECONDS) }
        assertFalse("no token went to the impostor", impostorSaw.get())
        // The paired one, refusing: no lane.
        val refusedSaw = java.util.concurrent.atomic.AtomicBoolean()
        val (refusing, refusedDone) = listener(peer, FileLaneProtocol.readyLine(false, FileLaneProtocol.REASON_RANGE), refusedSaw)
        refusing.use { assertNull(dial(it.localPort)); refusedDone.get(15, TimeUnit.SECONDS) }
        assertTrue(refusedSaw.get())
        // The paired one, agreeing: a lane that carries bytes.
        val okSaw = java.util.concurrent.atomic.AtomicBoolean()
        val (agreeing, okDone) = listener(peer, FileLaneProtocol.readyLine(true), okSaw)
        agreeing.use {
            val out = dial(it.localPort)
            assertNotNull(out)
            assertTrue(okSaw.get())
            out!!.close()
            okDone.get(15, TimeUnit.SECONDS)
        }
    }

    // ------------------------------------------------------------------ the rules around the lane

    @Test
    fun aTransferHasOneLiveTokenExpiredOnesArePurgedAndAnEndedTransferVoidsItsToken() {
        val host = FakeHost()
        val lanes = RealFileLanes(app, "desk-9", host)
        RealFileLanes.listenPort = 1717
        try {
            val first = lanes.issue("t-1")!!.token!!
            val second = lanes.issue("t-1")!!.token!!
            assertEquals("the newest accept's token is the one", FileLaneProtocol.REASON_TOKEN, RealFileLanes.judge(FileLaneProtocol.Token("t-1", first, 0, 0), "desk-9", host))
            assertNull(RealFileLanes.judge(FileLaneProtocol.Token("t-1", second, 0, 0), "desk-9", host))
            val third = lanes.issue("t-2")!!.token!!
            lanes.forget("t-2")
            assertEquals("a transfer that ended has no token", FileLaneProtocol.REASON_TOKEN, RealFileLanes.judge(FileLaneProtocol.Token("t-2", third, 0, 0), "desk-9", host))
            val kept = RealFileLanes.pendingTokens()
            lanes.issue("t-3"); lanes.issue("t-3"); lanes.issue("t-3")
            assertEquals("three accepts of one transfer leave one token", kept + 1, RealFileLanes.pendingTokens())
            lanes.forget("t-3")
            assertEquals(kept, RealFileLanes.pendingTokens())
            val late = lanes.issue("t-4")!!.token!!
            host.lan = false // the path left the LAN before the token was used
            assertEquals(FileLaneProtocol.REASON_PATH, RealFileLanes.judge(FileLaneProtocol.Token("t-4", late, 0, 0), "desk-9", host))
            host.lan = true
        } finally {
            RealFileLanes.listenPort = 0
        }
    }

    @Test
    fun aTokenLineMayStartAtOrBelowTheVerifiedPrefixAndCarryTheTransfersPassOrTheNext() {
        val host = FakeHost(next = 4, pass = 5)
        val lanes = RealFileLanes(app, "desk-9", host)
        RealFileLanes.listenPort = 1717
        try {
            fun judge(start: Int, pass: Int) = RealFileLanes.judge(FileLaneProtocol.Token("t", lanes.issue("t")!!.token!!, pass, start), "desk-9", host)
            assertNull(judge(4, 5)); assertNull(judge(2, 5)); assertNull(judge(4, 6))
            assertEquals(FileLaneProtocol.REASON_RANGE, judge(5, 5))
            assertEquals(FileLaneProtocol.REASON_RANGE, judge(4, 7))
            assertEquals(FileLaneProtocol.REASON_RANGE, judge(4, 4))
        } finally {
            RealFileLanes.listenPort = 0
        }
    }

    @Test
    fun theHandshakeCapsAdmitAFewPerAddressAFewPerMinuteAndAFewInAll() {
        var now = 0L
        val limits = LaneAdmission({ now }, perIpOpen = 2, globalOpen = 3, perIpPerMinute = 4)
        assertTrue(limits.admit("10.0.0.1")); assertTrue(limits.admit("10.0.0.1"))
        assertFalse("two half-open per address", limits.admit("10.0.0.1"))
        assertTrue(limits.admit("10.0.0.2"))
        assertFalse("three half-open in all", limits.admit("10.0.0.3"))
        limits.release("10.0.0.1"); limits.release("10.0.0.1")
        assertTrue(limits.admit("10.0.0.1")); limits.release("10.0.0.1")
        assertTrue(limits.admit("10.0.0.1")); limits.release("10.0.0.1")
        assertFalse("four an minute per address", limits.admit("10.0.0.1"))
        now += 61_000
        assertTrue("a minute later", limits.admit("10.0.0.1"))
    }

    @Test
    fun aPeersLaneBackOffDoublesFromTenSecondsToFiveMinutesAndACleanLaneClearsIt() {
        val lanes = RealFileLanes(app, "desk-9", FakeHost())
        assertFalse(lanes.cooling(0))
        lanes.failed(1_000)
        assertTrue(lanes.cooling(1_000 + 9_999)); assertFalse(lanes.cooling(1_000 + 10_000))
        lanes.failed(100_000)
        assertTrue(lanes.cooling(100_000 + 19_999)); assertFalse(lanes.cooling(100_000 + 20_000))
        repeat(10) { lanes.failed(1_000_000) }
        assertTrue(lanes.cooling(1_000_000 + 299_999)); assertFalse("never longer than five minutes", lanes.cooling(1_000_000 + 300_000))
        lanes.clean()
        assertFalse(lanes.cooling(1_000_001))
    }
}
