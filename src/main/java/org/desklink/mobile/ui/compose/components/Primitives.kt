/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.webrtc.WebRtcConnectionPath
import org.desklink.mobile.webrtc.WebRtcConnectionStats

/** Haptics for a primary action: a light tick, or the confirm/reject patterns where they apply. */
@Composable
fun rememberHaptics(): (HapticFeedbackType) -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) { { type -> haptics.performHapticFeedback(type) } }
}

/** Numbers that line up and do not jitter as they change: battery, round trip, sizes. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

/** The live WebRTC path and round trip for [deviceId], polled while on screen; null until known. */
@Composable
fun rememberConnectionStats(deviceId: String?): WebRtcConnectionStats? {
    var stats by remember { mutableStateOf<WebRtcConnectionStats?>(null) }
    val view = LocalView.current
    LaunchedEffect(deviceId) {
        while (true) {
            DeskLinkApplication.getInstance().getDevice(deviceId)?.requestConnectionStats { s -> view.post { stats = s } }
            delay(STATS_POLL_MS)
        }
    }
    return stats
}

/** "Local network · 8 ms", in the wording the rest of DeskLink uses for the path. */
@Composable
fun connectionStatsLabel(stats: WebRtcConnectionStats): String {
    val label = when (stats.path) {
        WebRtcConnectionPath.LAN -> stringResource(R.string.connection_local)
        WebRtcConnectionPath.DIRECT -> stringResource(R.string.remote_path_direct)
        WebRtcConnectionPath.TURN -> stringResource(R.string.remote_path_turn)
    }
    return stats.roundTripTimeMs?.let { stringResource(R.string.remote_path_with_rtt, label, it) } ?: label
}

private const val STATS_POLL_MS = 2_000L
