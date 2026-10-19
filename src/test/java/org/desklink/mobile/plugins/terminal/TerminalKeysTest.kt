/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalKeysTest {
    private fun key(code: Int, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false, app: Boolean = false) =
        TerminalKeys.key(code, ctrl, alt, shift, cursorApp = app)?.toString(Charsets.UTF_8)

    @Test
    fun enterIsCarriageReturnAndBackspaceIsDel() {
        assertEquals("\r", key(KeyEvent.KEYCODE_ENTER))
        assertArrayEquals(byteArrayOf(0x0d), TerminalKeys.ENTER)
        assertEquals("\u007f", key(KeyEvent.KEYCODE_DEL))
        assertArrayEquals(byteArrayOf(0x7f), TerminalKeys.BACKSPACE)
        assertEquals("\u001b\u007f", key(KeyEvent.KEYCODE_DEL, alt = true))
    }

    @Test
    fun tabEscapeAndBackTab() {
        assertEquals("\t", key(KeyEvent.KEYCODE_TAB))
        assertEquals("\u001b[Z", key(KeyEvent.KEYCODE_TAB, shift = true))
        assertEquals("\u001b", key(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun arrowsFollowTheProgramsCursorKeyMode() {
        assertEquals("\u001b[A", key(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("\u001b[B", key(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("\u001b[C", key(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("\u001b[D", key(KeyEvent.KEYCODE_DPAD_LEFT))
        // vim and less switch to application mode and expect SS3.
        assertEquals("\u001bOA", key(KeyEvent.KEYCODE_DPAD_UP, app = true))
        assertEquals("\u001bOD", key(KeyEvent.KEYCODE_DPAD_LEFT, app = true))
    }

    @Test
    fun modifiedArrowsUseTheXtermParameter() {
        assertEquals("\u001b[1;5C", key(KeyEvent.KEYCODE_DPAD_RIGHT, ctrl = true))
        assertEquals("\u001b[1;3D", key(KeyEvent.KEYCODE_DPAD_LEFT, alt = true))
        assertEquals("\u001b[1;2A", key(KeyEvent.KEYCODE_DPAD_UP, shift = true))
    }

    @Test
    fun homeEndAndPaging() {
        assertEquals("\u001b[H", key(KeyEvent.KEYCODE_MOVE_HOME))
        assertEquals("\u001b[F", key(KeyEvent.KEYCODE_MOVE_END))
        assertEquals("\u001b[5~", key(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals("\u001b[6~", key(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals("\u001b[3~", key(KeyEvent.KEYCODE_FORWARD_DEL))
    }

    @Test
    fun allTwelveFunctionKeysHaveDistinctSequences() {
        val sequences = TerminalKeys.FUNCTION_KEYS.map { key(it) }
        assertEquals(12, sequences.size)
        assertEquals(12, sequences.toSet().size)
        assertEquals("\u001bOP", sequences[0])
        assertEquals("\u001b[15~", sequences[4])
        assertEquals("\u001b[24~", sequences[11])
    }

    @Test
    fun aPlainLetterIsNotASpecialKey() {
        assertNull(TerminalKeys.key(KeyEvent.KEYCODE_A))
    }

    @Test
    fun ctrlLettersBecomeControlCodes() {
        assertEquals("\u0003", String(TerminalKeys.text("c", ctrl = true)))
        assertEquals("\u0003", String(TerminalKeys.text("C", ctrl = true)))
        assertEquals("\u0004", String(TerminalKeys.text("d", ctrl = true)))
        assertEquals("\u001a", String(TerminalKeys.text("z", ctrl = true)))
        assertEquals("\u0000", String(TerminalKeys.text(" ", ctrl = true)))
        assertEquals("\u001b", String(TerminalKeys.text("[", ctrl = true)))
        assertEquals("\u001c", String(TerminalKeys.text("\\", ctrl = true)))
        assertEquals("\u001f", String(TerminalKeys.text("/", ctrl = true)))
        assertEquals("\u007f", String(TerminalKeys.text("?", ctrl = true)))
    }

    @Test
    fun altPrefixesEscape() {
        assertEquals("\u001bb", String(TerminalKeys.text("b", alt = true)))
        assertEquals("\u001b\u0003", String(TerminalKeys.text("c", ctrl = true, alt = true)))
    }

    @Test
    fun aModifierAppliesToTheFirstCharacterOnly() {
        assertEquals("\u0001bc", String(TerminalKeys.text("abc", ctrl = true)))
    }

    @Test
    fun plainTextIsUtf8IncludingSurrogatePairs() {
        assertArrayEquals("héllo 日本 😀".toByteArray(), TerminalKeys.text("héllo 日本 😀"))
        assertEquals("\u001b😀", String(TerminalKeys.text("😀", alt = true)))
        assertEquals(0, TerminalKeys.text("").size)
    }

    @Test
    fun aStickyModifierIsOneShotUnlessLocked() {
        var ctrl = Sticky.OFF
        assertFalse(ctrl.active)

        ctrl = ctrl.tap()
        assertEquals(Sticky.ARMED, ctrl)
        assertTrue(ctrl.active)
        ctrl = ctrl.afterUse()
        assertEquals(Sticky.OFF, ctrl)

        ctrl = ctrl.longPress()
        assertEquals(Sticky.LOCKED, ctrl)
        ctrl = ctrl.afterUse()
        assertEquals("a lock survives a key", Sticky.LOCKED, ctrl)
        assertEquals(Sticky.OFF, ctrl.tap())
        assertEquals(Sticky.OFF, ctrl.longPress())

        // Tapping an armed key disarms it.
        assertEquals(Sticky.OFF, Sticky.ARMED.tap())
        // Long-pressing an armed key locks it.
        assertEquals(Sticky.LOCKED, Sticky.ARMED.longPress())
    }
}
