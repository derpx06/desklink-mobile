/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame

/**
 * V2 "static refinement": a screen capture only produces frames when something changes, so the
 * last frame of a movement is often encoded once, blurry, and stays that way. [delayMs] after the
 * last frame the capture is asked to deliver it again ([force]), once, so the encoder can refine
 * it. Frames during motion only push the deadline back.
 */
internal class StaticRefinement(
    private val delayMs: Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val force: () -> Unit,
) {
    @Volatile private var forced = false
    private val task = Runnable {
        forced = true
        force()
    }

    fun onFrame() {
        if (forced) {
            // The re-delivered frame itself: nothing to refine after it.
            forced = false
            return
        }
        cancel(task)
        schedule(task, delayMs)
    }

    fun stop() = cancel(task)
}

/** Passes every captured frame on, and tells [refinement] about it. */
internal class RefiningCapturerObserver(
    private val target: CapturerObserver,
    private val refinement: StaticRefinement,
) : CapturerObserver {
    override fun onCapturerStarted(success: Boolean) = target.onCapturerStarted(success)

    override fun onCapturerStopped() {
        refinement.stop()
        target.onCapturerStopped()
    }

    override fun onFrameCaptured(frame: VideoFrame) {
        target.onFrameCaptured(frame)
        refinement.onFrame()
    }
}
