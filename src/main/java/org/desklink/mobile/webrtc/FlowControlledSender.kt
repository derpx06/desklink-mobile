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
 *
 * The monitor guards only the queue. The native send and every callback run outside it, by one
 * drainer at a time, so a native callback can never wait on a lock a sender holds (and the order of
 * writes is the order of arrival).
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
    private var draining = false

    /** Set when the buffer went over [highWater] with messages waiting: they resume under [lowWater]. */
    private var backedUp = false

    fun send(bytes: ByteArray, callback: TransportCallback) {
        val refused = synchronized(this) {
            if (held.size >= MAX_HELD_MESSAGES || heldBytes + bytes.size > MAX_HELD_BYTES) {
                true
            } else {
                held.addLast(Held(bytes, callback))
                heldBytes += bytes.size
                false
            }
        }
        if (refused) {
            callback.onFailure(TransportError(TransportErrorCode.SEND_FAILED, "The data channel is backed up"))
            return
        }
        drain()
    }

    /** The channel's buffered amount went down. */
    fun onBufferedAmountChange() = drain()

    /** Writes held messages, in order, while the buffer allows; one thread at a time, outside the monitor. */
    private fun drain() {
        while (true) {
            val next = synchronized(this) {
                if (draining || held.isEmpty()) return
                val buffered = bufferedAmount()
                if (backedUp && buffered > lowWater) return
                if (buffered > highWater) {
                    backedUp = true
                    return
                }
                backedUp = false
                draining = true
                held.removeFirst().also { heldBytes -= it.bytes.size }
            }
            try {
                // A callback that throws must not strand the messages behind it.
                runCatching {
                    if (rawSend(next.bytes)) next.callback.onSuccess()
                    else next.callback.onFailure(TransportError(TransportErrorCode.SEND_FAILED, "WebRTC data channel rejected message"))
                }
            } finally {
                synchronized(this) { draining = false }
            }
        }
    }

    @Synchronized
    fun heldCount(): Int = held.size

    /** The channel closed: every held message is reported (CLOSED), in order. */
    fun failAll(reason: String) {
        val all = synchronized(this) {
            held.toList().also {
                held.clear()
                heldBytes = 0
            }
        }
        all.forEach { it.callback.onFailure(TransportError(TransportErrorCode.CLOSED, reason)) }
    }

    companion object {
        const val EVENTS_HIGH_WATER = 256L * 1024
        const val EVENTS_LOW_WATER = 64L * 1024
        const val MAX_HELD_MESSAGES = 512
        const val MAX_HELD_BYTES = 8L * 1024 * 1024
    }
}
