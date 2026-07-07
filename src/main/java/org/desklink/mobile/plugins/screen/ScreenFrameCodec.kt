/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.screen

import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer

enum class ScreenFrameFormat(val wireName: String) {
    JPEG("jpeg"),
    WEBP("webp"),
    PNG("png");

    companion object {
        fun fromWireName(wireName: String): ScreenFrameFormat {
            return entries.firstOrNull { it.wireName == wireName }
                ?: throw ScreenFrameCodecException.InvalidHeader()
        }
    }
}

data class ScreenFrameHeader(
    val streamId: String,
    val sequence: Long,
    val width: Int,
    val height: Int,
    val format: ScreenFrameFormat,
    val timestampMillis: Long
)

data class DecodedScreenFrame(
    val header: ScreenFrameHeader,
    val payload: ByteArray
)

sealed class ScreenFrameCodecException(message: String) : IllegalArgumentException(message) {
    class Truncated : ScreenFrameCodecException("screen frame message is truncated")
    class InvalidHeader : ScreenFrameCodecException("screen frame header is invalid")
    class InvalidLength : ScreenFrameCodecException("screen frame length exceeds supported range")
}

object ScreenFrameCodec {
    fun encode(header: ScreenFrameHeader, payload: ByteArray): ByteArray {
        val headerBytes = JSONObject()
            .put("streamId", header.streamId)
            .put("sequence", header.sequence)
            .put("width", header.width)
            .put("height", header.height)
            .put("format", header.format.wireName)
            .put("timestampMillis", header.timestampMillis)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES + headerBytes.size + Int.SIZE_BYTES + payload.size)
        buffer.putInt(headerBytes.size)
        buffer.put(headerBytes)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    fun decode(input: ByteArray): DecodedScreenFrame {
        val buffer = ByteBuffer.wrap(input)
        val headerLength = readLength(buffer)
        if (buffer.remaining() < headerLength) {
            throw ScreenFrameCodecException.Truncated()
        }

        val headerBytes = ByteArray(headerLength)
        buffer.get(headerBytes)
        val header = parseHeader(headerBytes)

        val payloadLength = readLength(buffer)
        if (buffer.remaining() < payloadLength) {
            throw ScreenFrameCodecException.Truncated()
        }

        val payload = ByteArray(payloadLength)
        buffer.get(payload)
        return DecodedScreenFrame(header, payload)
    }

    private fun readLength(buffer: ByteBuffer): Int {
        if (buffer.remaining() < Int.SIZE_BYTES) {
            throw ScreenFrameCodecException.Truncated()
        }

        val length = buffer.int
        if (length < 0) {
            throw ScreenFrameCodecException.InvalidLength()
        }
        return length
    }

    private fun parseHeader(headerBytes: ByteArray): ScreenFrameHeader {
        try {
            val json = JSONObject(String(headerBytes, Charsets.UTF_8))
            return ScreenFrameHeader(
                streamId = json.getString("streamId"),
                sequence = json.getLong("sequence"),
                width = json.getInt("width"),
                height = json.getInt("height"),
                format = ScreenFrameFormat.fromWireName(json.getString("format")),
                timestampMillis = json.getLong("timestampMillis")
            )
        } catch (exception: JSONException) {
            throw ScreenFrameCodecException.InvalidHeader()
        }
    }
}
