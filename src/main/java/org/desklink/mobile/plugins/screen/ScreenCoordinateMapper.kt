/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.screen

import kotlin.math.min
import kotlin.math.roundToInt

data class ScreenPoint(val x: Int, val y: Int)

class ScreenCoordinateMapper(
    private val remoteWidth: Int,
    private val remoteHeight: Int,
    private val viewWidth: Int,
    private val viewHeight: Int
) {
    fun mapTouch(touchX: Float, touchY: Float): ScreenPoint? {
        if (remoteWidth <= 0 || remoteHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return null
        }

        val scale = min(
            viewWidth.toFloat() / remoteWidth.toFloat(),
            viewHeight.toFloat() / remoteHeight.toFloat()
        )
        val displayedWidth = remoteWidth * scale
        val displayedHeight = remoteHeight * scale
        val offsetX = (viewWidth - displayedWidth) / 2f
        val offsetY = (viewHeight - displayedHeight) / 2f

        if (
            touchX < offsetX ||
            touchY < offsetY ||
            touchX > offsetX + displayedWidth ||
            touchY > offsetY + displayedHeight
        ) {
            return null
        }

        val remoteX = ((touchX - offsetX) / scale)
            .roundToInt()
            .coerceIn(0, remoteWidth - 1)
        val remoteY = ((touchY - offsetY) / scale)
            .roundToInt()
            .coerceIn(0, remoteHeight - 1)

        return ScreenPoint(remoteX, remoteY)
    }
}
