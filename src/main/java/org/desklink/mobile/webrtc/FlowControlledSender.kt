/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.desklink.mobile.transport.TransportCallback
import org.desklink.mobile.transport.TransportError
import org.desklink.mobile.transport.TransportErrorCode
import java.util.ArrayDeque

/**
 * Send-side flow control for one data channel (the `events` channel). SCTP buffers whatever it is
 * handed, so a burst of feature packets used to sit in libwebrtc's buffer ahead of everything that
 * came after. Above [highWater] buffered bytes a message waits here instead, and goes out, in order,
 * when the channel reports the buffer back under [lowWater].
 *
 * Nothing is dropped silently: past [MAX_HELD_MESSAGES] / [MAX_HELD_BYTES] the message is refused
 * through its callback, which is how a caller finds out (the device queue reports or coalesces).
 */
class FlowControlledSender(
    private val bufferedAmount: () -> Long,
    private val rawSend: (ByteArray) -> Boolean,
    private val highWater: Long = EVENTS_HIGH_WATER,
    private val lowWater: Long = EVENTS_LOW_WATER,
) {
    private class Held(val bytes: ByteArray, val callback: TransportCallback)

    private val held = ArrayDeque<Held>()
    private var heldBytes = 0L

    @Synchronized
    fun send(bytes: ByteArray, callback: TransportCallback) {
        if (held.isEmpty() && bufferedAmount() <= highWater) return write(bytes, callback)
        if (held.size >= MAX_HELD_MESSAGES || heldBytes + bytes.size > MAX_HELD_BYTES) {
            callback.onFailure(TransportError(TransportErrorCode.SEND_FAILED, "The data channel is backed up"))
            return
        }
        held.addLast(Held(bytes, callback))
        heldBytes += bytes.size
    }

    /** The channel's buffered amount went down. */
    @Synchronized
    fun onBufferedAmountChange() {
        if (held.isEmpty() || bufferedAmount() > lowWater) return
        while (held.isNotEmpty() && bufferedAmount() <= highWater) {
            val next = held.removeFirst()
            heldBytes -= next.bytes.size
            write(next.bytes, next.callback)
        }
    }

    @Synchronized
    fun heldCount(): Int = held.size

    /** The channel closed: every held message is reported, in order. */
    @Synchronized
    fun failAll(reason: String) {
        while (held.isNotEmpty()) held.removeFirst().callback.onFailure(TransportError(TransportErrorCode.CLOSED, reason))
        heldBytes = 0
    }

    private fun write(bytes: ByteArray, callback: TransportCallback) {
        if (rawSend(bytes)) callback.onSuccess()
        else callback.onFailure(TransportError(TransportErrorCode.SEND_FAILED, "WebRTC data channel rejected message"))
    }

    companion object {
        const val EVENTS_HIGH_WATER = 256L * 1024
        const val EVENTS_LOW_WATER = 64L * 1024
        const val MAX_HELD_MESSAGES = 512
        const val MAX_HELD_BYTES = 8L * 1024 * 1024
    }
}
