/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

/**
 * A1: the audio section's Opus line says `minptime=10;useinbandfec=1;stereo=1;maxaveragebitrate=128000`.
 * Applied to this side's own offer or answer before it is set and signed (libwebrtc writes only
 * the first two). Other sections are untouched.
 */
object WebRtcAudioSdp {
    private val REQUIRED = linkedMapOf(
        "minptime" to "10",
        "useinbandfec" to "1",
        "stereo" to "1",
        "maxaveragebitrate" to "128000",
    )

    fun withOpusParameters(sdp: String): String {
        val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val lines = sdp.split(eol).toMutableList()
        val opus = lines.mapNotNull { Regex("""^a=rtpmap:(\d+) opus/48000(/2)?$""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }.toSet()
        if (opus.isEmpty()) return sdp
        for (pt in opus) {
            val index = lines.indexOfFirst { it.startsWith("a=fmtp:$pt ") }
            if (index >= 0) {
                lines[index] = "a=fmtp:$pt " + merge(lines[index].removePrefix("a=fmtp:$pt "))
            } else {
                val rtpmap = lines.indexOfFirst { it.startsWith("a=rtpmap:$pt ") }
                lines.add(rtpmap + 1, "a=fmtp:$pt " + merge(""))
            }
        }
        return lines.joinToString(eol)
    }

    private fun merge(parameters: String): String {
        val pairs = linkedMapOf<String, String>()
        parameters.split(';').map(String::trim).filter(String::isNotEmpty).forEach { item ->
            val (key, value) = item.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            pairs[key] = value
        }
        REQUIRED.forEach { (key, value) -> pairs[key] = value }
        return pairs.entries.joinToString(";") { "${it.key}=${it.value}" }
    }
}
