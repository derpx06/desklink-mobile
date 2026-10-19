/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.mousepad

import kotlin.math.max
import kotlin.math.min

/**
 * Local pinch-zoom of the desktop picture. A whole desktop on a phone screen is
 * too small to read or hit; zooming happens on the phone only — the desktop
 * never sees it — and every touch is mapped back through it.
 *
 * Content is drawn at `content * scale + offset` inside a view of `width` x
 * `height`; offsets are clamped so the zoomed picture always covers the view.
 */
data class DesktopViewTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    /** Zooms by `factor`, keeping the content under the focus point still. */
    fun zoomedBy(factor: Float, focusX: Float, focusY: Float, width: Float, height: Float): DesktopViewTransform {
        if (!factor.isFinite() || factor <= 0f) return this
        val newScale = (scale * factor).coerceIn(1f, MAX_SCALE)
        val contentX = (focusX - offsetX) / scale
        val contentY = (focusY - offsetY) / scale
        return DesktopViewTransform(newScale, focusX - contentX * newScale, focusY - contentY * newScale)
            .clamped(width, height)
    }

    fun pannedBy(dx: Float, dy: Float, width: Float, height: Float): DesktopViewTransform =
        copy(offsetX = offsetX + dx, offsetY = offsetY + dy).clamped(width, height)

    private fun clamped(width: Float, height: Float) = copy(
        offsetX = offsetX.coerceIn(min(0f, width - width * scale), 0f),
        offsetY = offsetY.coerceIn(min(0f, height - height * scale), 0f),
    )

    /** A point on the view, in unzoomed view coordinates. */
    fun toContent(x: Float, y: Float): Pair<Float, Float> = (x - offsetX) / scale to (y - offsetY) / scale

    companion object {
        const val MAX_SCALE = 5f

        /**
         * Zoomed so the picture fills the whole view with no bars — YouTube's
         * "zoom to fill" — centred, cropping the overflowing edges (still
         * reachable by pinch-panning). Identity when the aspects already match.
         */
        fun filling(viewWidth: Int, viewHeight: Int, desktopWidth: Int, desktopHeight: Int): DesktopViewTransform {
            if (viewWidth <= 0 || viewHeight <= 0 || desktopWidth <= 0 || desktopHeight <= 0) {
                return DesktopViewTransform()
            }
            val fit = min(viewWidth.toFloat() / desktopWidth, viewHeight.toFloat() / desktopHeight)
            val fill = max(viewWidth.toFloat() / desktopWidth, viewHeight.toFloat() / desktopHeight)
            val scale = (fill / fit).coerceIn(1f, MAX_SCALE)
            return DesktopViewTransform(
                scale = scale,
                offsetX = (viewWidth - viewWidth * scale) / 2f,
                offsetY = (viewHeight - viewHeight * scale) / 2f,
            )
        }
    }
}

/**
 * Where a touch lands on the desktop, in the desktop's own pixels (its
 * `screen-ready` size). The unzoomed picture is letterboxed into the view;
 * touches on the bars are null so they cannot click the screen edge.
 */
fun desktopPointFor(
    viewX: Float,
    viewY: Float,
    transform: DesktopViewTransform,
    viewWidth: Int,
    viewHeight: Int,
    desktopWidth: Int,
    desktopHeight: Int,
): ScreenPointF? {
    if (viewWidth <= 0 || viewHeight <= 0 || desktopWidth <= 0 || desktopHeight <= 0) return null
    val (x, y) = transform.toContent(viewX, viewY)
    val fit = min(viewWidth.toFloat() / desktopWidth, viewHeight.toFloat() / desktopHeight)
    val left = (viewWidth - desktopWidth * fit) / 2f
    val top = (viewHeight - desktopHeight * fit) / 2f
    val dx = (x - left) / fit
    val dy = (y - top) / fit
    if (dx < 0f || dy < 0f || dx > desktopWidth || dy > desktopHeight) return null
    return ScreenPointF(min(dx, desktopWidth - 1f), min(dy, desktopHeight - 1f))
}

/** Desktop pixels per view pixel at the current zoom: turns finger motion
 * into scroll distance that feels the same at any zoom. */
fun desktopPixelsPerViewPixel(
    transform: DesktopViewTransform,
    viewWidth: Int,
    viewHeight: Int,
    desktopWidth: Int,
    desktopHeight: Int,
): Float {
    if (viewWidth <= 0 || viewHeight <= 0 || desktopWidth <= 0 || desktopHeight <= 0) return 1f
    val fit = min(viewWidth.toFloat() / desktopWidth, viewHeight.toFloat() / desktopHeight)
    return 1f / max(fit * transform.scale, 1e-3f)
}

data class ScreenPointF(val x: Float, val y: Float)
