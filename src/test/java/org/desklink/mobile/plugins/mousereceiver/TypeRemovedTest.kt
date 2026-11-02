/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mousereceiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The desk's "Type" button is gone; what Glide types with is not. */
class TypeRemovedTest {
    private fun source(path: String) = File("src/main/java/org/desklink/mobile/$path").readText()

    @Test
    fun noTypeOnPhoneSwitchPathOrNotificationIsLeft() {
        assertFalse(File("src/main/java/org/desklink/mobile/plugins/mousereceiver/FocusedFieldTyping.kt").exists())
        assertFalse(File("src/main/java/org/desklink/mobile/plugins/mousereceiver/TypeOnPhoneAllowReceiver.kt").exists())
        val everything = File("src/main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml") && "values-" !in it.path }.joinToString("\n") { it.readText() }
        for (word in listOf("FocusedFieldTyping", "TypeOnPhoneAllowReceiver", "settings_type_on_phone", "type_on_phone_ask", "focused-field")) {
            assertFalse("$word is still referred to", everything.contains(word))
        }
        assertEquals("the plugin no longer reports a focused field", true, source("plugins/mousereceiver/MouseReceiverPlugin.kt").contains("outgoingPacketTypes: Array<String> = emptyArray()"))
    }

    @Test
    fun glideStillTypesIntoTheFocusedFieldWhileThePointerIsOnThePhone() {
        val plugin = source("plugins/mousereceiver/MouseReceiverPlugin.kt")
        val glideKeys = plugin.substringAfter("F4 glide: while the desk's pointer is here")
        assertTrue(glideKeys.contains("Glide.isGliding(device.deviceId) && (np.has(\"key\") || np.has(\"specialKey\"))"))
        assertTrue(glideKeys.substringBefore("return false").contains("AccessibilityTyping.handle(np)"))
        // Its consent is the glide switch alone (the packet gate in the coordinator).
        val gate = source("webrtc/WebRtcSessionCoordinator.kt").substringAfter("internal fun deliverFeaturePacket").substringBefore("verifyInboundInput")
        assertTrue(gate.contains("glide.isGliding(device.deviceId) && glide.allowed(context, device.deviceId)"))
    }

    @Test
    fun theKeyboardStateReportIsNoLongerAPacketTypeOfTheActiveProtocol() {
        assertFalse(source("protocol/desklinkv9/DeskLinkProtocol.kt").contains("keyboardstate"))
        assertFalse(org.desklink.mobile.webrtc.WebRtcFeatureProfile.allows("desklink.mousepad.keyboardstate"))
        assertTrue("and the glide packet stays", org.desklink.mobile.webrtc.WebRtcFeatureProfile.allows("desklink.mousepad.request"))
    }
}
