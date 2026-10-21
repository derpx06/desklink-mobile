/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.mousepad

import org.desklink.mobile.NetworkPacket

/**
 * Pointer motion, at most one packet per display frame.
 *
 * Relative deltas are summed and an absolute position replaces the previous
 * one; the result goes out when [requestFrame] fires (Choreographer vsync in
 * the app). Any other packet goes through [sendAfterMotion], which sends the
 * pending motion first, so a click or key never overtakes the move before it.
 */
internal class PointerMotionCoalescer(
    private val requestFrame: (Runnable) -> Unit,
    private val send: (NetworkPacket) -> Unit,
) {
    private var dx = 0.0
    private var dy = 0.0
    private var hasDelta = false
    private var x = 0
    private var y = 0
    private var hasPosition = false
    private var frameRequested = false

    @Synchronized
    fun addDelta(dx: Double, dy: Double) {
        if (hasPosition) flushLocked()
        this.dx += dx
        this.dy += dy
        hasDelta = true
        requestFrameLocked()
    }

    @Synchronized
    fun moveTo(x: Int, y: Int) {
        if (hasDelta) flushLocked()
        this.x = x
        this.y = y
        hasPosition = true
        requestFrameLocked()
    }

    @Synchronized
    fun sendAfterMotion(packet: NetworkPacket) {
        flushLocked()
        send(packet)
    }

    @Synchronized
    fun flush() = flushLocked()

    private fun requestFrameLocked() {
        if (frameRequested) return
        frameRequested = true
        requestFrame(Runnable {
            synchronized(this) {
                frameRequested = false
                flushLocked()
            }
        })
    }

    private fun flushLocked() {
        if (hasDelta) {
            hasDelta = false
            val np = NetworkPacket(MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST)
            np["dx"] = dx
            np["dy"] = dy
            dx = 0.0
            dy = 0.0
            send(np)
        }
        if (hasPosition) {
            hasPosition = false
            val np = NetworkPacket(MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST)
            np["x"] = x
            np["y"] = y
            send(np)
        }
    }
}
