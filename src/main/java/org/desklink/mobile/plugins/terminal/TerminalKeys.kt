/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import android.view.KeyEvent
import com.termux.terminal.KeyHandler

/** A modifier key on the key bar: tap arms it for the next key, long-press locks it. */
enum class Sticky {
    OFF,
    ARMED,
    LOCKED,
    ;

    val active: Boolean get() = this != OFF

    fun tap(): Sticky = if (this == OFF) ARMED else OFF

    fun longPress(): Sticky = if (this == LOCKED) OFF else LOCKED

    /** After a key consumed it: a one-shot disarms, a lock stays. */
    fun afterUse(): Sticky = if (this == ARMED) OFF else this
}

/**
 * Turns keys and text into the bytes a terminal program expects.
 *
 * Special keys go through Termux's `KeyHandler`, the same table xterm-compatible terminals use, so
 * application-cursor mode, modified arrows and F-keys match what vim, tmux and readline decode.
 */
object TerminalKeys {
    const val ESC = "\u001b"

    /** Bytes for a non-text key, or null if it is not one. */
    fun key(keyCode: Int, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false, cursorApp: Boolean = false, keypadApp: Boolean = false): ByteArray? {
        var mods = 0
        if (ctrl) mods = mods or KeyHandler.KEYMOD_CTRL
        if (alt) mods = mods or KeyHandler.KEYMOD_ALT
        if (shift) mods = mods or KeyHandler.KEYMOD_SHIFT
        return KeyHandler.getCode(keyCode, mods, cursorApp, keypadApp)?.toByteArray(Charsets.UTF_8)
    }

    /**
     * UTF-8 bytes for typed [text]. [ctrl] and [alt] apply to the first character only (a sticky
     * modifier is for one key): Ctrl maps it to its control code, Alt prefixes ESC so readline's
     * Alt+B / Alt+F and vim's Alt mappings work.
     */
    fun text(text: String, ctrl: Boolean = false, alt: Boolean = false): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        if (!ctrl && !alt) return text.toByteArray(Charsets.UTF_8)
        val first = text.codePointAt(0)
        val rest = text.substring(Character.charCount(first))
        val out = StringBuilder()
        if (alt) out.append(ESC)
        out.appendCodePoint(if (ctrl) controlCode(first) else first)
        out.append(rest)
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** The control code for Ctrl + [codePoint], or the character itself when it has none. */
    fun controlCode(codePoint: Int): Int = when (codePoint) {
        in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
        in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
        ' '.code, '2'.code, '@'.code -> 0
        '['.code, '3'.code -> 27
        '\\'.code, '4'.code -> 28
        ']'.code, '5'.code -> 29
        '^'.code, '6'.code -> 30
        '_'.code, '7'.code, '/'.code, '-'.code -> 31
        '8'.code, '?'.code -> 127
        else -> codePoint
    }

    val ENTER = byteArrayOf('\r'.code.toByte())
    val BACKSPACE = byteArrayOf(0x7f)

    /** F1..F12, for the key bar's second row. */
    val FUNCTION_KEYS = listOf(
        KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4,
        KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8,
        KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12,
    )
}
