package org.desklink.mobile.helpers

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class LineTooLongException : IOException("Line too long")

/**
 * Reads until a \n is found or if maxLineSize is reached.
 * We only check for \n line terminators because that's what the DeskLink protocol uses.
 *
 * Pass [carry] when reading from a socket that has a read timeout. Without it,
 * a timeout striking mid-line discards everything read so far and the next call
 * starts a fresh buffer — so the remainder of the packet is then parsed as if it
 * were a whole one, throws `JSONException`, and tears the whole link down. A
 * large signed SDP or notification packet arriving during a stall did exactly
 * that. When [carry] is supplied the partial line survives the timeout and the
 * read resumes where it stopped.
 */
@JvmOverloads
@Throws(IOException::class)
fun readLineBounded(
    inputStream: InputStream,
    maxLineSize: Int,
    carry: ByteArrayOutputStream = ByteArrayOutputStream(1024),
): String {
    while (true) {
        val b = inputStream.read()
        if (b == -1) {
            throw IOException("Stream closed")
        }
        carry.write(b)
        if (b == '\n'.code) {
            // Can't specify Charsets.UTF_8 until Android API 33,
            // but the default charset is always UTF-8 on Android
            val line = carry.toString()
            carry.reset()
            return line
        }
        if (carry.size() >= maxLineSize) {
            // The stream is now positioned mid-line and there is no way to find
            // the next boundary without reading the rest of it, so the caller
            // cannot simply retry: doing that parsed the tail of an oversized
            // line as a fresh packet. Callers are expected to drop the link.
            carry.reset()
            throw LineTooLongException()
        }
    }
}
