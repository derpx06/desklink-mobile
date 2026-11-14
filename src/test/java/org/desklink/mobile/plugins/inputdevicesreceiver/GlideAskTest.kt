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
    fun clear() = Glide.setAllowed(app, "desk-ask", false)

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
        assertTrue("a tap opens the switch", notice.contentIntent != null)
        assertEquals(listOf("Allow", "Not now"), notice.actions.map { it.title.toString() })
        assertTrue("nothing sent back but state", sent.none { it.type == DeskLinkProtocol.PACKET_TYPE_SETTINGS_REQUEST })
    }

    /**
     * The prompt must reach the person over whatever app is in front: a high-importance channel and priority (heads-up), never
     * an activity started from the background, and it goes away by itself when the desk stops waiting.
     */
    @Test
    fun thePromptIsAHeadsUpThatTimesOutWithTheDesk() {
        plugin().onPacketReceived(ask())
        val notice = notices().single()
        assertEquals(org.desklink.mobile.helpers.NotificationHelper.Channels.REQUESTS, notice.channelId)
        @Suppress("DEPRECATION")
        assertEquals(android.app.Notification.PRIORITY_HIGH, notice.priority)
        assertEquals(InputDevicesReceiverPlugin.ASK_TIMEOUT_MS, notice.timeoutAfter)
        assertEquals("the desk waits as long (glide::ASK_WAIT)", 60_000L, InputDevicesReceiverPlugin.ASK_TIMEOUT_MS)
    }

    private fun answer(action: String) = InputDevicesReceiverPlugin.AskReceiver().onReceive(
        app, android.content.Intent(action).putExtra(InputDevicesReceiverPlugin.EXTRA_DEVICE, "desk-ask"),
    )

    @Test
    fun allowTurnsGlideOnForThatDeskAndThePromptGoes() {
        plugin().onPacketReceived(ask())
        answer(InputDevicesReceiverPlugin.ACTION_ALLOW)
        assertTrue("the person allowed it", Glide.allowed(app, "desk-ask"))
        assertTrue("answered: gone", notices().isEmpty())
    }

    @Test
    fun notNowTellsTheDeskItWasDeclinedAndThePromptGoes() {
        val p = plugin()
        p.onPacketReceived(ask())
        p.reportDeclined()
        val report = sent.last()
        assertEquals(false, report.getBoolean("allowed", true))
        assertEquals(InputDevicesReceiverPlugin.REASON_DECLINED, report.getString("reason"))
        answer(InputDevicesReceiverPlugin.ACTION_NOT_NOW)
        assertFalse(Glide.allowed(app, "desk-ask"))
        assertTrue(notices().isEmpty())
    }

    @Test
    fun allowedBySomeOtherWayThePromptGoes() {
        val p = plugin()
        p.onPacketReceived(ask())
        assertEquals(1, notices().size)
        // The person turned the switch on in the app instead, with the service running: the report says allowed, the prompt goes.
        org.robolectric.Robolectric.buildService(org.desklink.mobile.plugins.mousereceiver.MouseReceiverService::class.java).create()
        try {
            Glide.setAllowed(app, "desk-ask", true)
            p.reportAllowed()
            assertEquals(true, sent.last().getBoolean("allowed", false))
            assertTrue(notices().isEmpty())
        } finally {
            org.desklink.mobile.plugins.mousereceiver.MouseReceiverService.instance = null
        }
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
}
