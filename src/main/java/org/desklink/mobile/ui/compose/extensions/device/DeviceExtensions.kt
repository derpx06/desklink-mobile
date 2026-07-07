/*
 * SPDX-FileCopyrightText: 2026 Saul Cintero Chocarro <scintero@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.extensions.device

import org.desklink.mobile.Device
import org.desklink.mobile.DeviceType.LAPTOP
import org.desklink.mobile.DeviceType.PHONE
import org.desklink.mobile.DeviceType.TABLET
import org.desklink.mobile.DeviceType.TV
import org.desklink.mobile.ui.compose.model.device.DeviceUiModel
import org.desklink.mobile.R

fun Device.toUiModel() = DeviceUiModel(
    id = deviceId,
    icon = iconDrawable,
    name = name,
    summaryRes = if (compareProtocolVersion() > 0) R.string.protocol_version_newer else 0,
    isReachable = isReachable,
    isPaired = isPaired
)