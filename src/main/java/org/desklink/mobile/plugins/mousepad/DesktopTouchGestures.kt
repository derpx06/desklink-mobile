/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.mousepad

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.abs

/**
 * What a touch on the desktop picture means, the way remote-desktop apps do it:
 *
 * - tap: left click there (two quick taps are a double click on the desktop)
 * - long press, then lift: right click there
 * - long press, then move: press and drag (hold) — select text, move windows
 * - one-finger drag: press, move, release — the same, without waiting
 * - two-finger drag: scroll, at the fingers
 * - pinch: zoom the picture on the phone (pans with the fingers while zoomed)
 * - two-finger tap: right click
 */
interface DesktopGestureSink {
    fun tap(at: Offset)
    fun rightClick(at: Offset)
    fun dragStart(at: Offset)
    fun dragMove(to: Offset)
    fun dragEnd()
    /** A two-finger scroll begins at [at]. */
    fun scrollStart(at: Offset)
    fun scroll(by: Offset)
    fun zoom(factor: Float, focus: Offset)
    fun pan(by: Offset)
    fun longPressFeedback()
}

private enum class FirstPhase { Tap, Drag, Multi }
internal enum class TwoFingerMode { Undecided, Zoom, Scroll }

/** Scroll or zoom, from how far the fingers moved together ([travel]) and
 * apart ([spread]). */
internal fun twoFingerMode(travel: Float, spread: Float, slop: Float) = when {
    travel > slop && travel >= spread -> TwoFingerMode.Scroll
    spread > slop * 2 && spread > travel -> TwoFingerMode.Zoom
    else -> TwoFingerMode.Undecided
}

suspend fun PointerInputScope.detectDesktopGestures(sink: DesktopGestureSink) {
    val slop = viewConfiguration.touchSlop
    val longPress = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        val first = withTimeoutOrNull(longPress) {
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) return@withTimeoutOrNull FirstPhase.Tap
                if (pressed.size >= 2) return@withTimeoutOrNull FirstPhase.Multi
                val moved = pressed.first().position - start
                if (moved.getDistance() > slop) return@withTimeoutOrNull FirstPhase.Drag
            }
            @Suppress("UNREACHABLE_CODE")
            FirstPhase.Tap
        }
        when (first) {
            null -> {
                // Held still. Lifting now is a right click; moving instead is a
                // held drag, which is how a touch screen selects or moves things.
                sink.longPressFeedback()
                var dragging = false
                while (true) {
                    val event = awaitPointerEvent()
                    val finger = event.changes.firstOrNull { it.id == down.id && it.pressed } ?: break
                    if (!dragging && (finger.position - start).getDistance() > slop) {
                        dragging = true
                        sink.dragStart(start)
                    }
                    if (dragging) sink.dragMove(finger.position)
                    event.changes.forEach(PointerInputChange::consume)
                }
                if (dragging) sink.dragEnd() else sink.rightClick(start)
                drainUntilAllUp()
            }
            FirstPhase.Tap -> sink.tap(start)
            FirstPhase.Drag -> {
                sink.dragStart(start)
                while (true) {
                    val event = awaitPointerEvent()
                    val finger = event.changes.firstOrNull { it.id == down.id && it.pressed } ?: break
                    sink.dragMove(finger.position)
                    event.changes.forEach(PointerInputChange::consume)
                }
                sink.dragEnd()
                drainUntilAllUp()
            }
            FirstPhase.Multi -> twoFingers(sink, slop)
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.drainUntilAllUp() {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach(PointerInputChange::consume)
        if (event.changes.none { it.pressed }) return
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.twoFingers(
    sink: DesktopGestureSink,
    slop: Float,
) {
    var mode = TwoFingerMode.Undecided
    var startCentroid: Offset? = null
    var startSpan = 0f
    var lastCentroid = Offset.Zero
    var lastSpan = 0f
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.size < 2) break
        val centroid = pressed.take(2).let { (a, b) -> (a.position + b.position) / 2f }
        val span = pressed.take(2).let { (a, b) -> (a.position - b.position).getDistance() }
        if (startCentroid == null) {
            startCentroid = centroid
            startSpan = span
        } else {
            if (mode == TwoFingerMode.Undecided) {
                // Fingers moving together always spread a little: whichever
                // changed more decides. Testing spread first turned most
                // scrolls into a zoom of the phone's picture.
                mode = twoFingerMode((centroid - startCentroid).getDistance(), abs(span - startSpan), slop)
                if (mode == TwoFingerMode.Scroll) sink.scrollStart(startCentroid)
            }
            when (mode) {
                TwoFingerMode.Zoom -> {
                    if (lastSpan > 0f) sink.zoom(span / lastSpan, centroid)
                    sink.pan(centroid - lastCentroid)
                }
                TwoFingerMode.Scroll -> sink.scroll(centroid - lastCentroid)
                TwoFingerMode.Undecided -> Unit
            }
        }
        lastCentroid = centroid
        lastSpan = span
        event.changes.forEach(PointerInputChange::consume)
    }
    if (mode == TwoFingerMode.Undecided) startCentroid?.let(sink::rightClick)
    drainUntilAllUp()
}
