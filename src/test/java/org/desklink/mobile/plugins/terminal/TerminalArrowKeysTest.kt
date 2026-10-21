/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** The key bar's arrows: plain, application-cursor mode, and with sticky Ctrl/Alt (xterm modifiers). */
class TerminalArrowKeysTest {
    private fun bytes(keyCode: Int, ctrl: Boolean = false, alt: Boolean = false, cursorApp: Boolean = false) =
        TerminalKeys.key(keyCode, ctrl = ctrl, alt = alt, cursorApp = cursorApp)?.toString(Charsets.UTF_8)

    @Test
    fun arrowsFollowXterm() {
        assertEquals("\u001b[D", bytes(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("\u001b[A", bytes(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("\u001b[B", bytes(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("\u001b[C", bytes(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("\u001bOA", bytes(KeyEvent.KEYCODE_DPAD_UP, cursorApp = true))
        assertEquals("\u001b[1;5D", bytes(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = true))
        assertEquals("\u001b[1;3C", bytes(KeyEvent.KEYCODE_DPAD_RIGHT, alt = true))
        assertEquals("\u001b[1;7A", bytes(KeyEvent.KEYCODE_DPAD_UP, ctrl = true, alt = true))
    }
}
