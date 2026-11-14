/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.inputdevicesreceiver

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The desk asks for Glide (`desklink.settings.request {set: {glide: true}}`, its "Ask on phone"): the phone shows a prompt and
 * never flips its own switch; the person decides here.
 */
@RunWith(RobolectricTestRunner::class)
class GlideAskTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val sent = mutableListOf<NetworkPacket>()

    private fun plugin(): InputDevicesReceiverPlugin {
        val device = mockk<Device>(relaxed = true)
        every { device.deviceId } returns "desk-ask"
        every { device.name } returns "Workbench"
        every { device.sendPacket(any()) } answers { sent += firstArg<NetworkPacket>() }
        return InputDevicesReceiverPlugin().also { it.setContext(app, device); it.onCreate() }
    }

    private fun ask(value: Any = true, key: String = Glide.SETTING_KEY) = NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST).apply {
        this["set"] = JSONObject().put(key, value)
    }

    private fun notices() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    @After
    fun clear() {
        Glide.setAllowed(app, "desk-ask", false)
        Glide.clearNotices("desk-ask")
    }

    @Test
    fun everyStateHasItsPrompt() {
        assertEquals(Glide.Prompt.TURN_ON, Glide.promptFor(asksToTurnOn = true, switchOn = false, serviceOn = false))
        assertEquals(Glide.Prompt.TURN_ON, Glide.promptFor(asksToTurnOn = true, switchOn = false, serviceOn = true))
        assertEquals(Glide.Prompt.ACCESSIBILITY, Glide.promptFor(asksToTurnOn = true, switchOn = true, serviceOn = false))
        assertEquals("already on: nothing to ask", Glide.Prompt.NONE, Glide.promptFor(asksToTurnOn = true, switchOn = true, serviceOn = true))
        assertEquals(Glide.Prompt.NONE, Glide.promptFor(asksToTurnOn = false, switchOn = false, serviceOn = false))
    }

    @Test
    fun onlyGlideTrueIsAnAsk() {
        assertTrue(Glide.asksToTurnOn(JSONObject().put("glide", true)))
        assertFalse(Glide.asksToTurnOn(JSONObject().put("glide", false)))
        assertFalse(Glide.asksToTurnOn(JSONObject().put("glide", "true")))
        assertFalse(Glide.asksToTurnOn(JSONObject().put("clipboard", true)))
        assertFalse(Glide.asksToTurnOn(null))
    }

    @Test
    fun theWordsSayWhoAsksAndWhatToDo() {
        val (title, text) = InputDevicesReceiverPlugin.askWords(app, Glide.Prompt.TURN_ON, "Workbench")
        assertEquals("Workbench wants to use Glide. Turn on?", title)
        assertTrue(text, text.contains("Let Workbench control this phone"))
        val (_, service) = InputDevicesReceiverPlugin.askWords(app, Glide.Prompt.ACCESSIBILITY, "Workbench")
        assertEquals(app.getString(R.string.settings_glide_accessibility, "Workbench"), service)
    }

    @Test
    fun anAskShowsAPromptAndNeverTurnsGlideOn() {
        val p = plugin()
        assertTrue("the plugin takes it", p.supportedPacketTypes.contains(DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST))
        assertTrue(p.onPacketReceived(ask()))
        assertFalse("the person decides, not the desk", Glide.allowed(app, "desk-ask"))
        val notice = notices().single()
        assertEquals("Workbench wants to use Glide. Turn on?", notice.extras.getString("android.title"))
        assertTrue("a one-tap way there", notice.contentIntent != null && notice.actions.size == 1)
        assertTrue("nothing sent back but state", sent.none { it.type == DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST })
    }

    @Test
    fun anythingElseInTheRequestShowsNothingAndChangesNothing() {
        val p = plugin()
        p.onPacketReceived(ask(key = "clipboard"))
        p.onPacketReceived(ask(value = false))
        assertTrue(notices().isEmpty())
        assertFalse(Glide.allowed(app, "desk-ask"))
    }

    @Test
    fun asksAreRateLimited() {
        val p = plugin()
        repeat(50) { p.onPacketReceived(ask()) }
        assertEquals(1, notices().size)
    }

    @Test
    fun anAskIsRememberedForHomeUntilAnswered() {
        assertFalse(Glide.asking("desk-ask"))
        plugin().onPacketReceived(ask())
        assertTrue("Home's Glide row offers Allow", Glide.asking("desk-ask"))
        Glide.setAllowed(app, "desk-ask", true)
        assertFalse("turning it on answers it", Glide.asking("desk-ask"))
        // Both notices fade after NOTICE_MS; the next glide clears a refusal at once.
        Glide.markRefusedLocked("x", now = 1_000L)
        assertTrue(Glide.refusedLocked("x", now = 1_000L + Glide.NOTICE_MS - 1))
        assertFalse(Glide.refusedLocked("x", now = 1_000L + Glide.NOTICE_MS))
        Glide.markRefusedLocked("x", now = 1_000L)
        Glide.begin("x", Glide.TOP_EDGE, null, now = 2_000L)
        Glide.end()
        assertFalse(Glide.refusedLocked("x", now = 2_000L))
    }
}
