/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.shell

import androidx.compose.runtime.Immutable
import org.desklink.mobile.ui.compose.model.device.DeviceConnection

/**
 * What the tab bar says without being opened: Home's icon carries the link state, Files' icon a progress
 * ring while the Bucket syncs or a file moves, Terminals a coral dot when a command finished or needs
 * attention, Settings an amber dot when a permission a switched-on feature needs is missing.
 */
@Immutable
data class TabBadges(
    val link: DeviceConnection = DeviceConnection.OFFLINE,
    /** 0..1 while something moves with a known size; null with [filesBusy] = moving, size unknown. */
    val filesProgress: Float? = null,
    val filesBusy: Boolean = false,
    val terminalsAttention: Boolean = false,
    val settingsAttention: Boolean = false,
)

/** What a tab's long-press does, or null when it has no quick action. */
enum class QuickAction { SWITCH_COMPUTER, SEND_FILES, NEW_NOTE, NEW_TERMINAL }

fun AppTab.quickAction(): QuickAction? = when (this) {
    AppTab.HOME -> QuickAction.SWITCH_COMPUTER
    AppTab.FILES -> QuickAction.SEND_FILES
    AppTab.NOTES -> QuickAction.NEW_NOTE
    AppTab.TERMINALS -> QuickAction.NEW_TERMINAL
    AppTab.SETTINGS -> null
}

/**
 * The Files ring: the Bucket's and the running transfer's progress together. [bucketFraction] is null when the
 * Bucket is idle or its size is unknown; [transferFraction] likewise; either can be busy without a fraction.
 */
fun filesBadge(bucketBusy: Boolean, bucketFraction: Float?, transferRunning: Boolean, transferFraction: Float?): Pair<Boolean, Float?> {
    val busy = bucketBusy || transferRunning
    if (!busy) return false to null
    val known = listOfNotNull(bucketFraction.takeIf { bucketBusy }, transferFraction.takeIf { transferRunning })
    return true to known.takeIf { it.isNotEmpty() }?.average()?.toFloat()?.coerceIn(0f, 1f)
}
