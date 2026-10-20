/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.webrtc.PeerConnection

/** One ICE server entry before it becomes a libwebrtc object, so selection is plain logic. */
internal data class IceServerSpec(
    val urls: List<String>,
    val username: String? = null,
    val password: String? = null,
) {
    fun toIceServer(): PeerConnection.IceServer = PeerConnection.IceServer.builder(urls).apply {
        username?.let(::setUsername)
        password?.let(::setPassword)
    }.createIceServer()
}

/**
 * STUN for relay-bootstrapped peers when neither the relay nor the user
 * supplied one; the desktop's `DEFAULT_STUN_SERVER`. Never used on the LAN.
 */
internal const val DEFAULT_STUN_SERVER = "stun:stun.cloudflare.com:3478"

/**
 * The desktop's `ice_config_for`. With a LAN link: configured STUN, plus
 * configured TURN only when "Use TURN" is on. Bootstrapped through the relay:
 * configured STUN, else the relay grant's STUN, else [DEFAULT_STUN_SERVER];
 * plus configured and granted TURN only when "Use TURN" is on. With it off no
 * TURN is used at all, user-configured included. An empty [granted] (timeout,
 * `turn-denied`, `turn-quota`, relay offline) still leaves STUN.
 */
internal fun selectIceServers(
    hasLanLink: Boolean,
    useTurn: Boolean,
    configuredStun: List<IceServerSpec>,
    configuredTurn: List<IceServerSpec>,
    granted: List<IceServerSpec>,
): List<IceServerSpec> {
    val turn = if (!useTurn) emptyList() else if (hasLanLink) configuredTurn else configuredTurn + granted.only("turn:", "turns:")
    if (hasLanLink) return configuredStun + turn
    val stun = configuredStun
        .ifEmpty { granted.only("stun:", "stuns:") }
        .ifEmpty { listOf(IceServerSpec(listOf(DEFAULT_STUN_SERVER))) }
    return stun + turn
}

/** A grant entry may mix STUN and TURN URLs under one credential; keep those with [prefixes]. */
private fun List<IceServerSpec>.only(vararg prefixes: String): List<IceServerSpec> = mapNotNull { spec ->
    spec.urls.filter { url -> prefixes.any { url.startsWith(it, ignoreCase = true) } }
        .takeIf { it.isNotEmpty() }
        ?.let { spec.copy(urls = it) }
}
