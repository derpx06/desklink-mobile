/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mousereceiver

import kotlin.math.roundToInt

/**
 * Where the desk's pointer is on this phone, and when the overlay draws it.
 *
 * Moves arrive at the packet rate. Each one used to post its own `updateViewLayout` (a window relayout through the window
 * manager) to the main thread, so a fast mouse queued relayouts behind frames: the pointer trailed, then jumped. Now a move only
 * changes the position and asks for one frame ([requestFrame], a Choreographer callback); the frame draws the latest position.
 * The position keeps its fractions: a 0.3 px delta was rounded up to a whole pixel, so slow moves ran fast and drifted
 * diagonally. Thread-safe: moves come from the input thread (and the enter from the event thread), frames from the main thread.
 */
class PointerFrames(private val requestFrame: () -> Boolean) {
    private var x = 0.0
    private var y = 0.0
    private var width = 0
    private var height = 0
    private var frameRequested = false

    /** The screen's real size: the hot spot stays within 0..width and 0..height (the edges are where a glide hands back). */
    @Synchronized
    fun setBounds(width: Int, height: Int) {
        this.width = width
        this.height = height
        clamp()
    }

    /** Moves by a delta in desk pixels, one for one; a frame is asked for unless one already is. */
    @Synchronized
    fun moveBy(dx: Double, dy: Double) {
        if (!dx.isFinite() || !dy.isFinite()) return
        x += dx
        y += dy
        clamp()
        request()
    }

    @Synchronized
    fun moveTo(x: Double, y: Double) {
        if (!x.isFinite() || !y.isFinite()) return
        this.x = x
        this.y = y
        clamp()
        request()
    }

    @Synchronized
    fun x(): Int = x.roundToInt()

    @Synchronized
    fun y(): Int = y.roundToInt()

    /** The frame came: the position to draw now; the next move asks for the next frame. */
    @Synchronized
    fun frame(): IntArray {
        frameRequested = false
        return intArrayOf(x(), y())
    }

    /** Whether a frame is asked for and not drawn yet. */
    @Synchronized
    fun pending(): Boolean = frameRequested

    private fun request() {
        // Not marked when nothing could be asked (no overlay yet): the next move asks again.
        if (!frameRequested) frameRequested = requestFrame()
    }

    private fun clamp() {
        if (width > 0) x = x.coerceIn(0.0, width.toDouble())
        if (height > 0) y = y.coerceIn(0.0, height.toDouble())
    }
}
