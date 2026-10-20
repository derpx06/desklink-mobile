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

    /** Features are flowing, over a path that is neither of the above (e.g. Bluetooth). */
    CONNECTED,

    /** A link exists but the feature transport has not finished its handover. */
    CONNECTING,
    OFFLINE,
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
    val canOpenTerminal: Boolean = false,
    val canControlScreen: Boolean = false,
    val canSendFiles: Boolean = false,
)
