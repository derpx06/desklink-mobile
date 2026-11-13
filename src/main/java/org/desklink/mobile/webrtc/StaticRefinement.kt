/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame

/**
 * V2 "static refinement": a screen capture only produces frames when something changes, so the
 * last frame of a movement is encoded once, at the quantiser the motion needed, and stays that
 * blurry. [delayMs] after the last frame the capture is asked to deliver it again ([force]), and
 * again every [delayMs] up to [repeats] times: each repeat lets the hardware encoder spend bits
 * refining the same picture (one repeat left text visibly soft). Frames during motion only push
 * the deadline back and reset the count.
 */
internal class StaticRefinement(
    private val delayMs: Long,
    private val repeats: Int,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val force: () -> Unit,
) {
    @Volatile private var forced = false
    @Volatile private var left = 0
    private val task = Runnable {
        left--
        forced = true
        force()
    }

    fun onFrame() {
        cancel(task)
        if (forced) {
            // A re-delivered frame: the next repeat, if any are left.
            forced = false
        } else {
            left = repeats
        }
        if (left > 0) schedule(task, delayMs)
    }

    fun stop() = cancel(task)
}

/**
 * Passes every captured frame on, and tells [refinement] about it.
 *
 * A re-delivered frame comes out of `SurfaceTextureHelper.forceFrame` with the **same** timestamp
 * as the original, and libwebrtc's frame-rate limiter (`VideoAdapter`, fed the capture time)
 * drops a frame that is not due yet by its timestamp — so every refinement was thrown away before
 * the encoder saw it. Timestamps are kept strictly increasing, on the capture clock (monotonic,
 * as [System.nanoTime]).
 */
internal class RefiningCapturerObserver(
    private val target: CapturerObserver,
    private val refinement: StaticRefinement,
    private val nowNs: () -> Long = System::nanoTime,
) : CapturerObserver {
    private var lastTimestampNs = Long.MIN_VALUE

    override fun onCapturerStarted(success: Boolean) = target.onCapturerStarted(success)

    override fun onCapturerStopped() {
        refinement.stop()
        target.onCapturerStopped()
    }

    override fun onFrameCaptured(frame: VideoFrame) {
        val stamped = if (frame.timestampNs > lastTimestampNs) {
            frame
        } else {
            // Shares the buffer; the capture releases it with the original frame.
            VideoFrame(frame.buffer, frame.rotation, maxOf(nowNs(), lastTimestampNs + 1))
        }
        lastTimestampNs = stamped.timestampNs
        target.onFrameCaptured(stamped)
        refinement.onFrame()
    }
}
