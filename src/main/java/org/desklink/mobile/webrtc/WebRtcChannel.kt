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

    /** W2: only `desklink.heartbeat` (probes, replies, doze reports), so a feature burst cannot delay them. */
    LIVENESS("desklink-liveness-v1", true, null),
    ;

    companion object {
        /** An inbound liveness message above this is dropped and logged; the channel stays. */
        const val MAX_LIVENESS_ENVELOPE_BYTES = 2048

        /** A liveness send while this much is buffered is skipped: the next heartbeat supersedes it. */
        const val LIVENESS_MAX_BUFFERED_BYTES = 8192L

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
