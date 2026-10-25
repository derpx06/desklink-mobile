/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile

import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import org.desklink.mobile.helpers.DeviceHelper
import org.desklink.mobile.helpers.security.SslHelper
import java.net.InetAddress
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Phone side of QR pairing; the desktop side is `pairing_qr.rs`, which
 * documents the scheme. In short: the code carries the desktop's certificate
 * fingerprint and a one-time secret. The phone refuses the desktop unless its
 * certificate matches the fingerprint, then proves it scanned the code with an
 * HMAC over both ids and both fingerprints. Nothing to type or compare.
 */
object QrPairing {
    private const val TAG = "DeskLink/QrPairing"
    private const val MAC_LABEL = "desklink-qr-v1"
    private const val LINK_WAIT_MS = 10_000L

    data class Code(
        val desktopId: String,
        val fingerprint: ByteArray,
        val secret: ByteArray,
        val host: InetAddress?,
        /** QR v2 (`a`, `p`): every LAN address of the desk and its TCP port, dialled directly. */
        val addresses: List<InetAddress> = emptyList(),
        val port: Int? = null,
    )

    /** Where a scan has got to, for the scan screen (scanning → connecting → verifying → paired). */
    enum class Stage { CONNECTING, VERIFYING }

    /** The desktop a scan is pairing with; lets its link past the trusted-network gate. */
    @Volatile
    @JvmStatic
    var pendingDesktopId: String? = null
        private set

