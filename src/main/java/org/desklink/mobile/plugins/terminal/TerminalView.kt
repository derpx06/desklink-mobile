/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Scroller
import androidx.core.content.edit
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import org.desklink.mobile.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a [TerminalScreen] with Termux's [TerminalRenderer] and turns touch, IME and hardware
 * keys into bytes for the desktop. Deliberately thin: everything testable lives in
 * [TerminalScreen] and [TerminalKeys].
 */
@SuppressLint("ViewConstructor")
class TerminalView(context: Context, private val host: Host) : View(context), TerminalScreen.Listener {
    /** Sticky modifiers as they apply to the next key. */
    data class Modifiers(val ctrl: Boolean, val alt: Boolean)

    interface Host {
        fun send(bytes: ByteArray)

        /** The key bar's Ctrl/Alt for the key being typed; one-shots are disarmed by this call. */
        fun takeModifiers(): Modifiers

        /**
         * The grid this view could show changed (first layout, rotation, keyboard, font size), debounced.
         * A request: the screen has the size the desktop reports, which may be another. UI thread.
         */
        fun onGridChanged(rows: Int, cols: Int)

        /** The person tapped the terminal and probably wants the keyboard. */
        fun onTap()

        fun onFontSizeChanged(sizePx: Int)
    }

    private var screen: TerminalScreen? = null
    private var textSizePx = 0

    /** JetBrains Mono (bundled, OFL-1.1); the system monospace if it cannot be loaded. */
    private val painter = TerminalPainter(
        androidx.core.content.res.ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE,
        androidx.core.content.res.ResourcesCompat.getFont(context, R.font.jetbrains_mono_bold) ?: Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
    )

    // Cell size in whole pixels (the painter's), and the viewport onto the screen's grid.
    private var fontWidth = 1
    private var lineSpacing = 1
    private var panX = 0
    private var panY = 0

