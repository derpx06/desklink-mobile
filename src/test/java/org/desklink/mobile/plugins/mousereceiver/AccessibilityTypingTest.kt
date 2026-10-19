package org.desklink.mobile.plugins.mousereceiver

import org.desklink.mobile.plugins.mousereceiver.AccessibilityTyping.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityTypingTest {
    private fun edit(field: Field, key: String? = null, special: Int = 0, ctrl: Boolean = false) =
        AccessibilityTyping.edit(field, key, special, ctrl)

    @Test
    fun typingInsertsAtTheCaretAndReplacesASelection() {
        assertEquals(Field("hello", 5, 5), edit(Field("hell", 4, 4), "o"))
        assertEquals(Field("hXllo", 2, 2), edit(Field("hello", 1, 2), "X"))
        // A reversed selection from a node is still a selection.
        assertEquals(Field("hXllo", 2, 2), edit(Field("hello", 2, 1), "X"))
        // Out-of-range positions are clamped, not thrown.
        assertEquals(Field("b", 1, 1), edit(Field("a", -1, 99), "b"))
    }

    @Test
    fun backspaceAndDeleteRemoveOneCharacterOrTheSelection() {
        assertEquals(Field("hell", 4, 4), edit(Field("hello", 5, 5), special = 1))
        assertEquals(Field("", 0, 0), edit(Field("", 0, 0), special = 1))
        assertEquals(Field("ho", 1, 1), edit(Field("hello", 1, 4), special = 1))
        assertEquals(Field("ello", 0, 0), edit(Field("hello", 0, 0), special = 13))
        assertEquals(Field("hello", 5, 5), edit(Field("hello", 5, 5), special = 13))
        // An emoji is one character to the user, two chars in UTF-16.
        assertEquals(Field("a", 1, 1), edit(Field("a😀", 3, 3), special = 1))
    }

    @Test
    fun arrowsHomeAndEndMoveTheCaret() {
        assertEquals(Field("hello", 1, 1), edit(Field("hello", 2, 2), special = 4))
        assertEquals(Field("hello", 3, 3), edit(Field("hello", 2, 2), special = 6))
        assertEquals(Field("hello", 0, 0), edit(Field("hello", 3, 3), special = 10))
        assertEquals(Field("hello", 5, 5), edit(Field("hello", 3, 3), special = 11))
        assertEquals(Field("hello", 1, 1), edit(Field("hello", 1, 4), special = 4))
    }

    @Test
    fun ctrlASelectsAllAndUnknownKeysAreNotEdits() {
        assertEquals(Field("hello", 0, 5), edit(Field("hello", 2, 2), "a", ctrl = true))
        assertNull(edit(Field("hello", 2, 2), "z", ctrl = true))
        assertNull(edit(Field("hello", 2, 2), special = 5))
    }
}
