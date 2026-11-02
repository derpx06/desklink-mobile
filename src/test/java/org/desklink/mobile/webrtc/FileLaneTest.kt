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
        override fun onFrame(frame: ByteArray) { frames += frame }
        override fun laneClosed(transferId: String) { closed = true }
        override fun laneAccepted(transferId: String) { accepted = true }
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
            assertEquals("not the next unverified block", FileLaneProtocol.REASON_RANGE, RealFileLanes.judge(FileLaneProtocol.Token("transfer-1", wrongStart.token!!, 0, 2), "desk-1", host))
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

    @Test
    fun aRealPinnedLaneCarriesFramesAndASpentTokenOpensNothing() {
        RsaHelper.initialiseRsaKeys(app); DeviceHelper.initializeDeviceId(app); SslHelper.initialiseCertificate(app)
        // This phone is paired with itself for the test: one identity on both ends, each pinning the other (which is itself).
        val me = DeviceHelper.getDeviceId(app)
        TrustedDevices.addTrustedDevice(app, me)
        app.getSharedPreferences(me, android.content.Context.MODE_PRIVATE).edit(commit = true) { putString("certificate", Base64.encodeToString(SslHelper.certificate.encoded, 0)) }
        val host = FakeHost(next = 0, pass = null)
        RealFileLanes.register(app, me, host)
        val lanes = RealFileLanes(app, me, host)
        val pool = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            RealFileLanes.listenPort = server.localPort
            pool.submit {
                repeat(2) {
                    val raw = server.accept()
                    val line = readLineBounded(raw.getInputStream(), FileLaneProtocol.FIRST_LINE_BYTES)
                    RealFileLanes.accept(app, raw, line)
                }
            }
            val id = UUID.randomUUID().toString()
            val spec = lanes.issue(id)!!
            val opened = java.util.concurrent.CompletableFuture<LaneOut?>()
            lanes.dial(id, spec, 0, 0) { opened.complete(it) }
            val out = opened.get(20, TimeUnit.SECONDS)
            assertNotNull("the pinned lane opened", out)
            val frame = FileFrame.encode(UUID.fromString(id), 0, 0, 0, ByteArray(500) { 7 }, 0, 500, null)
            out!!.write(frame)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (host.frames.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(1, host.frames.size)
            assertTrue(host.frames.single().contentEquals(frame))
            // The same token again: refused (single use).
            val second = java.util.concurrent.CompletableFuture<LaneOut?>()
            lanes.dial(id, spec, 0, 0) { second.complete(it) }
            assertNull(second.get(20, TimeUnit.SECONDS))
            out.close()
            val closedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!host.closed && System.nanoTime() < closedBy) Thread.sleep(10)
            assertTrue("the receiver learns the lane closed", host.closed)
        }
        RealFileLanes.listenPort = 0
        RealFileLanes.unregister(me, host)
    }
}
