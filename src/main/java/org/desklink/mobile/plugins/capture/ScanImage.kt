/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.media.ExifInterface
import android.os.Build
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** A document scan's page: four corners in the photo's pixels, clockwise from top-left. */
data class Quad(val points: List<Pair<Float, Float>>) {
    init { require(points.size == 4) }
}

enum class ScanFilter { COLOUR, GREY, BLACK_WHITE }

/** The scan pipeline: corners, perspective, filter, PDF. Only platform graphics. */
object ScanImage {
    /** Long side of every page and photo sent (enough to read, small enough to send). */
    const val MAX_SIDE = 2560

    /** A photo from the camera, upright (EXIF) and at most [MAX_SIDE] on its long side. */
    fun load(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } else {
            0
        }
        val scale = minOf(1f, maxSide.toFloat() / max(raw.width, raw.height))
        val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also { if (it !== raw) raw.recycle() }
    }.getOrNull()

    /**
     * Where the page probably is: a quick edge/contrast pass on a small grey copy. Edge pixels are
     * those whose gradient is in the top tenth; the corners are the extreme edge points along the
     * diagonals. A result covering less than a fifth of the photo falls back to an 8 % inset.
     */
    fun detect(luma: IntArray, width: Int, height: Int): Quad {
        val fallback = inset(width, height, 0.08f)
        if (width < 8 || height < 8) return fallback
        val gradient = IntArray(width * height)
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            gradient[i] = abs(luma[i + 1] - luma[i - 1]) + abs(luma[i + width] - luma[i - width])
        }
        val sorted = gradient.copyOf().also { it.sort() }
        val threshold = max(sorted[(sorted.size * 0.9).toInt()], 24)
        var tl: Pair<Int, Int>? = null; var tr: Pair<Int, Int>? = null; var br: Pair<Int, Int>? = null; var bl: Pair<Int, Int>? = null
        var minSum = Int.MAX_VALUE; var maxSum = Int.MIN_VALUE; var minDiff = Int.MAX_VALUE; var maxDiff = Int.MIN_VALUE
        // Skip a 2 % border: the frame's own edge is not the page's.
        val mx = width / 50 + 1; val my = height / 50 + 1
        for (y in my until height - my) for (x in mx until width - mx) {
            if (gradient[y * width + x] < threshold) continue
            val sum = x + y; val diff = x - y
            if (sum < minSum) { minSum = sum; tl = x to y }
            if (sum > maxSum) { maxSum = sum; br = x to y }
            if (diff > maxDiff) { maxDiff = diff; tr = x to y }
            if (diff < minDiff) { minDiff = diff; bl = x to y }
        }
        val corners = listOf(tl, tr, br, bl)
        if (corners.any { it == null }) return fallback
        val quad = Quad(corners.map { it!!.first.toFloat() to it.second.toFloat() })
        return if (area(quad) < width * height / 5f) fallback else quad
    }

    fun inset(width: Int, height: Int, fraction: Float): Quad {
        val dx = width * fraction; val dy = height * fraction
        return Quad(listOf(dx to dy, width - dx to dy, width - dx to height - dy, dx to height - dy))
    }

    /** Shoelace area of the quad. */
    fun area(quad: Quad): Float {
        var sum = 0f
        for (i in 0 until 4) {
            val (x1, y1) = quad.points[i]; val (x2, y2) = quad.points[(i + 1) % 4]
            sum += x1 * y2 - x2 * y1
        }
        return abs(sum) / 2f
    }

    /** [detect] on a bitmap, through a ≤ 256 px grey copy, scaled back to the bitmap's pixels. */
    fun detect(bitmap: Bitmap): Quad {
        val scale = minOf(1f, 256f / max(bitmap.width, bitmap.height))
        val w = max(1, (bitmap.width * scale).toInt()); val h = max(1, (bitmap.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        if (small !== bitmap) small.recycle()
        val luma = IntArray(pixels.size) { i ->
            val c = pixels[i]
            (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000
        }
        val quad = detect(luma, w, h)
        return Quad(quad.points.map { (x, y) -> x / scale to y / scale })
    }

    /** The page flattened: [quad] mapped onto a rectangle as wide/tall as its longer opposite edges. */
    fun flatten(bitmap: Bitmap, quad: Quad): Bitmap {
        val p = quad.points
        fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot(a.first - b.first, a.second - b.second)
        val width = max(dist(p[0], p[1]), dist(p[3], p[2])).toInt().coerceIn(16, MAX_SIDE)
        val height = max(dist(p[0], p[3]), dist(p[1], p[2])).toInt().coerceIn(16, MAX_SIDE)
        val src = floatArrayOf(p[0].first, p[0].second, p[1].first, p[1].second, p[2].first, p[2].second, p[3].first, p[3].second)
        val dst = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
        val matrix = Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return out
    }

    /** Colour as shot, greyscale, or high-contrast black and white (paper white, ink black). */
    fun filter(bitmap: Bitmap, filter: ScanFilter): Bitmap {
        if (filter == ScanFilter.COLOUR) return bitmap
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        if (filter == ScanFilter.BLACK_WHITE) {
            val contrast = 2.6f
            val shift = -128f * contrast + 128f + 40f
            matrix.postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, shift,
                0f, contrast, 0f, 0f, shift,
                0f, 0f, contrast, 0f, shift,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bitmap, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        return out
    }

    fun writeJpeg(bitmap: Bitmap, file: File, quality: Int = 88): File {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return file
    }

    /** Every page into one PDF, each on a page of its own shape 595 pt (A4) wide. */
    fun writePdf(pages: List<Bitmap>, file: File): File {
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, page ->
                val width = 595
                val height = (595f * page.height / page.width).toInt().coerceAtLeast(1)
                val pdfPage = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                val scale = width.toFloat() / page.width
                pdfPage.canvas.drawBitmap(page, Matrix().apply { setScale(scale, scale) }, Paint(Paint.FILTER_BITMAP_FLAG))
                document.finishPage(pdfPage)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }
}