    /** Parses `desklink://pair?v=1&id=..&fp=..&s=..[&h=ip:port]`, or null if it isn't one. */
    @JvmStatic
    fun parse(text: String): Code? = runCatching {
        val uri = Uri.parse(text)
        if (uri.scheme != "desklink" || uri.host != "pair" || uri.getQueryParameter("v") != "1") return null
        val id = uri.getQueryParameter("id")?.takeIf(DeviceInfo::isValidDeviceId) ?: return null
        val fp = decode(uri.getQueryParameter("fp") ?: return null).takeIf { it.size == 32 } ?: return null
        val secret = decode(uri.getQueryParameter("s") ?: return null).takeIf { it.size == 32 } ?: return null
        // An IP literal on the local network only: never a DNS lookup, and a
        // hostile code must not make us announce ourselves to the internet.
        val host = uri.getQueryParameter("h")
            ?.substringBeforeLast(':')
            ?.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() || c == '.' } }
            ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
            ?.takeIf { org.desklink.mobile.helpers.isPrivateAddress(it) }
        Code(id, fp, secret, host, parseAddresses(uri.getQueryParameter("a")), parsePort(uri.getQueryParameter("p")))
    }.getOrNull()

    /**
     * `a`: comma-separated IPv4 addresses in the private LAN ranges (10/8, 172.16/12, 192.168/16),
     * at most 8. Parsed here octet by octet and built with getByAddress, so no text can ever reach
     * a DNS lookup; link-local, CGNAT, loopback and public addresses are dropped.
     */
    @JvmStatic
    fun parseAddresses(value: String?): List<InetAddress> =
        value.orEmpty().split(',').asSequence()
            .mapNotNull { lanIpv4(it.trim()) }
            .distinct()
            .take(8)
            .map { InetAddress.getByAddress(it.toByteArray()) }
            .toList()

    /** The four octets of a private-LAN IPv4 literal, or null. Leading zeros are refused (010 is octal elsewhere). */
    @JvmStatic
    fun lanIpv4(text: String): List<Byte>? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit) || (part.length > 1 && part[0] == '0')) return null
            part.toInt().takeIf { it in 0..255 } ?: return null
        }
        val (a, b) = octets
        val lan = a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
        return if (lan) octets.map(Int::toByte) else null
    }

    /** `p`: DeskLink's TCP port range only. */
    @JvmStatic
    fun parsePort(value: String?): Int? =
        value?.toIntOrNull()?.takeIf { it in org.desklink.mobile.backends.lan.LanLinkProvider.MIN_PORT..org.desklink.mobile.backends.lan.LanLinkProvider.MAX_PORT }

    /** Must equal `pairing_qr::proof` on the desktop, byte for byte. */
    @JvmStatic
    fun proof(secret: ByteArray, desktopId: String, phoneId: String, desktopFp: ByteArray, phoneFp: ByteArray): String =
        Base64.encodeToString(
            proofBytes(secret, desktopId, phoneId, desktopFp, phoneFp),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )

    @JvmStatic
    fun proofBytes(secret: ByteArray, desktopId: String, phoneId: String, desktopFp: ByteArray, phoneFp: ByteArray): ByteArray {
        val message = "$MAC_LABEL\n$desktopId\n$phoneId\n${hex(desktopFp)}\n${hex(phoneFp)}"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    /**
     * Runs the pairing on a worker thread. [onResult] gets null when the proof
     * was sent (the usual pairing callbacks report the outcome), or an error.
     */
    /** Thrown when the code is for a desktop already paired under another identity. */
    class ReplacesPairing(val deviceName: String) : Exception("Replaces the pairing with $deviceName")

    /**
     * A paired desktop that now shows a different certificate, or null. Pairing
     * with the code would have to replace that pairing, so the user decides.
     */
    /** Whether a paired computer's certificate is the one the code names. */
    @JvmStatic
    fun isSamePairing(pairedFingerprint: ByteArray, codeFingerprint: ByteArray): Boolean =
        pairedFingerprint.contentEquals(codeFingerprint)

    @JvmStatic
    fun conflictingPairing(application: DeskLinkApplication, code: Code): Device? =
        application.getDevice(code.desktopId)
            ?.takeIf { it.isPaired && !fingerprint(it.certificate).contentEquals(code.fingerprint) }

    /** The sentence for a failed pairing: ours when the failure carries one (a message we wrote), else [generic]; never empty. */
    @JvmStatic
    fun failureText(failure: Throwable, generic: String): String =
        failure.takeIf { it is IllegalStateException }?.message?.takeIf { it.isNotBlank() } ?: generic

    @JvmStatic
    fun pair(
        application: DeskLinkApplication,
        code: Code,
        replaceExisting: Boolean,
        onProgress: (Stage) -> Unit = {},
        onResult: (String?) -> Unit,
    ) {
        org.desklink.mobile.helpers.ThreadHelper.execute {
            // Any throwable is a failure, and what the person reads is one of our own sentences, never a raw exception.
            val failure = runCatching { pairBlocking(application, code, replaceExisting, onProgress) }.exceptionOrNull()
            onResult(failure?.let { failureText(it, application.getString(R.string.qr_error_generic)) })
        }
    }

    private fun pairBlocking(application: DeskLinkApplication, code: Code, replaceExisting: Boolean, onProgress: (Stage) -> Unit) {
        pendingDesktopId = code.desktopId
        try {
            // Never unpaired on a scan's say-so alone: a code shown elsewhere
            // can carry any desktop's public id. Only after the user confirmed
            // (replaceExisting) is the old record dropped, since its pinned
            // certificate would otherwise refuse the new one.
            // The same computer, the same identity, paired already: nothing to do, and nothing half-paired.
            application.getDevice(code.desktopId)?.takeIf { it.isPaired && isSamePairing(fingerprint(it.certificate), code.fingerprint) }?.let {
                error(application.getString(R.string.qr_error_already_paired, it.name))
            }
            // The old record has to go before the new link can be pinned (its certificate would refuse
            // the new one), so it is kept restorable: if the link or the proof fails, it comes back.
            var backup: PairingBackup? = null
            conflictingPairing(application, code)?.let { existing ->
                if (!replaceExisting) throw ReplacesPairing(existing.name)
                backup = PairingBackup.capture(application, existing.deviceId)
                existing.unpair()
            }
            try {
                connectAndProve(application, code, onProgress)
            } catch (error: Throwable) {
                backup?.let { it.restore(application); application.loadRememberedDevice(it.deviceId) }
                throw error
            }
        } finally {
            pendingDesktopId = null
        }
    }

    private fun connectAndProve(application: DeskLinkApplication, code: Code, onProgress: (Stage) -> Unit) {
        run {
            onProgress(Stage.CONNECTING)
            // QR v2: dial the desk's addresses ourselves (pinned to the code's fingerprint below),
            // no UDP hop. The announce-then-dial-back of v1 runs too, as the fallback.
            val port = code.port
            if (port != null && code.addresses.isNotEmpty()) {
                org.desklink.mobile.backends.lan.LanLinkProvider.instance?.dialDirect(code.desktopId, code.addresses, port)
            }
            code.host?.let { BackgroundService.instance?.sendIdentityTo(it) }
                ?: BackgroundService.instance?.refreshDiscovery()

            val device = waitForLink(application, code)
                ?: error(application.getString(R.string.qr_error_not_reached))
            val mac = proof(
                code.secret,
                code.desktopId,
                DeviceHelper.getDeviceId(application),
                code.fingerprint,
                fingerprint(SslHelper.certificate),
            )
            onProgress(Stage.VERIFYING)
            Log.i(TAG, "Sending QR pairing proof to ${code.desktopId}")
            device.pairingHandler.requestPairingWithProof(mac, device.certificate)
        }
    }

    /**
     * Waits for a pinned-TLS LAN link whose certificate matches the code. Not
     * any link whose certificate is only claimed), and an impostor answering
     * first under the desktop's public id is not fatal: keep waiting for the
     * real one until the deadline.
     */
    private fun waitForLink(application: DeskLinkApplication, code: Code): Device? {
        val deadline = SystemClock.elapsedRealtime() + LINK_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            application.getDevice(code.desktopId)
                ?.takeIf { device ->
                    device.hasLanLink &&
                        fingerprint(device.certificate).contentEquals(code.fingerprint)
                }
                ?.let { return it }
            Thread.sleep(200)
        }
        return null
    }

    private fun fingerprint(certificate: java.security.cert.Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
