/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.webrtc.WebRtcConnectionPath
import org.desklink.mobile.webrtc.shown
import org.desklink.mobile.webrtc.WebRtcConnectionStats

/** A list that is dragged hides the keyboard, as the platform's own lists do; focus and the cursor stay where they were. */
@Composable
fun Modifier.dismissKeyboardOnScroll(): Modifier {
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val connection = remember(keyboard) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y != 0f) keyboard?.hide()
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    return nestedScroll(connection)
}

/**
 * Text in chrome that has a fixed number of equal slots (the tab bar, a segmented control) grows with the
 * system font size only up to [max]: past that a word no longer fits its slot and would be cut or broken.
 * The words themselves stay readable and the rest of the screen still scales to 200 %.
 */
@Composable
fun CappedFontScale(max: Float, content: @Composable () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val capped = remember(density, max) { androidx.compose.ui.unit.Density(density.density, minOf(density.fontScale, max)) }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides capped, content = content)
}

/** Numbers that line up and do not jitter as they change: battery, round trip, sizes. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

/** A [LaunchedEffect] that runs only while the screen is STARTED: a poll loop in it stops in the background and starts again on return. */
@Composable
fun StartedEffect(vararg keys: Any?, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(*keys, lifecycle) { lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED, block) }
}

/** The live WebRTC path and round trip for [deviceId], polled while on screen; null until known. */
@Composable
fun rememberConnectionStats(deviceId: String?): WebRtcConnectionStats? {
    var stats by remember(deviceId) { mutableStateOf<WebRtcConnectionStats?>(null) }
    val view = LocalView.current
    StartedEffect(deviceId) {
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
    val label = when (stats.path.shown()) {
        WebRtcConnectionPath.LAN -> stringResource(R.string.connection_local)
        WebRtcConnectionPath.DIRECT -> stringResource(R.string.remote_path_direct)
        WebRtcConnectionPath.TURN -> stringResource(R.string.remote_path_turn)
        null -> stringResource(R.string.connection_connected)
    }
    return stats.roundTripTimeMs?.let { stringResource(R.string.remote_path_with_rtt, label, it) } ?: label
}

private const val STATS_POLL_MS = 2_000L
