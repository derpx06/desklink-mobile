/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.mousereceiver

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol

/**
 * Typing from the desktop into whatever text field has focus, through the
 * accessibility service — so it works with any keyboard. The remote-keyboard
 * path needs DeskLink's own IME to be the selected keyboard; with Gboard (or
 * any other) active every key was dropped.
 *
 * On Android 13+ the service is also an input method for the focused editor
 * (`flagInputMethodEditor`): text is committed and keys are sent as a keyboard
 * sends them, so web views, Compose fields, search boxes, Tab and IME actions
 * work. Older phones (or no editor connected) edit the focused node instead.
 */
object AccessibilityTyping {
    /** Desktop `specialKey` codes (`map_gdk_key_to_special`). */
    private const val BACKSPACE = 1
    private const val TAB = 2
    private const val LEFT = 4
    private const val UP = 5
    private const val RIGHT = 6
    private const val DOWN = 7
    private const val PAGE_UP = 8
    private const val PAGE_DOWN = 9
    private const val HOME = 10
    private const val END = 11
    private const val ENTER = 12
    private const val DELETE = 13
    private const val ESCAPE = 14
    private const val F1 = 21
    private const val F12 = 32

    /** Why a key reached no text field: the `reason` of this phone's `input-focus` report to the desk. */
    val REFUSALS = listOf(
        DeskLinkProtocol.TYPING_ACCESSIBILITY_OFF,
        DeskLinkProtocol.TYPING_NO_TEXT_FIELD,
        DeskLinkProtocol.TYPING_NOT_EDITABLE,
        DeskLinkProtocol.TYPING_TEXT_REFUSED,
        DeskLinkProtocol.TYPING_LOCKED,
    )

    data class Field(val text: String, val start: Int, val end: Int)

    /** The selection this path last set, with its direction (the field reports it in order), and which field it was in. */
    @Volatile
    private var lastSet: Pair<Int, Field>? = null

    /**
     * The field as [reported], with the direction of the selection this path set last when it is still that one: a
     * selection made with Shift+Left is reported in order, and the next Shift+Left must move its left end.
     */
    fun oriented(reported: Field, lastSet: Field?): Field =
        if (lastSet != null && lastSet.text == reported.text &&
            minOf(lastSet.start, lastSet.end) == minOf(reported.start, reported.end) &&
            maxOf(lastSet.start, lastSet.end) == maxOf(reported.start, reported.end)
        ) lastSet else reported

    /** One thing done through the focused editor's input connection. */
    sealed interface Step {
        data class Commit(val text: String) : Step
        data class Key(val code: Int, val meta: Int) : Step
        data class EditorAction(val action: Int) : Step
    }

    /**
     * What one desk key does to an editor, as a hardware keyboard would; null for a key that means nothing.
     * Enter in a single-line field runs the field's own action (Search, Go, Send...) when it has one.
     */
    fun steps(key: String?, special: Int, ctrl: Boolean, shift: Boolean, alt: Boolean, imeOptions: Int, inputType: Int): List<Step>? {
        val meta = (if (ctrl) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0) or
            (if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0) or
            (if (alt) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0)
        if (special != 0) {
            if (special == ENTER && meta == 0) {
                val action = imeOptions and EditorInfo.IME_MASK_ACTION
                val multiLine = inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
                val noEnterAction = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
                if (!multiLine && !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    return listOf(Step.EditorAction(action))
                }
            }
            val code = when (special) {
                BACKSPACE -> KeyEvent.KEYCODE_DEL
                TAB -> KeyEvent.KEYCODE_TAB
                LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                UP -> KeyEvent.KEYCODE_DPAD_UP
                RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
                PAGE_UP -> KeyEvent.KEYCODE_PAGE_UP
                PAGE_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
                HOME -> KeyEvent.KEYCODE_MOVE_HOME
                END -> KeyEvent.KEYCODE_MOVE_END
                ENTER -> KeyEvent.KEYCODE_ENTER
                DELETE -> KeyEvent.KEYCODE_FORWARD_DEL
                in F1..F12 -> KeyEvent.KEYCODE_F1 + (special - F1)
                else -> return null
            }
            return listOf(Step.Key(code, meta))
        }
        if (key.isNullOrEmpty()) return null
        // A shortcut (Ctrl+A/C/X/V/Z, Alt+...) is a key with its modifiers; text is committed as it is, in any script.
        if (ctrl || alt) {
            val c = key.singleOrNull()?.lowercaseChar() ?: return null
            val code = when (c) {
                in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
                in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
                else -> return null
            }
            return listOf(Step.Key(code, meta))
        }
        return listOf(Step.Commit(key))
    }

