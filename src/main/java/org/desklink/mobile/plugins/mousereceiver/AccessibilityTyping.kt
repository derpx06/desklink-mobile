/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.mousereceiver

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.desklink.mobile.NetworkPacket

/**
 * Typing from the desktop into whatever text field has focus, through the
 * accessibility service — so it works with any keyboard. The remote-keyboard
 * path needs DeskLink's own IME to be the selected keyboard; with Gboard (or
 * any other) active every key was dropped.
 */
object AccessibilityTyping {
    /** Desktop `specialKey` codes (`map_gdk_key_to_special`). */
    private const val BACKSPACE = 1
    private const val LEFT = 4
    private const val UP = 5
    private const val RIGHT = 6
    private const val DOWN = 7
    private const val HOME = 10
    private const val END = 11
    private const val ENTER = 12
    private const val DELETE = 13
    private const val ESCAPE = 14

    data class Field(val text: String, val start: Int, val end: Int)

    /**
     * The field after one key, or null when the key is not a text edit.
     * `start`/`end` are the selection (equal for a caret); out-of-range or
     * reversed values from a node are normalised first.
     */
    fun edit(field: Field, key: String?, special: Int, ctrl: Boolean): Field? {
        val length = field.text.length
        val a = field.start.coerceIn(0, length)
        val b = field.end.coerceIn(0, length)
        val (start, end) = if (a <= b) a to b else b to a
        val text = field.text
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
                    val from = previousCodePoint(text, start)
                    Field(text.removeRange(from, start), from, from)
                }
                else -> field.copy(start = 0, end = 0)
            }
            DELETE -> when {
                start != end -> Field(text.removeRange(start, end), start, start)
                start < length -> Field(text.removeRange(start, nextCodePoint(text, start)), start, start)
                else -> Field(text, start, start)
            }
            LEFT -> {
                val caret = if (start != end) start else if (start > 0) previousCodePoint(text, start) else 0
                Field(text, caret, caret)
            }
            RIGHT -> {
                val caret = if (start != end) end else if (end < length) nextCodePoint(text, end) else length
                Field(text, caret, caret)
            }
            HOME -> Field(text, 0, 0)
            END -> Field(text, length, length)
            else -> null
        }
    }

    private fun previousCodePoint(text: String, index: Int) =
        if (index >= 2 && Character.isLowSurrogate(text[index - 1]) && Character.isHighSurrogate(text[index - 2])) index - 2 else index - 1

    private fun nextCodePoint(text: String, index: Int) =
        if (index + 1 < text.length && Character.isHighSurrogate(text[index]) && Character.isLowSurrogate(text[index + 1])) index + 2 else index + 1

    /** Applies a mousepad key packet to the focused field. False if nothing took it. */
    fun handle(np: NetworkPacket): Boolean {
        val service = MouseReceiverService.instance ?: return false
        val special = np.getInt("specialKey", 0)
        val key = np.getString("key", "").takeIf { it.isNotEmpty() }
        val ctrl = np.getBoolean("ctrl", false)

        if (special == ESCAPE) return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        val node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        try {
            if (ctrl && key != null) {
                val action = when (key.lowercase()) {
                    "c" -> AccessibilityNodeInfo.ACTION_COPY
                    "x" -> AccessibilityNodeInfo.ACTION_CUT
                    "v" -> AccessibilityNodeInfo.ACTION_PASTE
                    else -> 0
                }
                if (action != 0) return node.performAction(action)
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
            if (special == UP || special == DOWN) {
                // A line up or down: what the arrow means in a multi-line field.
                val arguments = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE)
                    putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false)
                }
                val action = if (special == UP) AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY else AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                return node.performAction(action, arguments)
            }
            if (apply(node, key, special, ctrl)) return true
            // A field that refuses SET_TEXT (some web views, custom editors) still takes a paste.
            return key != null && special == 0 && !ctrl && paste(service, node, key)
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    /** Through the clipboard, as the desk's own copy so it is never sent back. */
    private fun paste(service: AccessibilityService, node: AccessibilityNodeInfo, text: String): Boolean {
        org.desklink.mobile.plugins.clipboard.ClipboardListener.instance(service).setText(text)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun apply(node: AccessibilityNodeInfo, key: String?, special: Int, ctrl: Boolean): Boolean {
        // An empty field reports its hint as its text.
        val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
        val current = if (showingHint) "" else node.text?.toString().orEmpty()
        val before = Field(current, node.textSelectionStart.takeIf { it >= 0 } ?: current.length,
            node.textSelectionEnd.takeIf { it >= 0 } ?: current.length)
        val after = edit(before, key, special, ctrl) ?: return false
        if (after.text != before.text) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, after.text)
            }
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return false
        }
        // SET_TEXT puts the caret at the end; put it where the edit left it.
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, after.start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, after.end)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        return true
    }
}
