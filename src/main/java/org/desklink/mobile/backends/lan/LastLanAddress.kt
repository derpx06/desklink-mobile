/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.backends.lan

import android.content.Context
import androidx.core.content.edit
import org.desklink.mobile.helpers.TrustedDevices
import org.desklink.mobile.helpers.isPrivateAddress
import java.net.InetAddress

/**
 * Where each paired desk was last reached over the LAN. With no link to a desk the phone also
 * announces itself to that address directly: on the phone's own hotspot it never hears the desk's
 * broadcasts, but the desk hears a unicast announce and dials back as usual.
 *
 * Kept in the device's own settings, so unpairing forgets it.
 */
object LastLanAddress {
    private const val KEY = "last_lan_address"

    fun remember(context: Context, deviceId: String, address: InetAddress) {
        if (!TrustedDevices.isTrustedDevice(context, deviceId) || !usable(address)) return
        val value = address.hostAddress ?: return
        val prefs = TrustedDevices.getDeviceSettings(context, deviceId)
        if (prefs.getString(KEY, null) != value) prefs.edit { putString(KEY, value) }
    }

    /** The paired desks' last addresses, except those already linked. */
    fun targets(context: Context, linked: Set<String>): List<InetAddress> =
        targets(
            TrustedDevices.getAllTrustedDevices(context).associateWith { TrustedDevices.getDeviceSettings(context, it).getString(KEY, null) },
            linked,
        )

    /** Pure: numeric private addresses only (never a name to resolve), one per address. */
    fun targets(stored: Map<String, String?>, linked: Set<String>): List<InetAddress> =
        stored.filterKeys { it !in linked }.values.filterNotNull()
            .filter(::isNumeric)
            .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            .filter(::usable)
            .distinct()

    private fun usable(address: InetAddress) = isPrivateAddress(address) && !address.isLoopbackAddress && !address.isAnyLocalAddress

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val IPV6 = Regex("""[0-9A-Fa-f:.]+(%[\w.]+)?""")

    private fun isNumeric(value: String) = IPV4.matches(value) || (':' in value && IPV6.matches(value))
}
