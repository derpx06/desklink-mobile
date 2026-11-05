/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a Termux [TerminalEmulator] screen, replacing Termux's `TerminalRenderer`.
 *
 * Why not the stock renderer: its cell width is the font's float advance (21.4 px), glyph runs and
 * run origins then disagree by sub-pixels, a fallback glyph is stretched with a canvas scale, and
 * the box drawing and block characters come from the font - which leaves gaps between rows
 * (line height 1.32 em) and has no Braille at all. Here:
 *
 *  * a cell is a whole number of pixels ([cellWidth] x [cellHeight]); a run's letter spacing is set
 *    so the font's advance is exactly [cellWidth];
 *  * U+2500-259F and Braille are drawn as shapes filling the cell edge to edge, so borders are
 *    continuous across rows and columns (what Alacritty, kitty and foot do);
 *  * any other glyph whose advance is not a whole number of cells (a fallback font, a wide
 *    character) is drawn alone in its cell(s), squeezed to fit.
 *
 * Nothing is scaled as a whole: a smaller screen is a smaller *font*, chosen by the caller.
 */
class TerminalPainter(private val regular: Typeface, private val bold: Typeface) {
    var textSizePx = 0
        private set

    /** Pixel size of one cell. */
    var cellWidth = 1
        private set
    var cellHeight = 1
        private set
    private var baseline = 0

    private val runPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val soloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val matches = HashMap<Int, Boolean>()
    private val one = CharArray(2)

    private val probe = Paint().apply { typeface = regular }

    /** Cell width and height in px at font size [px]; [setTextSize] makes them the painter's. */
    fun metrics(px: Int): Pair<Int, Int> {
        probe.textSize = px.toFloat()
        val fm = probe.fontMetrics
        return probe.measureText("M").roundToInt().coerceAtLeast(1) to ceil(fm.descent - fm.ascent + fm.leading).toInt().coerceAtLeast(1)
    }

    fun setTextSize(px: Int) {
        if (px == textSizePx) return
        textSizePx = px
        for (p in listOf(runPaint, soloPaint)) {
            p.typeface = regular
            p.textSize = px.toFloat()
            p.letterSpacing = 0f
        }
        val advance = runPaint.measureText("M")
        val (width, height) = metrics(px)
        cellWidth = width
        cellHeight = height
        // Letter spacing is in em: it makes every glyph of a run advance exactly one cell.
        runPaint.letterSpacing = (cellWidth - advance) / px
        val fm = runPaint.fontMetrics
        val ascent = -fm.ascent
        val descent = fm.descent
        baseline = ((cellHeight - (ascent + descent)) / 2f + ascent).roundToInt()
        matches.clear()
    }

    /** The largest font size (px) whose [columns] cells fit in [width] px, at least [minPx]. */
    fun fontSizeForColumns(columns: Int, width: Int, minPx: Int, maxPx: Int): Int {
        var size = maxPx
        while (size > minPx && metrics(size).first * columns > width) size--
        return size
    }

    /** A selection, in emulator rows (negative = scrollback): rows [y1]..[y2], from column [x1] on the first to [x2] on the last. */
    class Selection(val y1: Int, val y2: Int, val x1: Int, val x2: Int)

