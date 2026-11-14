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
        val keys = plugin.substringAfter("Keys go to the focused field while the desk's pointer glides here")
        assertTrue(keys.contains("Glide.isGliding(device.deviceId)"))
        assertTrue(keys.substringBefore("val dx").contains("if (gliding || takesKeys(snapshot)) AccessibilityTyping.handle(np) else false"))
        // Its consent is the glide switch alone (the packet gate in the coordinator).
        val gate = source("webrtc/WebRtcSessionCoordinator.kt").substringAfter("internal fun deliverFeaturePacket").substringBefore("verifyInboundInput")
        assertTrue(gate.contains("glide.isGliding(device.deviceId) && glide.allowed(context, device.deviceId)"))
    }

    /**
     * The desk's Phone Screen window types into the phone while it holds control of the phone's shared screen (a lease
     * checked by `verifyInboundInput` before any plugin sees the packet); keys were dropped there (owner, 11-14).
     */
    @Test
    fun theDeskControllingThisSharedScreenTypesIntoTheFocusedField() {
        fun snapshot(lease: Boolean, direction: org.desklink.mobile.webrtc.WebRtcScreenDirection?) =
            org.desklink.mobile.webrtc.WebRtcRemoteSessionController.Snapshot(
                org.desklink.mobile.webrtc.WebRtcRemoteSessionState.CONTROLLING, "s", direction, lease,
            )
        val phoneScreen = org.desklink.mobile.webrtc.WebRtcScreenDirection.PHONE_TO_DESKTOP
        assertTrue(MouseReceiverPlugin.takesKeys(snapshot(true, phoneScreen)))
        assertFalse("no control, no typing", MouseReceiverPlugin.takesKeys(snapshot(false, phoneScreen)))
        assertFalse("the phone viewing the desk is not the desk typing here",
            MouseReceiverPlugin.takesKeys(snapshot(true, org.desklink.mobile.webrtc.WebRtcScreenDirection.DESKTOP_TO_PHONE)))
        assertFalse(MouseReceiverPlugin.takesKeys(null))
    }

    @Test
    fun theKeyboardStateReportIsNoLongerAPacketTypeOfTheActiveProtocol() {
        assertFalse(source("protocol/desklinkv9/DeskLinkProtocol.kt").contains("keyboardstate"))
        assertFalse(org.desklink.mobile.webrtc.WebRtcFeatureProfile.allows("desklink.mousepad.keyboardstate"))
        assertTrue("and the glide packet stays", org.desklink.mobile.webrtc.WebRtcFeatureProfile.allows("desklink.mousepad.request"))
    }
}