    /**
     * The field after one key, or null when the key is not a text edit.
     * `start`/`end` are the selection (equal for a caret); out-of-range or
     * reversed values from a node are normalised first.
     */
    fun edit(field: Field, key: String?, special: Int, ctrl: Boolean, shift: Boolean = false): Field? {
        val length = field.text.length
        val a = field.start.coerceIn(0, length)
        val b = field.end.coerceIn(0, length)
        val (start, end) = if (a <= b) a to b else b to a
        val text = field.text
        // Shift moves the selection's moving end (`end`); its anchor (`start`) stays.
        if (shift && special in listOf(LEFT, RIGHT, HOME, END)) {
            val to = when (special) {
                LEFT -> if (b > 0) previousCharacter(text, b) else 0
                RIGHT -> if (b < length) nextCharacter(text, b) else length
                HOME -> 0
                else -> length
            }
            return Field(text, a, to)
        }
        if (ctrl && key != null) {
            return if (key.equals("a", ignoreCase = true)) Field(text, 0, length) else null
        }
        if (key != null && special == 0) {
            val inserted = text.substring(0, start) + key + text.substring(end)
            val caret = start + key.length
            return Field(inserted, caret, caret)
        }
        return when (special) {
            BACKSPACE -> when {
                start != end -> Field(text.removeRange(start, end), start, start)
                start > 0 -> {
                    val from = previousCharacter(text, start)
                    Field(text.removeRange(from, start), from, from)
                }
                else -> field.copy(start = 0, end = 0)
            }
            DELETE -> when {
                start != end -> Field(text.removeRange(start, end), start, start)
                start < length -> Field(text.removeRange(start, nextCharacter(text, start)), start, start)
                else -> Field(text, start, start)
            }
            LEFT -> {
                val caret = if (start != end) start else if (start > 0) previousCharacter(text, start) else 0
                Field(text, caret, caret)
            }
            RIGHT -> {
                val caret = if (start != end) end else if (end < length) nextCharacter(text, end) else length
                Field(text, caret, caret)
            }
            HOME -> Field(text, 0, 0)
            END -> Field(text, length, length)
            else -> null
        }
    }

    /** What rides on the emoji before it: a skin tone, an emoji or text variation, a keycap, a flag tag. */
    private fun extends(codePoint: Int) =
        codePoint in 0x1F3FB..0x1F3FF || codePoint == 0xFE0F || codePoint == 0xFE0E || codePoint == 0x20E3 || codePoint in 0xE0020..0xE007F

    /** An emoji picture (ponytail: the emoji blocks, not Unicode's Extended_Pictographic table). */
    private fun pictographic(codePoint: Int) =
        codePoint in 0x1F000..0x1FAFF || codePoint in 0x2600..0x27BF || codePoint in 0x2300..0x23FF || codePoint in 0x2B00..0x2BFF

    /** Part of an emoji a zero-width joiner may join from. */
    private fun emojiPart(codePoint: Int) = pictographic(codePoint) || extends(codePoint)

    private fun regionalIndicator(codePoint: Int) = codePoint in 0x1F1E6..0x1F1FF

    /** How many regional indicators (two chars each) end right at [index]. */
    private fun indicatorsBefore(text: String, index: Int): Int {
        var count = 0
        var at = index
        while (at >= 2 && regionalIndicator(text.codePointBefore(at))) {
            count++
            at -= 2
        }
        return count
    }

    /**
     * Where the character before [index] starts: one code point (an Indic vowel sign or half form goes alone, as with
     * Android's own Backspace), but a whole emoji: what rides on it, a flag's two indicators, and an emoji a zero-width
     * joiner joins to it (only between emoji: in Devanagari a ZWJ shapes a half form and joins nothing).
     */
    private fun previousCharacter(text: String, index: Int): Int {
        val indicators = indicatorsBefore(text, index)
        if (indicators >= 2 && indicators % 2 == 0) return index - 4
        var i = previousCodePoint(text, index)
        while (i > 0) {
            val codePoint = text.codePointAt(i)
            if (extends(codePoint)) {
                i = previousCodePoint(text, i)
                continue
            }
            if (pictographic(codePoint) && text.codePointBefore(i) == ZWJ) {
                val joiner = previousCodePoint(text, i)
                if (joiner > 0 && emojiPart(text.codePointBefore(joiner))) {
                    i = previousCodePoint(text, joiner)
                    continue
                }
            }
            return i
        }
        return i.coerceAtLeast(0)
    }

