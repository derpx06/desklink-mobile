/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

/** Must stay byte-for-byte compatible with the desktop channel contract. */
enum class WebRtcChannel(
    val label: String,
    val ordered: Boolean,
    val maxRetransmits: Int?,
) {
    CONTROL("desklink-control-v1", true, null),
    EVENTS("desklink-events-v1", true, null),
    INPUT_RELIABLE("desklink-input-reliable-v1", true, null),
    INPUT_REALTIME("desklink-input-realtime-v1", false, 0),
    FILE_CONTROL("desklink-file-control-v1", true, null),
    FILE_DATA("desklink-file-data-v1", true, null),
    TERMINAL("desklink-terminal-v1", true, null),
    ;

    companion object {
        fun fromLabel(label: String): WebRtcChannel? = entries.firstOrNull { it.label == label }

        fun validate(label: String, ordered: Boolean, maxRetransmits: Int?): WebRtcChannel {
            val channel = fromLabel(label) ?: error("Unknown DeskLink WebRTC channel: $label")
            require(channel.ordered == ordered && channel.maxRetransmits == maxRetransmits) {
                "Invalid reliability settings for DeskLink WebRTC channel: $label"
            }
            return channel
        }
    }
}
