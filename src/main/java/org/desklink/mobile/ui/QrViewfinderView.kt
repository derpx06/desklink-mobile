/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import com.journeyapps.barcodescanner.ViewfinderView

/**
 * The QR scanner's overlay: a dimmed camera with a rounded window and corner brackets, instead of
 * the library's red laser line. Wired in through our override of `zxing_barcode_scanner.xml`.
 */
class QrViewfinderView(context: Context, attrs: AttributeSet?) : ViewfinderView(context, attrs) {
    private val density = resources.displayMetrics.density
    private val radius = 28 * density
    private val bracket = 36 * density
    private val mask = Path()
    private val window = RectF()
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5 * density
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }

    override fun onDraw(canvas: Canvas) {
        refreshSizes()
        val frame = framingRect ?: return
        window.set(frame)
        mask.reset()
        mask.fillType = Path.FillType.EVEN_ODD
        mask.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        mask.addRoundRect(window, radius, radius, Path.Direction.CW)
        canvas.drawPath(mask, maskPaint)

        // Four corner brackets hugging the rounded window.
        val l = window.left; val t = window.top; val r = window.right; val b = window.bottom
        val d = radius * 2
        canvas.drawArc(l, t, l + d, t + d, 180f, 90f, false, bracketPaint)
        canvas.drawLine(l + radius, t, l + bracket + radius / 2, t, bracketPaint)
        canvas.drawLine(l, t + radius, l, t + bracket + radius / 2, bracketPaint)
        canvas.drawArc(r - d, t, r, t + d, 270f, 90f, false, bracketPaint)
        canvas.drawLine(r - radius, t, r - bracket - radius / 2, t, bracketPaint)
        canvas.drawLine(r, t + radius, r, t + bracket + radius / 2, bracketPaint)
        canvas.drawArc(l, b - d, l + d, b, 90f, 90f, false, bracketPaint)
        canvas.drawLine(l + radius, b, l + bracket + radius / 2, b, bracketPaint)
        canvas.drawLine(l, b - radius, l, b - bracket - radius / 2, bracketPaint)
        canvas.drawArc(r - d, b - d, r, b, 0f, 90f, false, bracketPaint)
        canvas.drawLine(r - radius, b, r - bracket - radius / 2, b, bracketPaint)
        canvas.drawLine(r, b - radius, r, b - bracket - radius / 2, bracketPaint)
    }
}
