/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mousepad

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskTextButton
import org.desklink.mobile.ui.compose.SecondaryButton

/**
 * What the desk viewer shows before the picture. Mirror: a spinner and "Connecting to <desk>…". Extend: a card made
 * for a phone on its side (icon and words side by side, short enough for 360 dp), saying what is happening in terms of
 * the second display. A stage that is not progress ([waiting] false: a refusal, a timeout) has no spinner and offers
 * Try again / Close.
 */
@Composable
fun ViewerStatus(
    extend: Boolean,
    deviceName: String,
    stage: String,
    waiting: Boolean,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** The computer never answered (the only stage that is about adding the display); any other stage is its own reason. */
    timedOut: Boolean = false,
) {
    if (!extend) {
        MirrorStatus(deviceName, stage, waiting, onRetry, onClose, modifier)
        return
    }
    Surface(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 12.dp).widthIn(max = 560.dp),
        shape = RoundedCornerShape(24.dp),
        color = PillBg,
        contentColor = PillText,
        border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f)),
    ) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The second display, ringed by the spinner while it is being added.
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                if (waiting) CircularProgressIndicator(Modifier.size(64.dp), color = LiveDot, strokeWidth = 3.dp)
                Box(Modifier.size(48.dp).background(ExtendTint, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.sym_desktop_windows), contentDescription = null, tint = LiveDot, modifier = Modifier.size(24.dp))
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        when {
                            waiting -> R.string.remote_extend_connecting_to
                            timedOut -> R.string.remote_extend_failed
                            else -> R.string.remote_extend_stopped
                        },
                        deviceName,
                    ),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PillText,
                )
                if (stage.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PillText.copy(alpha = 0.72f),
                        maxLines = 4,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (waiting) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.remote_extend_hint), fontSize = 12.sp, color = PillText.copy(alpha = 0.6f))
                } else {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(stringResource(R.string.device_try_again), onRetry, height = 48.dp, tonal = true)
                        DeskTextButton(stringResource(R.string.remote_screen_close), onClose)
                    }
                }
            }
        }
    }
}

@Composable
private fun MirrorStatus(deviceName: String, stage: String, waiting: Boolean, onRetry: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(32.dp).widthIn(max = 520.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (waiting) {
            CircularProgressIndicator(Modifier.size(32.dp))
            Spacer(Modifier.height(16.dp))
        }
        Text(
            stringResource(R.string.remote_connecting_to, deviceName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (stage.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                stage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 4,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (!waiting) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.device_try_again)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.remote_screen_close)) }
            }
        }
    }
}