    fun render(canvas: Canvas, e: TerminalEmulator, topRow: Int, selection: Selection?, cursorOn: Boolean) {
        val palette = e.mColors.mCurrentColors
        val screen = e.screen
        val reverseAll = e.isReverseVideo
        val columns = e.mColumns
        if (reverseAll) canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND])
        val cursorVisible = cursorOn && e.shouldCursorBeVisible()
        val cursorRow = e.cursorRow
        val cursorCol = e.cursorCol
        val cursorShape = e.cursorStyle
        // A snapshot or resize while scrolled back can leave the view's top row out of range, and
        // Termux throws for a row it does not hold.
        val topRow = topRow.coerceIn(-screen.activeTranscriptRows, 0)

        for (r in 0 until e.mRows) {
            val row = topRow + r
            val y = r * cellHeight
            val line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))
            val text = line.mText
            val used = line.spaceUsed
            var selFrom = -1
            var selTo = -1
            if (selection != null && row >= selection.y1 && row <= selection.y2) {
                selFrom = if (row == selection.y1) selection.x1 else 0
                selTo = if (row == selection.y2) selection.x2 else columns - 1
            }
            val cursorHere = cursorVisible && row == cursorRow

            var col = 0
            var idx = 0
            var runCol = 0
            var runIdx = 0
            var runStyle = 0L
            var runCursor = false
            var runSelected = false
            var runOpen = false

            fun flush(endCol: Int, endIdx: Int) {
                if (runOpen && endCol > runCol) {
                    drawRun(canvas, text, runIdx, endIdx - runIdx, runCol, endCol - runCol, y, runStyle, palette, reverseAll, runSelected, runCursor, cursorShape)
                }
                runOpen = false
            }

            while (col < columns) {
                val cp: Int
                val units: Int
                if (idx < used) {
                    val c = text[idx]
                    if (Character.isHighSurrogate(c) && idx + 1 < used) {
                        cp = Character.toCodePoint(c, text[idx + 1])
                        units = 2
                    } else {
                        cp = c.code
                        units = 1
                    }
                } else {
                    cp = ' '.code
                    units = 0
                }
                val width = if (cp == ' '.code) 1 else WcWidth.width(cp).coerceIn(1, 2)
                // Combining marks that follow belong to this cell.
                var end = idx + units
                while (end < used && WcWidth.width(text, end) <= 0) end += if (Character.isHighSurrogate(text[end])) 2 else 1
                val style = line.getStyle(col)
                val atCursor = cursorHere && (cursorCol == col || (width == 2 && cursorCol == col + 1))
                val selected = selFrom >= 0 && col in selFrom..selTo
                val solo = width == 2 || end - idx - units > 0 || special(cp)

                if (solo) {
                    flush(col, idx)
                    drawSolo(canvas, text, idx, end - idx, cp, col, width, y, style, palette, reverseAll, selected, atCursor, cursorShape)
                } else if (!runOpen || style != runStyle || atCursor != runCursor || selected != runSelected) {
                    flush(col, idx)
                    runOpen = true
                    runCol = col
                    runIdx = idx
                    runStyle = style
                    runCursor = atCursor
                    runSelected = selected
                }
                col += width
                // Past the end of the stored text the cells are blanks: only their background is drawn.
                if (units != 0) idx = end
            }
            flush(columns, idx)
        }
    }

    // ---- colours -----------------------------------------------------------------------------------

    private var foreColor = 0
    private var backColor = 0

    /** Resolves the colours of [style] into [foreColor] / [backColor]; returns the effect bits. */
    private fun resolve(style: Long, palette: IntArray, reverseAll: Boolean, selected: Boolean, cursor: Boolean, cursorShape: Int): Int {
        var fore = TextStyle.decodeForeColor(style)
        var back = TextStyle.decodeBackColor(style)
        val effect = TextStyle.decodeEffect(style)
        val bold = effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK) != 0
        val truecolor = { c: Int -> (c.toLong() and 0xff000000L) == 0xff000000L }
        if (bold && fore in 0..7) fore += 8
        var f = if (truecolor(fore)) fore else palette[fore]
        var b = if (truecolor(back)) back else palette[back]
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_DIM != 0) {
            f = (0xff shl 24) or
                (((f shr 16 and 0xff) * 2 / 3) shl 16) or (((f shr 8 and 0xff) * 2 / 3) shl 8) or ((f and 0xff) * 2 / 3)
        }
        val inverse = (effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0) xor reverseAll xor selected
        if (inverse) {
            val t = f
            f = b
            b = t
        }
        if (cursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
            b = palette[TextStyle.COLOR_INDEX_CURSOR]
            f = palette[TextStyle.COLOR_INDEX_BACKGROUND]
        }
        foreColor = f
        backColor = b
        return effect
    }

    // ---- runs --------------------------------------------------------------------------------------

    private fun drawRun(
        canvas: Canvas,
        text: CharArray,
        index: Int,
        count: Int,
        col: Int,
        cols: Int,
        y: Int,
        style: Long,
        palette: IntArray,
        reverseAll: Boolean,
        selected: Boolean,
        cursor: Boolean,
        cursorShape: Int,
    ) {
        val effect = resolve(style, palette, reverseAll, selected, cursor, cursorShape)
        val x = col * cellWidth
        paintBackground(canvas, x, y, cols * cellWidth, palette)
        if (count > 0 && effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE == 0) {
            runPaint.typeface = if (effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0) bold else regular
            runPaint.textSkewX = if (effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0) -0.2f else 0f
            runPaint.color = foreColor
            canvas.drawText(text, index, count, x.toFloat(), (y + baseline).toFloat(), runPaint)
        }
        decorations(canvas, effect, x, y, cols * cellWidth)
        drawCursorShape(canvas, cursor, cursorShape, x, y, cols * cellWidth, palette)
    }

    private fun paintBackground(canvas: Canvas, x: Int, y: Int, w: Int, palette: IntArray) {
        if (backColor == palette[TextStyle.COLOR_INDEX_BACKGROUND]) return
        fill.color = backColor
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + cellHeight).toFloat(), fill)
    }

    private fun decorations(canvas: Canvas, effect: Int, x: Int, y: Int, w: Int) {
        if (effect and (TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE or TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) == 0) return
        fill.color = foreColor
        val thick = max(1, cellHeight / 16)
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0) {
            val top = y + min(cellHeight - thick, baseline + max(1, cellHeight / 14))
            canvas.drawRect(x.toFloat(), top.toFloat(), (x + w).toFloat(), (top + thick).toFloat(), fill)
        }
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH != 0) {
            val top = y + (baseline - textSizePx / 3)
            canvas.drawRect(x.toFloat(), top.toFloat(), (x + w).toFloat(), (top + thick).toFloat(), fill)
        }
    }

    private fun drawCursorShape(canvas: Canvas, cursor: Boolean, shape: Int, x: Int, y: Int, w: Int, palette: IntArray) {
        if (!cursor || shape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) return
        fill.color = palette[TextStyle.COLOR_INDEX_CURSOR]
        val t = max(2, cellHeight / 10)
        if (shape == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
            canvas.drawRect(x.toFloat(), (y + cellHeight - t).toFloat(), (x + w).toFloat(), (y + cellHeight).toFloat(), fill)
        } else {
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + max(2, cellWidth / 8)).toFloat(), (y + cellHeight).toFloat(), fill)
        }
    }

    // ---- single cells ------------------------------------------------------------------------------

    /** Whether [cp] is drawn alone in its cell: shapes, and glyphs whose advance is not exactly the cell's. */
    private fun special(cp: Int): Boolean {
        if (cp < 0x80) return false
        if (isShape(cp)) return true
        return !matches.getOrPut(cp) {
            val s = String(Character.toChars(cp))
            abs(runPaint.measureText(s) - cellWidth) < 0.6f
        }
    }

    private fun isShape(cp: Int) = cp in 0x2500..0x259F || cp in 0x2800..0x28FF

    private fun drawSolo(
        canvas: Canvas,
        text: CharArray,
        index: Int,
        count: Int,
        cp: Int,
        col: Int,
        width: Int,
        y: Int,
        style: Long,
        palette: IntArray,
        reverseAll: Boolean,
        selected: Boolean,
        cursor: Boolean,
        cursorShape: Int,
    ) {
        val effect = resolve(style, palette, reverseAll, selected, cursor, cursorShape)
        val x = col * cellWidth
        val w = width * cellWidth
        paintBackground(canvas, x, y, w, palette)
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE == 0) {
            fill.color = foreColor
            when {
                cp in 0x2500..0x257F -> boxDrawing(canvas, cp, x, y)
                cp in 0x2580..0x259F -> block(canvas, cp, x, y)
                cp in 0x2800..0x28FF -> braille(canvas, cp, x, y)
                else -> {
                    soloPaint.typeface = if (effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0) bold else regular
                    soloPaint.textSkewX = if (effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0) -0.2f else 0f
                    soloPaint.color = foreColor
                    val measured = soloPaint.measureText(text, index, count)
                    val squeeze = if (measured > w) w / measured else 1f
                    canvas.save()
                    canvas.translate(x + (w - measured * squeeze) / 2f, (y + baseline).toFloat())
                    canvas.scale(squeeze, 1f)
                    canvas.drawText(text, index, count, 0f, 0f, soloPaint)
                    canvas.restore()
                }
            }
        }
        decorations(canvas, effect, x, y, w)
        drawCursorShape(canvas, cursor, cursorShape, x, y, w, palette)
    }

    // ---- block elements ----------------------------------------------------------------------------

    private fun block(canvas: Canvas, cp: Int, x: Int, y: Int) {
        val w = cellWidth
        val h = cellHeight
        fun r(l: Float, t: Float, rt: Float, b: Float) {
            canvas.drawRect(x + l, y + t, x + rt, y + b, fill)
        }
        fun fx(f: Float) = (w * f).roundToInt().toFloat()
        fun fy(f: Float) = (h * f).roundToInt().toFloat()
        val halfX = fx(0.5f)
        val halfY = fy(0.5f)
        when (cp) {
            0x2580 -> r(0f, 0f, w.toFloat(), halfY)
            in 0x2581..0x2587 -> r(0f, fy(1f - (cp - 0x2580) / 8f), w.toFloat(), h.toFloat())
            0x2588 -> r(0f, 0f, w.toFloat(), h.toFloat())
            in 0x2589..0x258F -> r(0f, 0f, fx((0x2590 - cp) / 8f), h.toFloat())
            0x2590 -> r(halfX, 0f, w.toFloat(), h.toFloat())
            0x2591, 0x2592, 0x2593 -> {
                val alpha = when (cp) { 0x2591 -> 64; 0x2592 -> 128; else -> 192 }
                fill.color = (foreColor and 0x00ffffff) or (alpha shl 24)
                r(0f, 0f, w.toFloat(), h.toFloat())
            }
            0x2594 -> r(0f, 0f, w.toFloat(), fy(0.125f))
            0x2595 -> r(fx(0.875f), 0f, w.toFloat(), h.toFloat())
            in 0x2596..0x259F -> {
                // bit set: UL, UR, LL, LR
                val mask = intArrayOf(0b0010, 0b0001, 0b1000, 0b1011, 0b1001, 0b1110, 0b1111 and 0b1101, 0b0100, 0b0110, 0b0111)[cp - 0x2596]
                if (mask and 0b1000 != 0) r(0f, 0f, halfX, halfY)
                if (mask and 0b0100 != 0) r(halfX, 0f, w.toFloat(), halfY)
                if (mask and 0b0010 != 0) r(0f, halfY, halfX, h.toFloat())
                if (mask and 0b0001 != 0) r(halfX, halfY, w.toFloat(), h.toFloat())
            }
        }
    }

    // ---- braille -----------------------------------------------------------------------------------

    private fun braille(canvas: Canvas, cp: Int, x: Int, y: Int) {
        val bits = cp - 0x2800
        if (bits == 0) return
        dot.color = foreColor
        val radius = max(1f, min(cellWidth / 7f, cellHeight / 14f))
        // dot n -> (column, row): 1,2,3 down the left, 4,5,6 down the right, 7,8 the bottom row.
        val cols = intArrayOf(0, 0, 0, 1, 1, 1, 0, 1)
        val rows = intArrayOf(0, 1, 2, 0, 1, 2, 3, 3)
        for (n in 0 until 8) {
            if (bits and (1 shl n) == 0) continue
            val cx = x + cellWidth * (0.28f + 0.44f * cols[n])
            val cy = y + cellHeight * (0.125f + 0.25f * rows[n])
            canvas.drawCircle(cx, cy, radius, dot)
        }
    }

    // ---- box drawing -------------------------------------------------------------------------------

    private fun boxDrawing(canvas: Canvas, cp: Int, x: Int, y: Int) {
        val w = cellWidth.toFloat()
        val h = cellHeight.toFloat()
        val light = max(1, (cellWidth / 8f).roundToInt()).toFloat()
        val heavy = max(light + 1f, light * 2f)
        // Where the lines cross: the pixel centre, on whole pixels so strokes are crisp.
        val cx = (cellWidth / 2).toFloat()
        val cy = (cellHeight / 2).toFloat()

        when (cp) {
            0x256D, 0x256E, 0x256F, 0x2570 -> {
                arc(canvas, cp, x, y, light)
                return
            }
            0x2571, 0x2572, 0x2573 -> {
                stroke.strokeWidth = light
                stroke.color = foreColor
                if (cp != 0x2571) canvas.drawLine(x.toFloat(), y.toFloat(), x + w, y + h, stroke)
                if (cp != 0x2572) canvas.drawLine(x.toFloat(), y + h, x + w, y.toFloat(), stroke)
                return
            }
            in 0x2504..0x250B, in 0x254C..0x254F -> {
                dashes(canvas, cp, x, y, light, heavy, cx, cy)
                return
            }
        }
        val spec = BOX[cp - 0x2500]
        val left = spec[0] - '0'
        val right = spec[1] - '0'
        val up = spec[2] - '0'
        val down = spec[3] - '0'

        fun thick(weight: Int) = if (weight == 2) heavy else light
        // Half the width a set of strokes takes across its axis, to join a corner.
        fun halfOf(weight: Int) = when (weight) {
            0 -> 0f
            3 -> light * 1.5f
            else -> thick(weight) / 2f
        }
        val vertHalf = max(halfOf(up), halfOf(down))
        val horizHalf = max(halfOf(left), halfOf(right))
        val vertDouble = up == 3 || down == 3
        val horizDouble = left == 3 || right == 3

        // How far a stroke reaches past the centre into the stroke it joins. 0 when the opposite half
        // carries it through. A double line at a double corner is the outer or inner one.
        fun reach(opposite: Int, weight: Int, crossHalf: Float, crossDouble: Boolean, inside: Boolean): Float = when {
            opposite != 0 -> 0f
            weight == 3 && crossDouble -> if (inside) -light / 2f else light * 1.5f
            else -> crossHalf
        }

        fun hLine(from: Float, to: Float, yc: Float, t: Float) {
            val top = y + yc - kotlin.math.floor(t / 2f)
            canvas.drawRect(x + from, top, x + to, top + t, fill)
        }

        fun vLine(from: Float, to: Float, xc: Float, t: Float) {
            val l = x + xc - kotlin.math.floor(t / 2f)
            canvas.drawRect(l, y + from, l + t, y + to, fill)
        }

        for ((weight, isRight) in listOf(left to false, right to true)) {
            if (weight == 0) continue
            val t = if (weight == 3) light else thick(weight)
            for (o in if (weight == 3) floatArrayOf(-light, light) else floatArrayOf(0f)) {
                val inside = (o < 0 && up != 0) || (o > 0 && down != 0)
                val r = reach(if (isRight) left else right, weight, vertHalf, vertDouble, inside)
                if (isRight) hLine((cx - r).coerceAtLeast(0f), w, cy + o, t) else hLine(0f, (cx + r).coerceAtMost(w), cy + o, t)
            }
        }
        for ((weight, isDown) in listOf(up to false, down to true)) {
            if (weight == 0) continue
            val t = if (weight == 3) light else thick(weight)
            for (o in if (weight == 3) floatArrayOf(-light, light) else floatArrayOf(0f)) {
                val inside = (o < 0 && left != 0) || (o > 0 && right != 0)
                val r = reach(if (isDown) up else down, weight, horizHalf, horizDouble, inside)
                if (isDown) vLine((cy - r).coerceAtLeast(0f), h, cx + o, t) else vLine(0f, (cy + r).coerceAtMost(h), cx + o, t)
            }
        }
    }

    private fun arc(canvas: Canvas, cp: Int, x: Int, y: Int, light: Float) {
        val w = cellWidth.toFloat()
        val h = cellHeight.toFloat()
        val cx = x + (cellWidth / 2).toFloat() + (if (light.toInt() % 2 == 1) 0.5f else 0f)
        val cy = y + (cellHeight / 2).toFloat() + (if (light.toInt() % 2 == 1) 0.5f else 0f)
        val r = min(w, h) / 2f
        stroke.strokeWidth = light
        stroke.color = foreColor
        stroke.strokeCap = Paint.Cap.BUTT
        path.reset()
        when (cp) {
            0x256D -> { // right + down
                path.moveTo(x + w, cy); path.lineTo(cx + r, cy); path.quadTo(cx, cy, cx, cy + r); path.lineTo(cx, y + h)
            }
            0x256E -> { // left + down
                path.moveTo(x.toFloat(), cy); path.lineTo(cx - r, cy); path.quadTo(cx, cy, cx, cy + r); path.lineTo(cx, y + h)
            }
            0x256F -> { // left + up
                path.moveTo(x.toFloat(), cy); path.lineTo(cx - r, cy); path.quadTo(cx, cy, cx, cy - r); path.lineTo(cx, y.toFloat())
            }
            else -> { // 0x2570 right + up
                path.moveTo(x + w, cy); path.lineTo(cx + r, cy); path.quadTo(cx, cy, cx, cy - r); path.lineTo(cx, y.toFloat())
            }
        }
        canvas.drawPath(path, stroke)
    }

    private fun dashes(canvas: Canvas, cp: Int, x: Int, y: Int, light: Float, heavy: Float, cx: Float, cy: Float) {
        val (count, isHeavy, vertical) = when (cp) {
            0x2504 -> Triple(3, false, false)
            0x2505 -> Triple(3, true, false)
            0x2506 -> Triple(3, false, true)
            0x2507 -> Triple(3, true, true)
            0x2508 -> Triple(4, false, false)
            0x2509 -> Triple(4, true, false)
            0x250A -> Triple(4, false, true)
            0x250B -> Triple(4, true, true)
            0x254C -> Triple(2, false, false)
            0x254D -> Triple(2, true, false)
            0x254E -> Triple(2, false, true)
            else -> Triple(2, true, true)
        }
        val t = if (isHeavy) heavy else light
        val length = (if (vertical) cellHeight else cellWidth).toFloat()
        val step = length / count
        val dash = step * 0.6f
        for (i in 0 until count) {
            val start = i * step + (step - dash) / 2f
            if (vertical) {
                val l = x + cx - kotlin.math.floor(t / 2f)
                canvas.drawRect(l, y + start, l + t, y + start + dash, fill)
            } else {
                val top = y + cy - kotlin.math.floor(t / 2f)
                canvas.drawRect(x + start, top, x + start + dash, top + t, fill)
            }
        }
    }

    companion object {
        /**
         * U+2500..257F as `left right up down` weights: 0 none, 1 light, 2 heavy, 3 double. Entries
         * that are not straight strokes (dashes, arcs, diagonals) are drawn elsewhere and hold "0000".
         */
        private val BOX: Array<String> = Array(128) { "0000" }.also { t ->
            fun put(from: Int, vararg specs: String) = specs.forEachIndexed { i, spec -> t[from + i - 0x2500] = spec }
            put(0x2500, "1100", "2200", "0011", "0022")
            put(0x250C, "0101", "0201", "0102", "0202", "1001", "2001", "1002", "2002", "0110", "0210", "0120", "0220", "1010", "2010", "1020", "2020")
            put(0x251C, "0111", "0211", "0121", "0112", "0122", "0221", "0212", "0222", "1011", "2011", "1021", "1012", "1022", "2021", "2012", "2022")
            put(0x252C, "1101", "2101", "1201", "2201", "1102", "2102", "1202", "2202", "1110", "2110", "1210", "2210", "1120", "2120", "1220", "2220")
            put(0x253C, "1111", "2111", "1211", "2211", "1121", "1112", "1122", "2121", "1221", "2112", "1212", "2221", "2212", "2122", "1222", "2222")
            put(0x2550, "3300", "0033", "0301", "0103", "0303", "3001", "1003", "3003", "0310", "0130", "0330", "3010", "1030", "3030")
            put(0x255E, "0311", "0133", "0333", "3011", "1033", "3033", "3301", "1103", "3303", "3310", "1130", "3330", "3311", "1133", "3333")
            put(0x2574, "1000", "0010", "0100", "0001", "2000", "0020", "0200", "0002", "1200", "0012", "2100", "0021")
        }
    }
}
