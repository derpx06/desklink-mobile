/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

/**
 * The safety net under every plugin: what one desk may send of one feature type. Plugins keep
 * their own tighter bounds; this only stops a flood (a bug or a hostile peer) from reaching them.
 */
object InboundLimits {
    /** Pointer, pen and presenter streams: a desk sends up to 250 motion packets a second. */
    const val MAX_INPUT_PER_SECOND = 600

    /** Every other feature type: 20 a second on average is far above any real feature. */
    const val MAX_FEATURE_PER_MINUTE = 1200

    /** Notifications the phone posts for one desk: Android itself drops an app's flood past ~50. */
    const val MAX_DESK_NOTIFICATIONS_PER_MINUTE = 60

    /** A desk notification's icon read into memory (it is shrunk to icon size). */
    const val MAX_NOTIFICATION_ICON_BYTES = 2L * 1024 * 1024

    private val INPUT = setOf(
        DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST,
        DeskLinkProtocol.PACKET_TYPE_PRESENTER,
        DeskLinkProtocol.PACKET_TYPE_DIGITIZER,
    )

    fun isInput(type: String) = type in INPUT

    /** One desk's pair of limiters. */
    class ForDesk(tag: String = "DeskLink/Inbound", clock: (() -> Long)? = null) {
        private val input = if (clock != null) RateLimiter(MAX_INPUT_PER_SECOND, 1_000, tag, clock) else RateLimiter(MAX_INPUT_PER_SECOND, 1_000, tag)
        private val feature = if (clock != null) RateLimiter(MAX_FEATURE_PER_MINUTE, 60_000, tag, clock) else RateLimiter(MAX_FEATURE_PER_MINUTE, 60_000, tag)

        fun allow(type: String): Boolean = if (isInput(type)) input.allow(type) else feature.allow(type)
    }
}
