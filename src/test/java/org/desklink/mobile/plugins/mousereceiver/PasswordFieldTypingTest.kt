/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mousereceiver

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A password field reads as bullets: the field path must never write text computed from them (it would replace the
 * password with bullets) nor paste (the typed characters would stay in the clipboard's history). Review 4, 11-16.
 */
@RunWith(RobolectricTestRunner::class)
class PasswordFieldTypingTest {
    private fun field(password: Boolean) = AccessibilityNodeInfo.obtain().apply {
        isEditable = true
        isPassword = password
        text = if (password) "•••" else "abc"
        addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
    }

    @Test
    fun aPasswordFieldIsNeverEditedFromItsBulletsNorPastedInto() {
        val node = field(password = true)
        var pasted = false
        for (special in listOf(0, 1, 13, 4)) {
            assertFalse(AccessibilityTyping.typeIntoNode(node, if (special == 0) "x" else null, special, ctrl = false, shift = false) { pasted = true; true })
        }
        assertFalse("never through the clipboard", pasted)
        assertTrue("no action at all", shadowOf(node).performedActions.isEmpty())
    }

    @Test
    fun anOrdinaryFieldIsStillEdited() {
        val node = field(password = false)
        AccessibilityTyping.typeIntoNode(node, "x", 0, ctrl = false, shift = false) { true }
        assertTrue(shadowOf(node).performedActions.contains(AccessibilityNodeInfo.ACTION_SET_TEXT))
    }
}