    /**
     * Draw a grid wider than the view in a smaller font (the largest whole-pixel size that fits its
     * columns) instead of panning over it. A smaller real font, never a scaled bitmap.
     */
    var fitWidth = false
        set(value) {
            if (field == value) return
            field = value
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_FIT, value) }
            applyMetrics()
            invalidate()
        }

    private val gridRunnable = Runnable { host.onGridChanged(lastRows, lastCols) }

    /** The grid this view's size and font could show, and so what it asks the desktop for. */
    private var lastRows = 0
    private var lastCols = 0

    /** Top visible row in the emulator's coordinates: 0 is the live screen, negative is scrollback. */
    private var topRow = 0
    private var scrollRemainder = 0f
    private var scaleAccumulator = 1f
    private val scroller = Scroller(context)

    // Selection, in emulator coordinates (rows are relative to the live screen, negative = scrollback).
    private var selecting = false
    private var dragSelecting = false
    private var anchorX = 0
    private var anchorY = 0
    private var cursorX = 0
    private var cursorY = 0
    private var selectionMode: ActionMode? = null

    private val updatePending = AtomicBoolean(false)
    private val pacer = TerminalFramePacer()

    private val scaling = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (selecting) return true
                scaleAccumulator *= detector.scaleFactor
                // One point of font size per 10 % of pinch, so it tracks the fingers without thrashing the grid.
                if (scaleAccumulator > 1.1f) {
                    scaleAccumulator = 1f
                    setTextSizePx(textSizePx + 1)
                } else if (scaleAccumulator < 0.9f) {
                    scaleAccumulator = 1f
                    setTextSizePx(textSizePx - 1)
                }
                return true
            }
        },
    )

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (selecting) {
                    stopSelection()
                    return true
                }
                val s = screen ?: return true
                if (s.withEmulator { it.isMouseTrackingActive }) {
                    // A tap is a click for programs that track the mouse (tmux, vim with mouse=a).
                    val (col, row) = screenCell(e)
                    s.withEmulator {
                        it.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, col + 1, row + 1, true)
                        it.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, col + 1, row + 1, false)
                    }
                }
                // ...and still the way to the keyboard: those programs take
                // typing too, and skipping this left no way to type into them.
                requestFocus()
                host.onTap()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (screen == null || scaling.isInProgress) return true
                // A screen bigger than the view is panned over; only a vertical drag over one that
                // fits in height scrolls (history, wheel, arrows).
                if (maxPanX() > 0) {
                    panX = (panX + distanceX.toInt()).coerceIn(0, maxPanX())
                    invalidate()
                }
                if (maxPanY() > 0) {
                    panY = (panY + distanceY.toInt()).coerceIn(0, maxPanY())
                    invalidate()
                    return true
                }
                val distance = distanceY + scrollRemainder
                val rows = (distance / lineSpacing).toInt()
                scrollRemainder = distance - rows * lineSpacing
                scrollRows(rows, e2.x, e2.y)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val s = screen ?: return true
                if (!scroller.isFinished) return true
                val relative = s.withEmulator { it.isMouseTrackingActive || it.isAlternateBufferActive }
                val history = s.withEmulator { it.screen.activeTranscriptRows }
                val velocity = -(velocityY * FLING_SCALE).toInt()
                if (relative) {
                    scroller.fling(0, 0, 0, velocity, 0, 0, -rows() / 2, rows() / 2)
                } else {
                    scroller.fling(0, topRow, 0, velocity, 0, 0, -history, 0)
                }
                val x = e2.x
                val y0 = e2.y
                post(object : Runnable {
                    private var lastY = 0

                    override fun run() {
                        if (scroller.isFinished) return
                        val more = scroller.computeScrollOffset()
                        val y = scroller.currY
                        scrollRows(if (relative) y - lastY else y - topRow, x, y0)
                        lastY = y
                        if (more) post(this)
                    }
                })
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (screen == null || scaling.isInProgress) return
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val (col, row) = screenCell(e)
                anchorX = col
                cursorX = col
                anchorY = row + topRow
                cursorY = row + topRow
                selecting = true
                dragSelecting = true
                invalidate()
            }
        },
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.terminal_view_description)
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_FONT_PX, 0)
        textSizePx = if (stored > 0) {
            stored
        } else {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DEFAULT_FONT_SP, resources.displayMetrics).toInt()
        }
        fitWidth = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FIT, false)
        applyMetrics()
    }

    /** Shows [screen]. The screen outlives this view; only its listener is ours. */
    fun attach(screen: TerminalScreen) {
        this.screen?.takeIf { it.listener === this }?.listener = null
        this.screen = screen
        screen.listener = this
        lastRows = 0
        lastCols = 0
        topRow = 0
        stopSelection()
        applyMetrics()
        updateGrid(immediately = true)
        invalidate()
    }

    /** Whether the screen is bigger than the view, so dragging pans over it. */
    fun isPannable(): Boolean = maxPanX() > 0 || maxPanY() > 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        screen?.listener = this
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(gridRunnable)
        stopSelection()
        screen?.takeIf { it.listener === this }?.listener = null
        super.onDetachedFromWindow()
    }

    // ---- TerminalScreen.Listener (client thread) ----------------------------------------------

    override fun onScreenChanged(settled: Boolean) {
        synchronized(pacer) { pacer.changed(SystemClock.uptimeMillis(), settled) }
        // Coalesced: a flood of output costs one UI pass per frame, not one per chunk.
        if (updatePending.compareAndSet(false, true)) postOnAnimation(::frame)
    }

    /**
     * A vsync with output waiting: draw if the output has settled (see [TerminalFramePacer]), else look
     * again at the next one. A repaint that reached us in two chunks is never shown with half of it.
     */
    private fun frame() {
        updatePending.set(false)
        val (due, waiting) = synchronized(pacer) { pacer.due(SystemClock.uptimeMillis()) to pacer.pending }
        if (due) {
            screenUpdated()
        } else if (waiting && updatePending.compareAndSet(false, true)) {
            postOnAnimation(::frame)
        }
    }

    override fun onSnapshot() {
        post {
            topRow = 0
            stopSelection()
        }
    }

    override fun onBell() {
        post { performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
    }

    private fun screenUpdated() {
        val s = screen ?: return
        val (shift, history) = s.withEmulator {
            val shift = it.scrollCounter
            it.clearScrollCounter()
            shift to it.screen.activeTranscriptRows
        }
        if (selecting) {
            // New lines pushed the text up; keep the selection on the same text.
            topRow = (topRow - shift).coerceAtLeast(-history)
            anchorY -= shift
            cursorY -= shift
        } else {
            topRow = 0
            followCursor()
        }
        applyMetricsIfFit()
        invalidate()
    }

    // ---- metrics and layout ------------------------------------------------------------------------

    private fun gridSize(): Pair<Int, Int> = screen?.withEmulator { it.mColumns to it.mRows } ?: (lastCols to lastRows)

    /** Sets the painter's font: the chosen size, or the largest that fits the screen's columns in [fitWidth]. */
    private fun applyMetrics() {
        var px = textSizePx
        val (cols, _) = gridSize()
        if (fitWidth && width > 0 && cols > 0 && painter.metrics(px).first * cols > width) {
            px = painter.fontSizeForColumns(cols, width, MIN_FIT_PX, px)
        }
        painter.setTextSize(px)
        fontWidth = painter.cellWidth
        lineSpacing = painter.cellHeight
        clampPan()
    }

    private fun applyMetricsIfFit() {
        if (fitWidth) applyMetrics() else clampPan()
    }

    private fun setTextSizePx(size: Int) {
        val clamped = size.coerceIn(MIN_FONT_PX, MAX_FONT_PX)
        if (clamped == textSizePx) return
        textSizePx = clamped
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_FONT_PX, clamped) }
        applyMetrics()
        updateGrid()
        host.onFontSizeChanged(clamped)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyMetrics()
        updateGrid()
    }

    private fun rows(): Int = lastRows

    /**
     * Works out what the view could show at the chosen font and asks for it, a moment after the last
     * change (the keyboard animating in is one change per frame). The screen itself keeps the size
     * the desktop reports; see [TerminalScreen].
     */
    private fun updateGrid(immediately: Boolean = false) {
        if (screen == null || width == 0 || height == 0) return
        val (cellW, cellH) = painter.metrics(textSizePx)
        val cols = max(MIN_COLS, width / cellW)
        val rows = max(MIN_CELLS, height / cellH)
        if (cols == lastCols && rows == lastRows) return
        val first = lastCols == 0
        lastCols = cols
        lastRows = rows
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_ROWS, rows).putInt(KEY_COLS, cols) }
        removeCallbacks(gridRunnable)
        if (immediately || first) gridRunnable.run() else postDelayed(gridRunnable, GRID_DEBOUNCE_MS)
    }

    // ---- viewport ----------------------------------------------------------------------------------

    private fun maxPanX(): Int = max(0, gridSize().first * fontWidth - width)

    private fun maxPanY(): Int = max(0, gridSize().second * lineSpacing - height)

    private fun clampPan() {
        panX = panX.coerceIn(0, maxPanX())
        panY = panY.coerceIn(0, maxPanY())
    }

    /** Keeps the cursor inside the view when the screen is bigger than it. */
    private fun followCursor() {
        if (maxPanX() == 0 && maxPanY() == 0) return
        val (cx, cy) = screen?.withEmulator { it.cursorCol to it.cursorRow } ?: return
        val left = cx * fontWidth
        val top = cy * lineSpacing
        if (left < panX) panX = left else if (left + fontWidth > panX + width) panX = left + fontWidth - width
        if (top < panY) panY = top else if (top + lineSpacing > panY + height) panY = top + lineSpacing - height
        clampPan()
    }

    override fun onDraw(canvas: Canvas) {
        val s = screen
        if (s == null) {
            canvas.drawColor(0xFF000000.toInt())
            return
        }
        val selection = if (selecting) selectionBounds().let { (y1, y2, x1, x2) -> TerminalPainter.Selection(y1, y2, x1, x2) } else null
        s.withEmulator { emulator ->
            canvas.drawColor(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
            canvas.save()
            canvas.translate(-panX.toFloat(), -panY.toFloat())
            painter.render(canvas, emulator, topRow, selection, cursorOn = true)
            canvas.restore()
        }
    }

    private fun selectionBounds(): IntArray {
        if (!selecting) return intArrayOf(Int.MAX_VALUE, Int.MIN_VALUE, 0, 0)
        val forward = anchorY < cursorY || (anchorY == cursorY && anchorX <= cursorX)
        return if (forward) intArrayOf(anchorY, cursorY, anchorX, cursorX) else intArrayOf(cursorY, anchorY, cursorX, anchorX)
    }

    // ---- touch --------------------------------------------------------------------------------------

    private fun screenCell(e: MotionEvent): Pair<Int, Int> = screenCell(e.x, e.y)

    private fun screenCell(x: Float, y: Float): Pair<Int, Int> {
        val (cols, rows) = gridSize()
        val col = ((x + panX) / fontWidth).toInt().coerceIn(0, max(0, cols - 1))
        val row = ((y + panY) / lineSpacing).toInt().coerceIn(0, max(0, rows - 1))
        return col to row
    }

    private fun scrollRows(rowsDown: Int, x: Float, y: Float) {
        val s = screen ?: return
        val up = rowsDown < 0
        val (col, row) = screenCell(x, y)
        repeat(abs(rowsDown)) {
            val (mouse, alt, cursorApp) = s.withEmulator {
                Triple(it.isMouseTrackingActive, it.isAlternateBufferActive, it.isCursorKeysApplicationMode)
            }
            when {
                // The program handles the mouse: give it wheel events.
                mouse -> s.withEmulator {
                    it.sendMouseEvent(
                        if (up) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON,
                        col + 1,
                        row + 1,
                        true,
                    )
                }
                // less, man, vim without a mouse: there is no scrollback to move, so page with the arrows.
                alt -> TerminalKeys.key(
                    if (up) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN,
                    cursorApp = cursorApp,
                )?.let(host::send)
                else -> {
                    val history = s.withEmulator { it.screen.activeTranscriptRows }
                    topRow = (topRow + if (up) -1 else 1).coerceIn(-history, 0)
                }
            }
        }
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dragSelecting) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val (col, row) = screenCell(event)
                    cursorX = col
                    cursorY = row + topRow
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragSelecting = false
                    showSelectionMenu()
                }
            }
            return true
        }
        scaling.onTouchEvent(event)
        if (!scaling.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            scrollRemainder = 0f
        }
        return true
    }

    // ---- selection ----------------------------------------------------------------------------------

    private fun showSelectionMenu() {
        selectionMode?.finish()
        selectionMode = startActionMode(
            object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    menu.add(Menu.NONE, MENU_COPY, 0, R.string.terminal_copy)
                    menu.add(Menu.NONE, MENU_SELECT_ALL, 1, R.string.terminal_select_all)
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    when (item.itemId) {
                        MENU_COPY -> {
                            copySelection()
                            mode.finish()
                        }
                        MENU_SELECT_ALL -> selectAll()
                    }
                    return true
                }

                override fun onDestroyActionMode(mode: ActionMode) {
                    selectionMode = null
                    stopSelection()
                }

                override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                    val (y1, _, x1, _) = selectionBounds()
                    val top = ((y1 - topRow) * lineSpacing - panY).coerceIn(0, height)
                    val left = (x1 * fontWidth - panX).coerceIn(0, width)
                    outRect.set(left, top, left + 1, top + lineSpacing)
                }
            },
            ActionMode.TYPE_FLOATING,
        )
    }

    private fun selectAll() {
        val s = screen ?: return
        val history = s.withEmulator { it.screen.activeTranscriptRows }
        anchorX = 0
        anchorY = -history
        val (cols, rows) = gridSize()
        cursorX = cols - 1
        cursorY = rows - 1
        invalidate()
    }

    private fun copySelection() {
        val s = screen ?: return
        val (y1, y2, x1, x2) = selectionBounds()
        val text = s.withEmulator { it.getSelectedText(x1, y1, x2, y2) }
        if (text.isNotEmpty()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.terminal_clip_label), text))
        }
    }

    fun stopSelection() {
        selectionMode?.finish()
        selectionMode = null
        if (selecting) {
            selecting = false
            dragSelecting = false
            invalidate()
        }
    }

    // ---- keyboard ---------------------------------------------------------------------------------

    fun showKeyboard() {
        requestFocus()
        // An explicit request: SHOW_IMPLICIT may be refused, and was.
        androidx.core.view.ViewCompat.getWindowInsetsController(this)
            ?.show(androidx.core.view.WindowInsetsCompat.Type.ime())
            ?: (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // No suggestions, so keyboards send characters at once instead of composing and
        // autocorrecting. TYPE_CLASS_TEXT is required: without a class this reads as TYPE_NULL, and some
        // keyboards then refuse to open. Never a password variation: ColorOS blacks out every screen
        // capture (screenshots and screen sharing) while a password field has the keyboard.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flushEditable()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flushEditable()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // Some IMEs (Samsung with spell check) delete several characters at once.
                repeat(beforeLength) { sendKey(KeyEvent.KEYCODE_DEL, null) }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            private fun flushEditable() {
                val content = editable ?: return
                if (content.isNotEmpty()) sendText(content.toString())
                content.clear()
            }
        }
    }

    /** IME text, in one send. A bare newline is the Enter key. */
    private fun sendText(text: String) {
        stopSelection()
        val modifiers = host.takeModifiers()
        host.send(TerminalKeys.text(text.replace('\n', '\r'), modifiers.ctrl, modifiers.alt))
    }

    private fun sendKey(keyCode: Int, event: KeyEvent?): Boolean {
        val s = screen ?: return false
        stopSelection()
        val modifiers = host.takeModifiers()
        val (cursorApp, keypadApp) = s.withEmulator { it.isCursorKeysApplicationMode to it.isKeypadApplicationMode }
        val bytes = TerminalKeys.key(
            keyCode,
            ctrl = modifiers.ctrl || event?.isCtrlPressed == true,
            alt = modifiers.alt || event?.isAltPressed == true,
            shift = event?.isShiftPressed == true,
            cursorApp = cursorApp,
            keypadApp = keypadApp,
        )
        if (bytes != null) {
            host.send(bytes)
            return true
        }
        // Ctrl and left Alt are applied by TerminalKeys, so they must not also change the character
        // (Alt+B would otherwise arrive as a composed symbol); right Alt stays for AltGr layouts.
        val meta = (event?.metaState ?: 0) and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON).inv()
        val codePoint = (event?.getUnicodeChar(meta) ?: 0) and CODE_POINT_MASK
        if (codePoint == 0 || !Character.isValidCodePoint(codePoint)) return false
        host.send(
            TerminalKeys.text(
                String(Character.toChars(codePoint)),
                modifiers.ctrl || event?.isCtrlPressed == true,
                modifiers.alt || event?.isAltPressed == true,
            ),
        )
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Back, volume, home and friends belong to the system and the activity.
        if (event.isSystem || keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        return sendKey(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    companion object {
        /** The (rows, columns) the terminal screen last asked for, else the fallbacks. */
        fun rememberedGrid(context: Context, fallbackRows: Int, fallbackCols: Int): Pair<Int, Int> {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_ROWS, fallbackRows) to prefs.getInt(KEY_COLS, fallbackCols)
        }

        private const val PREFS = "desklink_terminal_view"
        private const val KEY_FONT_PX = "font_px"
        private const val DEFAULT_FONT_SP = 13f
        private const val MIN_FONT_PX = 14
        private const val MAX_FONT_PX = 120
        private const val MIN_CELLS = 4

        /** The fewest columns the view asks for: Claude Code and Codex lay out sensibly from here. */
        private const val MIN_COLS = 40
        private const val MIN_FIT_PX = 6
        private const val KEY_FIT = "fit_width"
        private const val KEY_ROWS = "grid_rows"
        private const val KEY_COLS = "grid_cols"
        private const val GRID_DEBOUNCE_MS = 150L
        private const val FLING_SCALE = 0.25f
        private const val MENU_COPY = 1
        private const val MENU_SELECT_ALL = 2

        /** Strips the dead-key flag from `getUnicodeChar`; combining accents are not composed here. */
        private const val CODE_POINT_MASK = 0x7FFFFFFF
    }
}
