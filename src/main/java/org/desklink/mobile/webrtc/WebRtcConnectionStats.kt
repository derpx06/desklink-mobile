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

/** One poll of the live peer. Null fields are values libwebrtc has not measured yet. */
data class WebRtcConnectionStats(
    val path: WebRtcConnectionPath,
    val roundTripTimeMs: Long?,
    val availableOutgoingBitrateBps: Long?,
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
    if (localType == "relay" || remoteType == "relay") return WebRtcConnectionPath.TURN
    val local = localType == "host" || localType == "prflx"
    val remote = remoteType == "host" || remoteType == "prflx"
    return if (local && remote && !bootstrapViaRelay) WebRtcConnectionPath.LAN else WebRtcConnectionPath.DIRECT
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
    )
}
