/*
 * SPDX-FileCopyrightText: 2023 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile

import android.content.Context
import android.util.Base64
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import org.desklink.mobile.helpers.DeviceHelper
import org.desklink.mobile.helpers.TrustedDevices
import org.desklink.mobile.R
import java.security.cert.Certificate
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateException
import androidx.core.content.edit

/**
 * DeviceInfo contains all the properties needed to instantiate a Device.
 */
data class DeviceInfo(
    @JvmField val id: String,
    @JvmField val certificate: Certificate,
    @JvmField var name: String,
    @JvmField var type: DeviceType,
    @JvmField var protocolVersion: Int = 0,
    @JvmField var incomingCapabilities: Set<String>? = null,
    @JvmField var outgoingCapabilities: Set<String>? = null,
    /**
     * The peer's restart epoch: random per process start, absent from older
     * builds. A different one means the peer restarted and any WebRTC peer of
     * its previous process is dead. Mirrors the desktop's `instance_id`.
     */
    @JvmField var instanceId: String? = null,
    /** The peer's product version ("1.3.0"), from its identity: shown as a hint, never a trust or capability decision. */
    @JvmField var appVersion: String? = null,
) {

    /**
     * Saves the info in settings so it can be restored later using loadFromSettings().
     * This is used to keep info from paired devices, even when they are not reachable.
     * The capabilities and protocol version are not persisted.
     */
    fun saveInSettings(context: Context) {
        try {
            val encodedCertificate = Base64.encodeToString(certificate.encoded, 0)
            TrustedDevices.getDeviceSettings(context, id).edit(commit = true) {
                putString("certificate", encodedCertificate)
                putString("deviceName", name)
                putString("deviceType", type.toString())
                putInt("protocolVersion", protocolVersion)
                appVersion?.let { putString("appVersion", it) }
            }
        } catch (e: CertificateEncodingException) {
            throw RuntimeException(e)
        }
    }

    /**
     * Serializes to a NetworkPacket, which LanLinkProvider uses to send this data over the network.
     * The serialization doesn't include the certificate, since LanLink can query that from the socket.
     * Can be deserialized using fromIdentityPacketAndCert(), given a certificate.
     */
    fun toIdentityPacket(): NetworkPacket =
        NetworkPacket(NetworkPacket.PACKET_TYPE_IDENTITY).also { np ->
            np["deviceId"] = id
            np["deviceName"] = name
            np["protocolVersion"] = protocolVersion
            np["deviceType"] = type.toString()
            np["incomingCapabilities"] = incomingCapabilities!!
            np["outgoingCapabilities"] = outgoingCapabilities!!
            np["instanceId"] = PROCESS_INSTANCE_ID
            np["appVersion"] = org.desklink.mobile.BuildConfig.VERSION_NAME.take(MAX_APP_VERSION)
        }

    companion object {
        const val MAX_APP_VERSION = 32

        /** A version from the wire: at most [MAX_APP_VERSION] chars of digits, letters and `.+-_`, else null. */
        @JvmStatic
        fun cleanAppVersion(raw: String?): String? =
            raw?.takeIf { it.isNotEmpty() && it.length <= MAX_APP_VERSION && it.all { c -> c.isLetterOrDigit() || c in ".+-_" } }

        /** "Update DeskLink on <peer>": the major.minor of two versions differ (a patch difference is not skew); unparsable never. */
        @JvmStatic
        fun versionsSkew(own: String?, peer: String?): Boolean {
            fun majorMinor(v: String?) = v?.split('.')?.takeIf { it.size >= 2 }?.let { p ->
                val major = p[0].toIntOrNull()
                val minor = p[1].takeWhile(Char::isDigit).toIntOrNull()
                if (major != null && minor != null) major to minor else null
            }
            val a = majorMinor(own) ?: return false
            val b = majorMinor(peer) ?: return false
            return a != b
        }

        /** This process's restart epoch; see [instanceId]. */
        @JvmField
        val PROCESS_INSTANCE_ID: String = java.util.UUID.randomUUID().toString().replace("-", "")

        /** Whether two epochs prove a restart. Unknown on either side means no. */
        @JvmStatic
        fun peerRestarted(previous: String?, current: String?): Boolean =
            !previous.isNullOrEmpty() && !current.isNullOrEmpty() && previous != current

        /**
         * Recreates a DeviceInfo object that was persisted using saveInSettings()
         */
        @JvmStatic
        @Throws(CertificateException::class)
        fun loadFromSettings(context: Context, deviceId: String) =
            with(TrustedDevices.getDeviceSettings(context, deviceId)) {
                DeviceInfo(
                    id = deviceId,
                    name = getString("deviceName", "unknown")!!,
                    type = DeviceType.fromString(getString("deviceType", "desktop")!!),
                    certificate = TrustedDevices.getDeviceCertificate(context, deviceId),
                    protocolVersion = getInt("protocolVersion", 0),
                    appVersion = cleanAppVersion(getString("appVersion", "")),
                )
            }

        /**
         * Reads the stored
         */
        @JvmStatic
        fun loadProtocolVersionFromSettings(context: Context, deviceId: String) =
            with(TrustedDevices.getDeviceSettings(context, deviceId)) {
                getInt("protocolVersion", 0)
            }

        /**
         * Recreates a DeviceInfo object that was serialized using toIdentityPacket().
         * Since toIdentityPacket() doesn't serialize the certificate, this needs to be passed separately.
         */
        @JvmStatic
        fun fromIdentityPacketAndCert(identityPacket: NetworkPacket, certificate: Certificate) =
            with(identityPacket) {
                DeviceInfo(
                    id = getString("deviceId"), // Redundant: We could read this from the certificate instead
                    name = DeviceHelper.filterInvalidCharactersFromDeviceNameAndLimitLength(getString("deviceName", "unknown")),
                    type = DeviceType.fromString(getString("deviceType", "desktop")),
                    certificate = certificate,
                    protocolVersion = getInt("protocolVersion"),
                    incomingCapabilities = getStringSet("incomingCapabilities"),
                    outgoingCapabilities = getStringSet("outgoingCapabilities"),
                    instanceId = getString("instanceId", "").takeIf { it.isNotEmpty() && it.length <= 64 },
                    appVersion = cleanAppVersion(getString("appVersion", "")),
                )
            }

        @JvmStatic
        fun isValidIdentityPacket(identityPacket: NetworkPacket): Boolean = with(identityPacket) {
            type == NetworkPacket.PACKET_TYPE_IDENTITY &&
                    DeviceHelper.filterInvalidCharactersFromDeviceNameAndLimitLength(getString("deviceName", "")).isNotBlank() &&
                    isValidDeviceId(getString("deviceId", ""))
        }

        private val DEVICE_ID_REGEX = "^[a-zA-Z0-9_-]{32,38}$".toRegex()

        @JvmStatic
        fun isValidDeviceId(deviceId: String): Boolean = deviceId.matches(DEVICE_ID_REGEX)
    }
}

