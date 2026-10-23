/*
 * SPDX-FileCopyrightText: 2026 Saul Cintero Chocarro <scintero@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.model.device

/** How this phone reaches a device right now, as the person reads it. */
enum class DeviceConnection {
    /** Pinned TLS over the same network. */
    LOCAL,

    /** Through the DeskLink relay, away from the computer's network. */
    AWAY,

    /** Features are flowing, over a path that is neither of the above (the relay). */
    CONNECTED,

    /** A link exists but the feature transport has not finished its handover. */
    CONNECTING,
    OFFLINE,

    /** Reachable, but every recovery attempt failed: the connection cannot be set up now. It keeps trying. */
    UNAVAILABLE,
}

data class DeviceUiModel(
    val id: String,
    val icon: Int,
    val name: String,
    val summaryRes: Int,
    val isReachable: Boolean,
    val isPaired: Boolean,
    val connection: DeviceConnection = DeviceConnection.OFFLINE,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    /** The device asked to pair and is waiting for an answer here. */
    val wantsToPair: Boolean = false,
    /** Its terminal sessions, for the card's strip; empty unless the feature transport is up. */
    val sessions: List<org.desklink.mobile.ui.compose.components.SessionPreview> = emptyList(),
)
