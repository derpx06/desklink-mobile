package org.desklink.mobile.plugins.mousereceiver

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.desklink.mobile.NetworkPacket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FocusedFieldTypingTest {
    private fun packet(vararg fields: Pair<String, Any>) = NetworkPacket("desklink.mousepad.request").apply {
        fields.forEach { (k, v) -> when (v) { is String -> set(k, v); is Int -> set(k, v); is Boolean -> set(k, v); else -> error("?") } }
    }

    @Test
    fun onlyKeysMayTargetTheFocusedField() {
        val text = packet("target" to "focused-field", "key" to "héllo")
        assertTrue(FocusedFieldTyping.targetsFocusedField(text))
        assertTrue(FocusedFieldTyping.keyboardOnly(text))
        assertTrue(FocusedFieldTyping.keyboardOnly(packet("target" to "focused-field", "specialKey" to 12)))
        assertFalse(FocusedFieldTyping.keyboardOnly(packet("target" to "focused-field", "key" to "a", "singleclick" to true)))
        assertFalse(FocusedFieldTyping.keyboardOnly(packet("target" to "focused-field", "dx" to 3)))
        assertFalse(FocusedFieldTyping.keyboardOnly(packet("target" to "focused-field")))
        assertFalse(FocusedFieldTyping.targetsFocusedField(packet("key" to "a")))
    }

    @Test
    fun typingIsOffPerDeskUntilTurnedOn() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(FocusedFieldTyping.allowed(context, "deskA"))
        FocusedFieldTyping.setAllowed(context, "deskA", true)
        assertTrue(FocusedFieldTyping.allowed(context, "deskA"))
        assertFalse(FocusedFieldTyping.allowed(context, "deskB"))
    }
}
