/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.backends.lan

import android.app.Application
import android.util.Base64
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.desklink.mobile.helpers.readLineBounded
import org.desklink.mobile.helpers.DeviceHelper
import org.desklink.mobile.helpers.LineTooLongException
import org.desklink.mobile.helpers.TrustedDevices
import org.desklink.mobile.helpers.security.RsaHelper
import org.desklink.mobile.helpers.security.SslHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** The pinned TLS link on a real loopback socket: only the stored certificate gets in, and a line is bounded by who sent it. */
@RunWith(RobolectricTestRunner::class)
class PinnedTlsSocketTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val peerId = "aaaaaaaa_bbbb_cccc_dddd_eeeeeeeeeeee"
    private val pool = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    private class Identity(val key: PrivateKey, val cert: X509Certificate)

    private fun identity(deviceId: String): Identity {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val name = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, deviceId).build()
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(now - 86_400_000L), Date(now + 86_400_000L * 365), name, pair.public)
        val holder = builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private))
        return Identity(pair.private, java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(holder.encoded.inputStream()) as X509Certificate)
    }

    @Before
    fun phone() {
        RsaHelper.initialiseRsaKeys(app)
        DeviceHelper.initializeDeviceId(app)
        SslHelper.initialiseCertificate(app)
    }

    /** This phone pairs with [stored]: the certificate it will accept from [peerId]. */
    private fun pair(stored: Identity) {
        TrustedDevices.addTrustedDevice(app, peerId)
        app.getSharedPreferences(peerId, android.content.Context.MODE_PRIVATE).edit(commit = true) {
            putString("certificate", Base64.encodeToString(stored.cert.encoded, 0))
        }
    }

    /** The peer dials as a client with [who]'s key and certificate; the phone accepts in server mode and reads one line of at most [max]. */
    private fun connect(who: Identity, trusted: Boolean, max: Int, send: ByteArray): Pair<String?, Throwable?> {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val phone = pool.submit(Callable<Pair<String?, Throwable?>> {
                try {
                    val raw = server.accept()
                    val tls = SslHelper.convertToSslSocket(app, raw, peerId, trusted, clientMode = false)
                    tls.startHandshake()
                    tls.soTimeout = 20_000
                    readLineBounded(tls.inputStream, max) to null
                } catch (e: Throwable) {
                    null to e
                }
            })
            val peer = pool.submit {
                runCatching {
                    val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                        load(null, null)
                        setKeyEntry("key", who.key, "".toCharArray(), arrayOf(who.cert))
                    }
                    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "".toCharArray()) }
                    val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>?, type: String?) = Unit
                        override fun checkServerTrusted(chain: Array<X509Certificate>?, type: String?) = Unit
                        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
                    })
                    val context = SSLContext.getInstance("TLSv1.2").apply { init(kmf.keyManagers, trustAll, null) }
                    val socket = context.socketFactory.createSocket(Socket(InetAddress.getLoopbackAddress(), server.localPort), "localhost", server.localPort, true) as SSLSocket
                    socket.startHandshake()
                    socket.outputStream.write(send)
                    socket.outputStream.flush()
                    socket.inputStream.read() // held open until the phone has judged the line
                }
            }
            val result = phone.get(60, TimeUnit.SECONDS)
            peer.cancel(true)
            return result
        }
    }

    private fun line(bytes: Int) = ByteArray(bytes) { 'a'.code.toByte() } + '\n'.code.toByte()

    @Test
    fun theStoredCertificateGetsInAndItsLinesAreRead() {
        val peer = identity(peerId)
        pair(peer)
        val (read, error) = connect(peer, trusted = true, max = UnpairedLimits.MAX_PAIRED_LINE, send = "{\"type\":\"x\"}\n".toByteArray())
        assertEquals(null, error)
        assertEquals("{\"type\":\"x\"}\n", read)
    }

    @Test
    fun aDifferentCertificateClaimingTheSameIdIsRefusedInTheHandshake() {
        pair(identity(peerId))
        val impostor = identity(peerId)
        val (read, error) = connect(impostor, trusted = true, max = UnpairedLimits.MAX_PAIRED_LINE, send = "{}\n".toByteArray())
        assertEquals("nothing was read", null, read)
        assertTrue("refused by TLS, not by a later check: $error", error is javax.net.ssl.SSLException)
    }

    @Test
    fun aLineBeyondThePairedLimitIsRefusedAndSoIsOneBeyondTheUnpairedLimitFromAStranger() {
        val peer = identity(peerId)
        pair(peer)
        val paired = connect(peer, trusted = true, max = UnpairedLimits.MAX_PAIRED_LINE, send = ByteArray(UnpairedLimits.MAX_PAIRED_LINE + 1) { 'a'.code.toByte() })
        assertTrue("an oversize line from a paired peer: ${paired.second}", paired.second is LineTooLongException)
        // A line the paired peer may send is too much for a stranger, who is limited to identity and pairing.
        val between = UnpairedLimits.MAX_UNPAIRED_LINE + 1000
        assertTrue(between < UnpairedLimits.MAX_PAIRED_LINE)
        assertEquals(null, connect(peer, trusted = true, max = UnpairedLimits.MAX_PAIRED_LINE, send = line(between)).second)
        val stranger = connect(identity("stranger"), trusted = false, max = UnpairedLimits.MAX_UNPAIRED_LINE, send = line(between))
        assertTrue("a stranger's long line: ${stranger.second}", stranger.second is LineTooLongException)
        assertNotNull("a stranger's short line is still read", connect(identity("stranger"), trusted = false, max = UnpairedLimits.MAX_UNPAIRED_LINE, send = line(100)).first)
    }

    @Test
    fun theLinkReadsWithTheLimitOfWhoSentIt() {
        assertEquals(UnpairedLimits.MAX_PAIRED_LINE, LanLink.MAX_PACKET_SIZE)
        assertTrue(UnpairedLimits.MAX_UNPAIRED_LINE < UnpairedLimits.MAX_PAIRED_LINE)
    }
}
