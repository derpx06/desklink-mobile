package org.desklink.mobile.plugins.mousereceiver

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.desklink.mobile.plugins.mousereceiver.AccessibilityTyping.Field
import org.desklink.mobile.plugins.mousereceiver.AccessibilityTyping.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityTypingTest {
    private fun edit(field: Field, key: String? = null, special: Int = 0, ctrl: Boolean = false, shift: Boolean = false) =
        AccessibilityTyping.edit(field, key, special, ctrl, shift)

    /** A whole emoji goes and is stepped over (skin tone, joiners, variation); an Indic vowel sign goes alone, as Android's Backspace. */
    @Test
    fun backspaceDeleteAndArrowsTakeAWholeEmoji() {
        val thumb = "a\uD83D\uDC4D\uD83C\uDFFD" // a + thumbs up + medium skin tone
        assertEquals(Field("a", 1, 1), edit(Field(thumb, thumb.length, thumb.length), special = 1))
        assertEquals(Field("a", 1, 1), edit(Field(thumb, 1, 1), special = 13))
        assertEquals(Field(thumb, 1, 1), edit(Field(thumb, thumb.length, thumb.length), special = 4))
        assertEquals(Field(thumb, thumb.length, thumb.length), edit(Field(thumb, 1, 1), special = 6))
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69" // man ZWJ woman
        assertEquals(Field("", 0, 0), edit(Field(family, family.length, family.length), special = 1))
        val heart = "x\u2764\uFE0F"
        assertEquals(Field("x", 1, 1), edit(Field(heart, heart.length, heart.length), special = 1))
        assertEquals(Field("मराठ", 4, 4), edit(Field("मराठी", 5, 5), special = 1))
    }

    /** A field reports a selection in order; the one this path made keeps its direction while it is still the same. */
    @Test
    fun aSelectionKeepsTheDirectionItWasMadeIn() {
        val madeLeftward = Field("hello", 3, 1)
        assertEquals(madeLeftward, AccessibilityTyping.oriented(Field("hello", 1, 3), madeLeftward))
        // Shift+Left again moves the left end.
        assertEquals(Field("hello", 3, 0), edit(AccessibilityTyping.oriented(Field("hello", 1, 3), madeLeftward), special = 4, shift = true))
        // Changed since (a tap, typing on the phone): the field's own word.
        assertEquals(Field("hello", 2, 4), AccessibilityTyping.oriented(Field("hello", 2, 4), madeLeftward))
        assertEquals(Field("hallo", 1, 3), AccessibilityTyping.oriented(Field("hallo", 1, 3), madeLeftward))
        assertEquals(Field("hello", 1, 3), AccessibilityTyping.oriented(Field("hello", 1, 3), null))
    }

    /** Shift with an arrow, Home or End moves one end of the selection; the other stays where it was. */
    @Test
    fun shiftExtendsTheSelection() {
        assertEquals(Field("hello", 3, 2), edit(Field("hello", 3, 3), special = 4, shift = true))
        assertEquals(Field("hello", 3, 1), edit(Field("hello", 3, 2), special = 4, shift = true))
        assertEquals(Field("hello", 3, 4), edit(Field("hello", 3, 3), special = 6, shift = true))
        assertEquals(Field("hello", 3, 0), edit(Field("hello", 3, 3), special = 10, shift = true))
        assertEquals(Field("hello", 3, 5), edit(Field("hello", 3, 3), special = 11, shift = true))
        // Then Backspace removes what is selected, whichever way it was made.
        assertEquals(Field("hlo", 1, 1), edit(Field("hello", 3, 1), special = 1))
    }

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

    private val singleLineSearch = EditorInfo.IME_ACTION_SEARCH
    private val text = InputType.TYPE_CLASS_TEXT
    private fun steps(key: String? = null, special: Int = 0, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false,
                      imeOptions: Int = 0, inputType: Int = text) =
        AccessibilityTyping.steps(key, special, ctrl, shift, alt, imeOptions, inputType)

    /** Through the editor's connection: text in any script is committed whole, as typed. */
    @Test
    fun textInAnyScriptIsCommittedAsItIs() {
        for (typed in listOf("a", "A", " ", "नमस्कार", "मराठी", "😀", "👍🏽", "é")) {
            assertEquals(typed, listOf(Step.Commit(typed)), steps(typed))
        }
        assertNull(steps(""))
        assertNull(steps(null))
    }

    @Test
    fun specialKeysAreTheKeysAKeyboardSends() {
        fun key(special: Int) = (steps(special = special)!!.single() as Step.Key).code
        assertEquals(KeyEvent.KEYCODE_DEL, key(1))
        assertEquals(KeyEvent.KEYCODE_TAB, key(2))
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, key(4))
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, key(5))
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, key(6))
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, key(7))
        assertEquals(KeyEvent.KEYCODE_PAGE_UP, key(8))
        assertEquals(KeyEvent.KEYCODE_PAGE_DOWN, key(9))
        assertEquals(KeyEvent.KEYCODE_MOVE_HOME, key(10))
        assertEquals(KeyEvent.KEYCODE_MOVE_END, key(11))
        assertEquals(KeyEvent.KEYCODE_ENTER, key(12))
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL, key(13))
        assertEquals(KeyEvent.KEYCODE_F5, key(25))
        assertNull("Escape is Back, never a key here; unknown codes are nothing", steps(special = 99))
    }

    @Test
    fun shortcutsCarryTheirModifiers() {
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        for ((letter, code) in listOf("a" to KeyEvent.KEYCODE_A, "c" to KeyEvent.KEYCODE_C, "v" to KeyEvent.KEYCODE_V, "x" to KeyEvent.KEYCODE_X, "Z" to KeyEvent.KEYCODE_Z)) {
            assertEquals(letter, listOf(Step.Key(code, ctrl)), steps(letter, ctrl = true))
        }
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        assertEquals("Shift+Left extends the selection", listOf(Step.Key(KeyEvent.KEYCODE_DPAD_LEFT, shift)), steps(special = 4, shift = true))
        assertEquals(listOf(Step.Key(KeyEvent.KEYCODE_DEL, ctrl)), steps(special = 1, ctrl = true))
        assertNull("no key for Ctrl+a symbol", steps("€", ctrl = true))
    }

    @Test
    fun enterRunsASingleLineFieldsActionAndIsANewlineOrKeyOtherwise() {
        assertEquals(listOf(Step.EditorAction(EditorInfo.IME_ACTION_SEARCH)), steps(special = 12, imeOptions = singleLineSearch))
        assertEquals(listOf(Step.EditorAction(EditorInfo.IME_ACTION_GO)), steps(special = 12, imeOptions = EditorInfo.IME_ACTION_GO))
        val enter = listOf(Step.Key(KeyEvent.KEYCODE_ENTER, 0))
        assertEquals("a message box: a new line", enter, steps(special = 12, imeOptions = EditorInfo.IME_ACTION_SEND, inputType = text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertEquals(enter, steps(special = 12, imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION))
        assertEquals(enter, steps(special = 12, imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED))
        assertEquals(enter, steps(special = 12, imeOptions = EditorInfo.IME_ACTION_NONE))
    }
}
