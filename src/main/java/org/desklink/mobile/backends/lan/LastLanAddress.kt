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
 * Where each paired desk was last reached over the LAN: its last [AddressBook.MAX] addresses, with
 * the TCP port when it is known. With no link to a desk the phone announces itself to those
 * addresses directly (on the phone's own hotspot it never hears the desk's broadcasts, but the desk
 * hears a unicast announce and dials back) and dials them itself, with [DialBackoff] so a dead
 * address is not hammered.
 *
 * Kept in the device's own settings, so unpairing forgets it.
 */
object LastLanAddress {
    private const val KEY = "last_lan_address"
    private const val KEY_LIST = "last_lan_addresses"

    /** One address a desk may be reached at; [port] 0 = unknown (announce only, never dialed). */
    data class Known(val host: String, val port: Int)

    /** An address to dial: this desk, there. */
    data class Dial(val deviceId: String, val address: InetAddress, val port: Int)

    @JvmOverloads
    fun remember(context: Context, deviceId: String, address: InetAddress, port: Int = 0) {
        if (!TrustedDevices.isTrustedDevice(context, deviceId) || !usable(address)) return
        val host = address.hostAddress ?: return
        val prefs = TrustedDevices.getDeviceSettings(context, deviceId)
        val before = load(prefs.getString(KEY_LIST, null), prefs.getString(KEY, null))
        val after = AddressBook.remember(before, host, port)
        if (after != before) prefs.edit { putString(KEY_LIST, AddressBook.encode(after)) }
    }

    /** The numeric hosts a desk was last seen at (for telling a reinstalled machine from a different one). */
    fun hostsOf(context: Context, deviceId: String): Set<String> {
        val prefs = TrustedDevices.getDeviceSettings(context, deviceId)
        return load(prefs.getString(KEY_LIST, null), prefs.getString(KEY, null)).map { it.host }.toSet()
    }

    private fun load(list: String?, legacy: String?): List<Known> =
        AddressBook.decode(list).ifEmpty { legacy?.let { listOf(Known(it, 0)) } ?: emptyList() }

    private fun stored(context: Context): Map<String, List<Known>> =
        TrustedDevices.getAllTrustedDevices(context).associateWith {
            val prefs = TrustedDevices.getDeviceSettings(context, it)
            load(prefs.getString(KEY_LIST, null), prefs.getString(KEY, null))
        }

    /** The paired desks' last addresses (announce targets), except those already linked. */
    fun targets(context: Context, linked: Set<String>, trustedNetwork: Boolean): List<InetAddress> =
        if (trustedNetwork) announceTargets(stored(context), linked) else emptyList()

    /** The addresses to dial now: known port, not linked, and [backoff] allows it. */
    fun dialTargets(context: Context, linked: Set<String>, backoff: DialBackoff, now: Long, trustedNetwork: Boolean): List<Dial> =
        if (trustedNetwork) dialTargets(stored(context), linked, backoff, now) else emptyList()

    /** Pure: numeric private addresses only (never a name to resolve), one per address. */
    fun announceTargets(stored: Map<String, List<Known>>, linked: Set<String>): List<InetAddress> =
        stored.filterKeys { it !in linked }.values.flatten().map { it.host }
            .filter(::isNumeric)
            .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            .filter(::usable)
            .distinct()

    /** Pure: [announceTargets]' rules, plus a known port and the backoff's say. Most recent first per desk. */
    fun dialTargets(stored: Map<String, List<Known>>, linked: Set<String>, backoff: DialBackoff, now: Long): List<Dial> =
        stored.filterKeys { it !in linked }.flatMap { (id, list) ->
            list.filter { it.port > 0 && isNumeric(it.host) }.mapNotNull { known ->
                val address = runCatching { InetAddress.getByName(known.host) }.getOrNull()?.takeIf(::usable) ?: return@mapNotNull null
                Dial(id, address, known.port).takeIf { backoff.canDial("$id/${known.host}:${known.port}", now) }
            }
        }

    fun key(dial: Dial) = "${dial.deviceId}/${dial.address.hostAddress}:${dial.port}"

    private fun usable(address: InetAddress) = isPrivateAddress(address) && !address.isLoopbackAddress && !address.isAnyLocalAddress

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val IPV6 = Regex("""[0-9A-Fa-f:.]+(%[\w.]+)?""")

    private fun isNumeric(value: String) = IPV4.matches(value) || (':' in value && IPV6.matches(value))
}

/** The last [MAX] addresses of one desk, most recent first. */
object AddressBook {
    const val MAX = 3

    /** [host]:[port] moves to the front; an unknown port (0) never erases a known one for the same host. */
    fun remember(list: List<LastLanAddress.Known>, host: String, port: Int): List<LastLanAddress.Known> {
        val old = list.firstOrNull { it.host == host }
        val kept = LastLanAddress.Known(host, if (port > 0) port else old?.port ?: 0)
        return (listOf(kept) + list.filter { it.host != host }).take(MAX)
    }

    /** `host|port,host|port`; an IPv6 literal has no `|` or `,`. */
    fun encode(list: List<LastLanAddress.Known>): String = list.joinToString(",") { "${it.host}|${it.port}" }

    fun decode(value: String?): List<LastLanAddress.Known> = value.orEmpty().split(',').mapNotNull { part ->
        val bar = part.lastIndexOf('|')
        if (bar <= 0) return@mapNotNull null
        val port = part.substring(bar + 1).toIntOrNull()?.takeIf { it in 0..65535 } ?: return@mapNotNull null
        LastLanAddress.Known(part.substring(0, bar), port)
    }.take(MAX)
}

/**
 * Backs off an address that did not answer: 3 s, 6 s, 12 s … at most 5 minutes between tries, and
 * back to nothing on success or when the network changes. Keyed by whatever the caller names the
 * address; the map is bounded by the addresses stored (3 per desk), so it stays small.
 */
class DialBackoff {
    private class State(var failures: Int, var retryAt: Long)

    private val states = HashMap<String, State>()

    @Synchronized
    fun canDial(key: String, now: Long): Boolean = states[key]?.let { now >= it.retryAt } ?: true

    @Synchronized
    fun failed(key: String, now: Long) {
        val state = states.getOrPut(key) { State(0, 0) }
        state.failures++
        state.retryAt = now + delayAfter(state.failures)
    }

    @Synchronized
    fun succeeded(key: String) {
        states.remove(key)
    }

    /** A new network: every address is worth a fresh try. */
    @Synchronized
    fun reset() = states.clear()

    companion object {
        const val FIRST_DELAY_MS = 3_000L
        const val MAX_DELAY_MS = 5 * 60_000L

        fun delayAfter(failures: Int): Long =
            (FIRST_DELAY_MS shl (failures - 1).coerceIn(0, 10)).coerceAtMost(MAX_DELAY_MS)
    }
}
