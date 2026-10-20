/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.LocalStatusColors
import org.desklink.mobile.ui.compose.model.device.DeviceConnection

@Composable
fun connectionLabel(connection: DeviceConnection): String = stringResource(
    when (connection) {
        DeviceConnection.LOCAL -> R.string.connection_local
        DeviceConnection.AWAY -> R.string.connection_away
        DeviceConnection.CONNECTED -> R.string.connection_connected
        DeviceConnection.CONNECTING -> R.string.connection_connecting
        DeviceConnection.OFFLINE -> R.string.connection_offline
    },
)

/** One line saying what the connection means for the person. */
@Composable
fun connectionDescription(connection: DeviceConnection): String = stringResource(
    when (connection) {
        DeviceConnection.LOCAL -> R.string.device_desc_local
        DeviceConnection.AWAY -> R.string.device_desc_away
        DeviceConnection.CONNECTED -> R.string.device_desc_connected
        DeviceConnection.CONNECTING -> R.string.device_desc_connecting
        DeviceConnection.OFFLINE -> R.string.device_desc_offline
    },
)

/** A small status capsule: a dot and the connection's name, tinted by state. */
@Composable
fun ConnectionPill(connection: DeviceConnection, modifier: Modifier = Modifier) {
    val status = LocalStatusColors.current
    val (dot, container, content) = when (connection) {
        DeviceConnection.LOCAL, DeviceConnection.CONNECTED ->
            Triple(status.online, status.onlineContainer, status.onOnlineContainer)
        DeviceConnection.AWAY -> Triple(status.away, status.awayContainer, status.onAwayContainer)
        DeviceConnection.CONNECTING -> Triple(status.pending, status.pendingContainer, status.onPendingContainer)
        DeviceConnection.OFFLINE -> Triple(
            MaterialTheme.colorScheme.outline,
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    StatusPill(
        text = connectionLabel(connection),
        dot = dot,
        container = container,
        content = content,
        pulsing = connection == DeviceConnection.CONNECTING,
        modifier = modifier,
    )
}

@Composable
fun StatusPill(
    text: String,
    dot: Color,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
) {
    Surface(shape = CircleShape, color = container, contentColor = content, modifier = modifier) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(dot, pulsing)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
fun StatusDot(color: Color, pulsing: Boolean = false, size: Dp = 8.dp) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "dot")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "dotAlpha",
        )
        a
    } else {
        1f
    }
    Box(
        Modifier
            .size(size)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

/** The device's icon in a rounded tile; tinted with the theme's primary while it is reachable. */
@Composable
fun DeviceAvatar(@DrawableRes icon: Int, online: Boolean, modifier: Modifier = Modifier, size: Dp = 52.dp) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(size * 0.32f),
        color = if (online) colors.primaryContainer else colors.surfaceContainerHighest,
        contentColor = if (online) colors.onPrimaryContainer else colors.onSurfaceVariant,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon > 0) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(size * 0.5f))
        }
    }
}

@Composable
fun BatteryBadge(percent: Int, charging: Boolean, modifier: Modifier = Modifier) {
    val description = stringResource(
        if (charging) R.string.battery_charging_description else R.string.battery_description,
        percent,
    )
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(if (charging) R.drawable.ic_bolt_24dp else R.drawable.ic_battery_24dp),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (!charging && percent <= 15) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.battery_percent, percent),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A section's label above a group of cards. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** A one-line notice with an icon; clickable when it has somewhere to send the person. */
@Composable
fun HintCard(
    @DrawableRes icon: Int,
    text: String,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    val container = if (warning) status.pendingContainer else colors.surfaceContainerHigh
    val content = if (warning) status.onPendingContainer else colors.onSurfaceVariant
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onClick != null) {
                Icon(painterResource(R.drawable.ic_chevron_right_24dp), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = container, contentColor = content, modifier = modifier, content = body)
    } else {
        Surface(shape = MaterialTheme.shapes.large, color = container, contentColor = content, modifier = modifier, content = body)
    }
}