    /** Where the character at [index] ends: the mirror of [previousCharacter]. */
    private fun nextCharacter(text: String, index: Int): Int {
        if (index + 4 <= text.length && regionalIndicator(text.codePointAt(index)) &&
            regionalIndicator(text.codePointAt(index + 2)) && indicatorsBefore(text, index) % 2 == 0
        ) return index + 4
        var j = nextCodePoint(text, index)
        while (j < text.length) {
            val codePoint = text.codePointAt(j)
            if (extends(codePoint)) {
                j = nextCodePoint(text, j)
                continue
            }
            val after = nextCodePoint(text, j)
            if (codePoint == ZWJ && after < text.length && pictographic(text.codePointAt(after)) && emojiPart(text.codePointBefore(j))) {
                j = nextCodePoint(text, after)
                continue
            }
            return j
        }
        return j
    }

    private const val ZWJ = 0x200D

    private fun previousCodePoint(text: String, index: Int) =
        if (index >= 2 && Character.isLowSurrogate(text[index - 1]) && Character.isHighSurrogate(text[index - 2])) index - 2 else index - 1

    private fun nextCodePoint(text: String, index: Int) =
        if (index + 1 < text.length && Character.isHighSurrogate(text[index]) && Character.isLowSurrogate(text[index + 1])) index + 2 else index + 1

    /** Why no key may be typed at all now, before any field is looked at: no service, or a locked phone. */
    fun refusalBefore(serviceRunning: Boolean, locked: Boolean): String? = when {
        !serviceRunning -> DeskLinkProtocol.TYPING_ACCESSIBILITY_OFF
        // Never into the lock screen (its PIN field, its notifications' replies), Glide or Phone Screen alike.
        locked -> DeskLinkProtocol.TYPING_LOCKED
        else -> null
    }

