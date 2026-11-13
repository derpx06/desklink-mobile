/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.webrtc.RTCStats

/** How the selected ICE pair reaches the desktop, for a connection chip. */
enum class WebRtcConnectionPath(val label: String) {
    LAN("lan"),
    DIRECT("direct"),
    TURN("turn"),
}

/**
 * The path as a surface may name it. With the relay compiled out (BuildConfig.RELAY_ENABLED, 1.3) every link began on
 * the LAN, so nothing is named the internet or a relay: anything but LAN reads as not known yet (null). The real path
 * stays on the device for what decides on it (the fast lane).
 */
fun WebRtcConnectionPath?.shown(relayEnabled: Boolean = org.desklink.mobile.BuildConfig.RELAY_ENABLED): WebRtcConnectionPath? =
    if (relayEnabled || this == WebRtcConnectionPath.LAN) this else null

/** One poll of the live peer. Null fields are values libwebrtc has not measured yet. */
data class WebRtcConnectionStats(
    val path: WebRtcConnectionPath,
    val roundTripTimeMs: Long?,
    val availableOutgoingBitrateBps: Long?,
    /** The remote candidate's address of the selected pair, as libwebrtc reports it (the fast lane dials only this; never a peer-supplied field). */
    val remoteAddress: String? = null,
)

/** Delivered on libwebrtc's signaling thread; post to the UI thread yourself. */
fun interface WebRtcConnectionStatsCallback {
    /** Null when there is no peer or no selected candidate pair yet. */
    fun onStats(stats: WebRtcConnectionStats?)
}

/**
 * A relay candidate on either side is TURN. Otherwise host/prflx on both sides
 * is the LAN — unless this device was reached through the relay link, where a
 * host-host pair is a VPN or routed path, not "same network": that, and any
 * server-reflexive pair, is DIRECT.
 */
internal fun connectionPath(
    localType: String?,
    remoteType: String?,
    bootstrapViaRelay: Boolean,
): WebRtcConnectionPath {
    val l = canonicalCandidateType(localType)
    val r = canonicalCandidateType(remoteType)
    if (l == "relay" || r == "relay") return WebRtcConnectionPath.TURN
    val local = l == "host" || l == "prflx"
    val remote = r == "host" || r == "prflx"
    return if (local && remote && !bootstrapViaRelay) WebRtcConnectionPath.LAN else WebRtcConnectionPath.DIRECT
}

/**
 * Stats spell candidate types two ways: the short SDP names (libwebrtc) and the long ones
 * (webrtcbin: "server-reflexive", "peer-reflexive", "relayed"). Both map to the short form (B5).
 */
internal fun canonicalCandidateType(type: String?): String? = when (type) {
    "srflx", "server-reflexive" -> "srflx"
    "prflx", "peer-reflexive" -> "prflx"
    "relay", "relayed" -> "relay"
    else -> type
}

/** transport → selectedCandidatePairId → candidate-pair → local/remote candidate. */
internal fun connectionStatsFrom(
    report: Map<String, RTCStats>,
    bootstrapViaRelay: Boolean,
): WebRtcConnectionStats? {
    val pairId = report.values.firstNotNullOfOrNull {
        if (it.type == "transport") it.members["selectedCandidatePairId"] as? String else null
    } ?: return null
    val pair = report[pairId]?.members ?: return null
    val localType = report[pair["localCandidateId"] as? String]?.members?.get("candidateType") as? String
    val remoteType = report[pair["remoteCandidateId"] as? String]?.members?.get("candidateType") as? String
    // Unresolved candidates mean the path is unknown, not "over the internet".
    if (localType.isNullOrEmpty() || remoteType.isNullOrEmpty()) return null
    return WebRtcConnectionStats(
        path = connectionPath(localType, remoteType, bootstrapViaRelay),
        roundTripTimeMs = (pair["currentRoundTripTime"] as? Number)?.let { Math.round(it.toDouble() * 1000) },
        availableOutgoingBitrateBps = (pair["availableOutgoingBitrate"] as? Number)?.toLong(),
        remoteAddress = report[pair["remoteCandidateId"] as? String]?.members.let { (it?.get("address") ?: it?.get("ip")) as? String },
    )
}
