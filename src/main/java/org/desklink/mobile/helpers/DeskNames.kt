/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R

/** The computer's name for a sentence: never blank, so "appear on , and" cannot happen. */
object DeskNames {
    fun of(deviceId: String?): String = fallbackFor(deviceId?.let { DeskLinkApplication.getInstance().getDevice(it)?.name })

    fun fallbackFor(name: String?): String =
        name?.takeIf { it.isNotBlank() } ?: DeskLinkApplication.getInstance().getString(R.string.settings_your_computer)
}