    /** Applies a mousepad key packet to the focused field: null when it was typed, else why not (one of [REFUSALS]). */
    fun handle(np: NetworkPacket): String? {
        val service = MouseReceiverService.instance ?: return refusalBefore(serviceRunning = false, locked = false)
        refusalBefore(serviceRunning = true, locked = org.desklink.mobile.helpers.LockedActions.isLocked(service))?.let { return it }
        val special = np.getInt("specialKey", 0)
        val key = np.getString("key", "").takeIf { it.isNotEmpty() }
        val ctrl = np.getBoolean("ctrl", false)

        if (special == ESCAPE) {
            return if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) null else DeskLinkProtocol.TYPING_TEXT_REFUSED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (typeIntoEditor(service, np, key, special, ctrl)) {
                true -> return null
                false -> return DeskLinkProtocol.TYPING_TEXT_REFUSED
                null -> Unit // no editor connected: the focused node says why
            }
        }
        val node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return DeskLinkProtocol.TYPING_NO_TEXT_FIELD
        try {
            return when {
                typeIntoNode(node, key, special, ctrl, np.getBoolean("shift", false)) { paste(service, node, it) } -> null
                !node.isEditable -> DeskLinkProtocol.TYPING_NOT_EDITABLE
                else -> DeskLinkProtocol.TYPING_TEXT_REFUSED
            }
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    /**
     * Through the editor's own input connection, as a keyboard types. Null when no editor is connected (nothing
     * focused takes text); true when sent, false for a key that means nothing to an editor.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun typeIntoEditor(service: AccessibilityService, np: NetworkPacket, key: String?, special: Int, ctrl: Boolean): Boolean? {
        val ime = service.inputMethod ?: return null
        val info = ime.currentInputEditorInfo ?: return null
        val connection = ime.currentInputConnection ?: return null
        val steps = steps(key, special, ctrl, np.getBoolean("shift", false), np.getBoolean("alt", false), info.imeOptions, info.inputType)
            ?: return false
        // A window with no editor focused still starts input, as a field that takes no text (TYPE_NULL): keys go to
        // what has input focus (Enter presses a button, a raw-key view reads them), text only to an editable one.
        if (info.inputType == InputType.TYPE_NULL) {
            val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
            val editable = focused.isEditable
            @Suppress("DEPRECATION")
            focused.recycle()
            if (!nullEditorTakes(steps, editable)) return null
        } else if (staleEditors.stale(info) { connection.getSurroundingText(0, 0, 0) == null }) {
            // A stale session: an editor that restarts input after an edit (Chrome's address bar, after every key) gets a
            // new session that Android never hands this service, and the app drops every later call ("Session id
            // mismatch") while each looks sent. A stale connection answers nothing, so it is asked (once per editor):
            // the focused node is edited instead (emulator, Android 16, 11-16).
            return null
        }
        for (step in steps) when (step) {
            is Step.Commit -> connection.commitText(step.text, 1, null)
            is Step.EditorAction -> connection.performEditorAction(step.action)
            is Step.Key -> {
                val down = SystemClock.uptimeMillis()
                val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
                connection.sendKeyEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, step.code, 0, step.meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
                connection.sendKeyEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, step.code, 0, step.meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
            }
        }
        return true
    }

    /** Whether a TYPE_NULL editor is typed into: keys always, text only when what has input focus is editable. */
    fun nullEditorTakes(steps: List<Step>, focusedEditable: Boolean): Boolean = focusedEditable || steps.none { it is Step.Commit }

    private val staleEditors = StaleEditors { android.util.Log.d("MouseReceiverPlugin", "keys: the editor's input connection is stale, editing the focused field") }

    /**
     * Which editor's connection was found stale: asked once per editor (a synchronous call into the app), remembered
     * until the focus moves (Android hands over a new editor then, and its connection is asked afresh).
     */
    class StaleEditors(private val found: () -> Unit = {}) {
        @Volatile
        private var stale: Any? = null

        fun stale(editor: Any, ask: () -> Boolean): Boolean {
            if (editor === stale) return true
            if (!ask()) return false
            stale = editor
            found()
            return true
        }
    }

    /**
     * Older phones, or no editor connected: actions on the focused node. False if nothing took the key. [paste] puts
     * text in through the clipboard, for a field that refuses SET_TEXT.
     */
    internal fun typeIntoNode(
        node: AccessibilityNodeInfo, key: String?, special: Int, ctrl: Boolean, shift: Boolean, paste: (String) -> Boolean,
    ): Boolean {
        if (ctrl && key != null) {
            val action = when (key.lowercase()) {
                "c" -> AccessibilityNodeInfo.ACTION_COPY
                "x" -> AccessibilityNodeInfo.ACTION_CUT
                "v" -> AccessibilityNodeInfo.ACTION_PASTE
                else -> 0
            }
            if (action != 0) return node.performAction(action)
        }
        // Tab moves the focus, as a keyboard's Tab does (Shift+Tab back).
        if (special == TAB) {
            val next = node.focusSearch(if (shift) android.view.View.FOCUS_BACKWARD else android.view.View.FOCUS_FORWARD) ?: return false
            try {
                return next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } finally {
                @Suppress("DEPRECATION")
                next.recycle()
            }
        }
        if (special == ENTER) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            ) return true
            // No IME action: a newline, which is what Enter means in a multi-line field.
            if (!node.isMultiLine) return false
            return apply(node, key = "\n", special = 0, ctrl = false)
        }
        if (!node.isEditable) return false
        // A password field reads as bullets: an edit computed from them would replace the password with bullets, and a
        // paste would leave the typed characters in the clipboard's history. Only its own editor connection types there.
        if (node.isPassword) return false
        if (special == UP || special == DOWN) {
            // A line up or down: what the arrow means in a multi-line field.
            val arguments = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE)
                putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false)
            }
            val action = if (special == UP) AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY else AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
            return node.performAction(action, arguments)
        }
        if (apply(node, key, special, ctrl, shift)) return true
        // A field that refuses SET_TEXT (some web views, custom editors) still takes a paste.
        return key != null && special == 0 && !ctrl && paste(key)
    }

    /** Through the clipboard, as the desk's own copy so it is never sent back. */
    private fun paste(service: AccessibilityService, node: AccessibilityNodeInfo, text: String): Boolean {
        org.desklink.mobile.plugins.clipboard.ClipboardListener.instance(service).setText(text)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun apply(node: AccessibilityNodeInfo, key: String?, special: Int, ctrl: Boolean, shift: Boolean = false): Boolean {
        // An empty field reports its hint as its text.
        val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
        val current = if (showingHint) "" else node.text?.toString().orEmpty()
        val reported = Field(current, node.textSelectionStart.takeIf { it >= 0 } ?: current.length,
            node.textSelectionEnd.takeIf { it >= 0 } ?: current.length)
        val owner = node.hashCode() // the node's window and id: another field never inherits a direction
        val before = oriented(reported, lastSet?.takeIf { it.first == owner }?.second)
        val after = edit(before, key, special, ctrl, shift) ?: return false
        if (after.text != before.text) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, after.text)
            }
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return false
        }
        // SET_TEXT puts the caret at the end; put it where the edit left it. In order: a TextView refuses start > end.
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, minOf(after.start, after.end))
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, maxOf(after.start, after.end))
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        lastSet = owner to after
        return true
    }
}
