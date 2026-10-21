/*
 * SPDX-FileCopyrightText: 2015 Vineet Garg <grg.vineet@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.desklink.mobile.helpers.security

import android.annotation.SuppressLint
import android.content.Context
import android.preference.PreferenceManager
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.desklink.mobile.helpers.DeviceHelper.getDeviceId
import org.desklink.mobile.helpers.RandomHelper
import org.desklink.mobile.helpers.security.RsaHelper.getPrivateKey
import org.desklink.mobile.helpers.security.RsaHelper.getPublicKey
import org.desklink.mobile.helpers.TrustedDevices
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Formatter
import java.util.Locale
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object SslHelper {
    lateinit var certificate: Certificate //my device's certificate
    private val factory: CertificateFactory = CertificateFactory.getInstance("X.509")

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustAllCerts: Array<TrustManager> = arrayOf(object : X509TrustManager {
        private val issuers = emptyArray<X509Certificate>()
        override fun getAcceptedIssuers(): Array<X509Certificate> = issuers
        override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) = Unit
        override fun checkServerTrusted(certs: Array<X509Certificate?>?, authType: String?) = Unit
    })

    fun initialiseCertificate(context: Context) {
        val privateKey: PrivateKey = getPrivateKey(context)
        val publicKey: PublicKey = getPublicKey(context)

        Log.i(LOG_TAG, "Key algorithm: " + publicKey.algorithm)

        val deviceId = getDeviceId(context)

        var needsToGenerateCertificate = false
        // Set only when this device's *identity* changed, which is the one case
        // where the peers it trusts no longer know it and its trust store is
        // meaningless. Certificate renewal on its own is not that case.
        var identityChanged = false
        val settings = PreferenceManager.getDefaultSharedPreferences(context)

        if (settings.contains("certificate")) {
            val currDate = Date()
            try {
                val certificateBytes = Base64.decode(settings.getString("certificate", ""), 0)
                val cert = parseCertificate(certificateBytes) as X509Certificate

                val certDeviceId = getCommonNameFromCertificate(cert)
                if (certDeviceId != deviceId) {
                    Log.e(LOG_TAG,"The certificate stored is from a different device id! (found: $certDeviceId expected:$deviceId)")
                    needsToGenerateCertificate = true
                    identityChanged = true
                } else if (cert.notAfter.time < currDate.time) {
                    Log.e(LOG_TAG, "The certificate expired: " + cert.notAfter)
                    needsToGenerateCertificate = true
                } else if (cert.notBefore.time > currDate.time) {
                    Log.e(LOG_TAG, "The certificate is not effective yet: " + cert.notBefore)
                    needsToGenerateCertificate = true
                } else {
                    certificate = cert
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Exception reading own certificate", e)
                needsToGenerateCertificate = true
            }
        } else {
            needsToGenerateCertificate = true
            // No stored certificate at all, yet a populated trust store. The
            // two live in different SharedPreferences files, so a restore or
            // partial data loss can keep one and drop the other. The device ID
            // is minted fresh alongside the certificate, so those trusted peers
            // know this device under an identity it no longer has — the same
            // stale-trust case as a changed common name, one branch over.
            identityChanged = TrustedDevices.getAllTrustedDevices(context).isNotEmpty()
        }

        if (needsToGenerateCertificate) {
            // Regenerating this device's own certificate does not invalidate the
            // certificates of the devices it trusts, and those records are the
            // only way a paired peer is recognised. Wiping them here silently
            // unpaired every device the moment this certificate lapsed — with no
            // prompt and no way to tell what had happened.
            //
            // The peer will refuse the new certificate under its own pinning
            // rules and prompt to pair again, which is the correct place for the
            // user to make that decision.
            //
            // A changed device ID is different: the peers in the trust store
            // know this device under the old ID and will never recognise it
            // again, so those records are stale rather than merely re-keyed.
            // Leaving them would show every one of them as paired forever with
            // no re-pair prompt ever raised.
            if (identityChanged) {
                Log.i(LOG_TAG, "Device identity changed; clearing the stale trust store")
                TrustedDevices.removeAllTrustedDevices(context)
            } else {
                Log.i(LOG_TAG, "Generating a certificate; existing pairings are preserved")
            }
            //Fix for https://issuetracker.google.com/issues/37095309
            val initialLocale = Locale.getDefault()
            setLocale(Locale.ENGLISH, context)

            val nameBuilder = X500NameBuilder(BCStyle.INSTANCE)
            // Only the common name is a compatibility-bound value: both
            // applications validate the certificate against the device ID and
            // read nothing else from the subject.
            //
            // The organisation was previously "KDE" on every newly generated
            // certificate. That is not preserved legacy material — it is minted
            // fresh on first run — and docs/BRANDING.md is explicit that
            // DeskLink is not presented as an official KDE product.
            nameBuilder.addRDN(BCStyle.CN, deviceId)
            nameBuilder.addRDN(BCStyle.OU, "DeskLink Mobile")
            nameBuilder.addRDN(BCStyle.O, "DeskLink")
            val localDate = LocalDate.now()
            val notBefore = localDate.minusYears(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val notAfter = localDate.plusYears(10).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val certificateBuilder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
                nameBuilder.build(),
                BigInteger.ONE,
                Date.from(notBefore),
                Date.from(notAfter),
                nameBuilder.build(),
                publicKey
            )
            val keyAlgorithm = privateKey.algorithm
            val signatureAlgorithm = if ("RSA" == keyAlgorithm) "SHA512withRSA" else "SHA512withECDSA"
            val contentSigner = JcaContentSignerBuilder(signatureAlgorithm).build(privateKey)
            val certificateBytes = certificateBuilder.build(contentSigner).encoded
            certificate = parseCertificate(certificateBytes)

            settings.edit {
                putString("certificate", Base64.encodeToString(certificateBytes, 0))
            }

            setLocale(initialLocale, context)
        }
    }

    private fun setLocale(locale: Locale, context: Context) {
        Locale.setDefault(locale)
        val resources = context.resources
        val config = resources.configuration
        config.locale = locale
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun getSslContextForDevice(context: Context, deviceId: String, isDeviceTrusted: Boolean): SSLContext {
        // TODO: This method is called for each payload that is sent. Cache the result.
        val privateKey = getPrivateKey(context)

        // Setup keystore
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setKeyEntry("key", privateKey, "".toCharArray(), arrayOf(certificate))

        // Add device certificate if device trusted
        if (isDeviceTrusted) {
            val remoteDeviceCertificate = TrustedDevices.getDeviceCertificate(context, deviceId)
            keyStore.setCertificateEntry(deviceId, remoteDeviceCertificate)
        }

        // Setup key manager factory
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, "".toCharArray())

        // Setup default trust manager
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStore)

        // Setup custom trust manager if device not trusted
        val tlsContext = SSLContext.getInstance("TLSv1.2") // Use TLS up to 1.2, since 1.3 seems to cause issues in some (older?) devices
        if (isDeviceTrusted) {
            tlsContext.init(keyManagerFactory.keyManagers, trustManagerFactory.trustManagers, RandomHelper.secureRandom)
        } else {
            tlsContext.init(keyManagerFactory.keyManagers, trustAllCerts, RandomHelper.secureRandom)
        }
        return tlsContext
    }

    private fun configureSslSocket(socket: SSLSocket, isDeviceTrusted: Boolean, isClient: Boolean) {
        socket.setSoTimeout(10000)
        if (isClient) {
            socket.useClientMode = true
        } else {
            socket.useClientMode = false
            if (isDeviceTrusted) {
                socket.needClientAuth = true
            } else {
                socket.wantClientAuth = true
            }
        }
    }

    @JvmStatic
    @Throws(java.security.cert.CertificateException::class)
    fun convertToSslSocket(context: Context, socket: Socket, deviceId: String, isDeviceTrusted: Boolean, clientMode: Boolean): SSLSocket {
        val sslSocketFactory = getSslContextForDevice(context, deviceId, isDeviceTrusted).socketFactory
        val sslSocket = sslSocketFactory.createSocket(socket, socket.inetAddress.hostAddress, socket.port, true) as SSLSocket
        configureSslSocket(sslSocket, isDeviceTrusted, clientMode)
        return sslSocket
    }

    fun getCertificateHash(certificate: Certificate): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        val formatter = Formatter()
        for (b in hash) {
            formatter.format("%02x:", b)
        }
        return formatter.toString()
    }

    fun parseCertificate(certificateBytes: ByteArray): Certificate {
        return factory.generateCertificate(ByteArrayInputStream(certificateBytes))
    }

    fun getCommonNameFromCertificate(cert: X509Certificate): String {
        val principal = cert.getSubjectX500Principal()
        val x500name = X500Name(principal.name)
        val rdn = x500name.getRDNs(BCStyle.CN).first()
        return IETFUtils.valueToString(rdn.getFirst().value)
    }

    private const val LOG_TAG = "DeskLink/SslHelper"
}