enum class DeviceType {
    PHONE, TABLET, DESKTOP, LAPTOP, TV;

    override fun toString() =
        when (this) {
            TABLET -> "tablet"
            PHONE -> "phone"
            TV -> "tv"
            LAPTOP -> "laptop"
            else -> "desktop"
        }

    fun getIcon(context: Context) =
        ContextCompat.getDrawable(context, toDrawableId())!!

    @DrawableRes
    fun toDrawableId() =
        when (this) {
            PHONE -> R.drawable.ic_device_phone_32dp
            TABLET -> R.drawable.ic_device_tablet_32dp
            TV -> R.drawable.ic_device_tv_32dp
            LAPTOP -> R.drawable.ic_device_laptop_32dp
            else -> R.drawable.ic_device_desktop_32dp
        }

    fun toShortcutDrawableId() =
        when (this) {
            PHONE -> R.drawable.ic_device_phone_shortcut
            TABLET -> R.drawable.ic_device_tablet_shortcut
            TV -> R.drawable.ic_device_tv_shortcut
            LAPTOP -> R.drawable.ic_device_laptop_shortcut
            else -> R.drawable.ic_device_desktop_shortcut
        }

    companion object {
        @JvmStatic
        fun fromString(s: String) =
            when (s) {
                "phone" -> PHONE
                "tablet" -> TABLET
                "tv" -> TV
                "laptop" -> LAPTOP
                else -> DESKTOP
            }
    }
}
