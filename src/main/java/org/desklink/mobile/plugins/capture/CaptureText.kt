/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.capture

/** The text of an insert-from-phone capture, bounded the way the computer bounds it. */
object CaptureText {
    /**
     * [text] cut to the most the computer accepts: 32 KiB in BYTES (not characters: 12 000 Chinese characters are 36 000
     * bytes and the whole message was refused), on a character boundary.
     */
    fun fit(text: String, maxBytes: Int = org.desklink.mobile.webrtc.WebRtcRemoteSessionControlMessage.MAX_CAPTURE_TEXT): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val cp = text.codePointAt(end)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(cp)
        }
        return text.substring(0, end)
    }
}
